package com.divurve.domain.user;

import com.divurve.common.architecture.UseCase;
import com.divurve.common.exception.NotFoundException;
import com.divurve.domain.goal.GoalRepository;
import com.divurve.domain.goal.entity.Goal;
import com.divurve.domain.holding.DepositRepository;
import com.divurve.domain.holding.HoldingRepository;
import com.divurve.domain.holding.KrwAssetRepository;
import com.divurve.domain.holding.entity.Deposit;
import com.divurve.domain.holding.entity.Holding;
import com.divurve.domain.holding.entity.KrwAsset;
import com.divurve.domain.plan.PlanRepository;
import com.divurve.domain.plan.PlanStepRepository;
import com.divurve.domain.plan.entity.Plan;
import com.divurve.domain.plan.entity.PlanStep;
import com.divurve.domain.settings.RiskProfileRepository;
import com.divurve.domain.settings.UserSettingsRepository;
import com.divurve.domain.settings.entity.RiskProfile;
import com.divurve.domain.settings.entity.UserSettings;
import com.divurve.domain.stress.StressTestRunRepository;
import com.divurve.domain.stress.entity.StressTestRun;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

/**
 * 사용자 한 명의 <b>모든 도메인 데이터</b>를 모은다 (이슈 #111).
 *
 * <p>운영 점검이 목적이므로 표시용으로 컬럼을 고르지 않는다 — 그래서 기존 사용자용 DTO
 * ({@code api/dto/asset/**} 등)를 재사용하지 않는다. 그쪽은 화면이 쓰는 값만 담고 있어
 * "무엇이 실제로 저장돼 있는가" 를 보는 이 화면의 목적과 어긋난다.
 *
 * <p>계획 회차({@code plan_steps})는 계획에 딸린 것이라 계획을 먼저 찾고 그 아래를 모은다.
 * 사용자 → 목표 → 계획 → 회차 순의 소유 사슬을 그대로 따라간다.
 *
 * <p>계산하지 않는다. 합계·비율을 만들지 않고 저장된 행을 그대로 돌려준다.
 */
@UseCase
public class AdminDataQueryService {

    private final UserRepository userRepository;
    private final HoldingRepository holdingRepository;
    private final DepositRepository depositRepository;
    private final KrwAssetRepository krwAssetRepository;
    private final GoalRepository goalRepository;
    private final PlanRepository planRepository;
    private final PlanStepRepository planStepRepository;
    private final RiskProfileRepository riskProfileRepository;
    private final UserSettingsRepository userSettingsRepository;
    private final StressTestRunRepository stressTestRunRepository;

    public AdminDataQueryService(
            UserRepository userRepository,
            HoldingRepository holdingRepository,
            DepositRepository depositRepository,
            KrwAssetRepository krwAssetRepository,
            GoalRepository goalRepository,
            PlanRepository planRepository,
            PlanStepRepository planStepRepository,
            RiskProfileRepository riskProfileRepository,
            UserSettingsRepository userSettingsRepository,
            StressTestRunRepository stressTestRunRepository) {
        this.userRepository = Objects.requireNonNull(userRepository, "userRepository");
        this.holdingRepository = Objects.requireNonNull(holdingRepository, "holdingRepository");
        this.depositRepository = Objects.requireNonNull(depositRepository, "depositRepository");
        this.krwAssetRepository = Objects.requireNonNull(krwAssetRepository, "krwAssetRepository");
        this.goalRepository = Objects.requireNonNull(goalRepository, "goalRepository");
        this.planRepository = Objects.requireNonNull(planRepository, "planRepository");
        this.planStepRepository = Objects.requireNonNull(planStepRepository, "planStepRepository");
        this.riskProfileRepository =
                Objects.requireNonNull(riskProfileRepository, "riskProfileRepository");
        this.userSettingsRepository =
                Objects.requireNonNull(userSettingsRepository, "userSettingsRepository");
        this.stressTestRunRepository =
                Objects.requireNonNull(stressTestRunRepository, "stressTestRunRepository");
    }

    /**
     * 사용자의 전 도메인 데이터를 모은다.
     *
     * @throws NotFoundException 없는 사용자인 경우 (404)
     */
    @Transactional(readOnly = true)
    public UserDataBundle collect(UUID userId) {
        Objects.requireNonNull(userId, "userId");
        if (!userRepository.existsById(userId)) {
            throw new NotFoundException("존재하지 않는 사용자입니다: " + userId);
        }

        List<Goal> goals = goalRepository.findByOwner_Id(userId);

        List<Plan> plans = new ArrayList<>();
        for (Goal goal : goals) {
            plans.addAll(planRepository.findByGoal_IdOrderByVersionDesc(goal.getId()));
        }

        List<PlanStep> planSteps = new ArrayList<>();
        for (Plan plan : plans) {
            planSteps.addAll(planStepRepository.findByPlan_IdOrderBySeqAsc(plan.getId()));
        }

        return new UserDataBundle(
                holdingRepository.findByOwner_Id(userId),
                depositRepository.findByOwner_Id(userId),
                krwAssetRepository.findByOwner_Id(userId),
                goals,
                plans,
                planSteps,
                riskProfileRepository.findByOwner_Id(userId).orElse(null),
                userSettingsRepository.findByOwner_Id(userId).orElse(null),
                stressTestRunRepository.findByOwner_IdOrderByCreatedAtDesc(userId));
    }

    /**
     * 사용자 한 명의 전 도메인 데이터.
     *
     * <p>엔티티를 그대로 담는다 — 응답 직렬화는 {@code api/dto/admin} 이 맡고, 여기서는 "무엇을
     * 모을 것인가" 만 정한다. 컬럼을 고르는 판단이 두 곳에 흩어지지 않게 하기 위해서다.
     *
     * @param holdings       보유 종목
     * @param deposits       외화 예금
     * @param krwAssets      원화 자산
     * @param goals          목표
     * @param plans          계획 (목표별 전 버전)
     * @param planSteps      계획 회차
     * @param riskProfile    위험 성향. 진단하지 않았으면 {@code null}
     * @param userSettings   사용자 설정. 없으면 {@code null}
     * @param stressTestRuns 스트레스 실행 이력
     */
    public record UserDataBundle(
            List<Holding> holdings,
            List<Deposit> deposits,
            List<KrwAsset> krwAssets,
            List<Goal> goals,
            List<Plan> plans,
            List<PlanStep> planSteps,
            RiskProfile riskProfile,
            UserSettings userSettings,
            List<StressTestRun> stressTestRuns) {
    }
}
