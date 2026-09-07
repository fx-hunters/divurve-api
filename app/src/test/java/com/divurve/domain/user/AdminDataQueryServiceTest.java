package com.divurve.domain.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.divurve.common.exception.NotFoundException;
import com.divurve.domain.RepositoryTestBase;
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
import com.divurve.domain.plan.PlanStatus;
import com.divurve.domain.plan.PlanStepStatus;
import com.divurve.domain.plan.entity.Plan;
import com.divurve.domain.plan.entity.PlanStep;
import com.divurve.domain.settings.RiskProfileRepository;
import com.divurve.domain.settings.UserSettingsRepository;
import com.divurve.domain.stress.StressTestRunRepository;
import com.divurve.domain.user.entity.User;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * {@link AdminDataQueryService} — 사용자 전 데이터 수집 (이슈 #111).
 *
 * <p>실 Postgres 로 검증하는 이유는 하나다: <b>다른 사용자의 데이터가 섞이지 않는가</b>(NFR-SE-03).
 * 목으로는 소유자 필터가 실제로 걸리는지 확인할 수 없다.
 */
@DisplayName("AdminDataQueryService")
class AdminDataQueryServiceTest extends RepositoryTestBase {

    @Autowired private UserRepository userRepository;
    @Autowired private HoldingRepository holdingRepository;
    @Autowired private DepositRepository depositRepository;
    @Autowired private KrwAssetRepository krwAssetRepository;
    @Autowired private GoalRepository goalRepository;
    @Autowired private PlanRepository planRepository;
    @Autowired private PlanStepRepository planStepRepository;
    @Autowired private RiskProfileRepository riskProfileRepository;
    @Autowired private UserSettingsRepository userSettingsRepository;
    @Autowired private StressTestRunRepository stressTestRunRepository;

    private AdminDataQueryService service;

    @BeforeEach
    void setUp() {
        service = new AdminDataQueryService(
                userRepository, holdingRepository, depositRepository, krwAssetRepository,
                goalRepository, planRepository, planStepRepository, riskProfileRepository,
                userSettingsRepository, stressTestRunRepository);
    }

    private User newUser(String label) {
        return userRepository.save(
                User.create(label + "-" + UUID.randomUUID() + "@divurve.local", label, "hash"));
    }

    /** 사용자 하나에 자산·목표·계획·회차를 한 벌씩 만든다. */
    private User seed(String label, String ticker) {
        User owner = newUser(label);
        holdingRepository.save(Holding.create(owner, ticker, "USD", 10.0, 100.0));
        depositRepository.save(Deposit.create(owner, "USD", new BigDecimal("500.0000")));
        krwAssetRepository.save(
                KrwAsset.create(owner, "cash", label + " 현금", 1_000_000L, Instant.now()));

        Goal goal = goalRepository.save(
                Goal.builder(owner, label + " 목표", "onetime", "travel", "USD")
                        .targetAmount(4000.0)
                        .allocatedHoldingAmount(0.0)
                        .status("active")
                        .build());
        Plan plan = planRepository.save(
                Plan.builder(goal, 1).status(PlanStatus.ACTIVE).build());
        planStepRepository.save(
                PlanStep.create(plan, 1, LocalDate.of(2026, 10, 1), 1000.0, 0.0,
                        PlanStepStatus.SCHEDULED));
        return owner;
    }

    @Test
    @DisplayName("사용자의 전 도메인 데이터를 모은다")
    void collect_GathersEveryDomain() {
        User owner = seed("mine", "AAPL");

        AdminDataQueryService.UserDataBundle bundle = service.collect(owner.getId());

        assertThat(bundle.holdings()).hasSize(1);
        assertThat(bundle.deposits()).hasSize(1);
        assertThat(bundle.krwAssets()).hasSize(1);
        assertThat(bundle.goals()).hasSize(1);
        assertThat(bundle.plans()).hasSize(1);
        assertThat(bundle.planSteps()).hasSize(1);
        assertThat(bundle.stressTestRuns()).isEmpty();
    }

    @Test
    @DisplayName("다른 사용자의 데이터가 섞이지 않는다 — NFR-SE-03")
    void collect_IsolatesOwners() {
        User mine = seed("mine", "AAPL");
        seed("other", "VOO");

        AdminDataQueryService.UserDataBundle bundle = service.collect(mine.getId());

        assertThat(bundle.holdings()).extracting(Holding::getTicker).containsExactly("AAPL");
        assertThat(bundle.goals()).extracting(Goal::getName).containsExactly("mine 목표");
        assertThat(bundle.planSteps()).hasSize(1);
    }

    @Test
    @DisplayName("데이터가 없는 사용자는 빈 목록과 null 을 돌려준다 — 404 가 아니다")
    void collect_EmptyUser() {
        User owner = newUser("empty");

        AdminDataQueryService.UserDataBundle bundle = service.collect(owner.getId());

        assertThat(bundle.holdings()).isEmpty();
        assertThat(bundle.deposits()).isEmpty();
        assertThat(bundle.krwAssets()).isEmpty();
        assertThat(bundle.goals()).isEmpty();
        assertThat(bundle.plans()).isEmpty();
        assertThat(bundle.planSteps()).isEmpty();
        assertThat(bundle.riskProfile()).isNull();
        assertThat(bundle.userSettings()).isNull();
    }

    @Test
    @DisplayName("없는 사용자는 404 다")
    void collect_MissingUser_Throws() {
        assertThatThrownBy(() -> service.collect(UUID.randomUUID()))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    @DisplayName("null 인자와 의존은 거부한다")
    void nullArguments_Throw() {
        assertThatThrownBy(() -> service.collect(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new AdminDataQueryService(
                null, holdingRepository, depositRepository, krwAssetRepository, goalRepository,
                planRepository, planStepRepository, riskProfileRepository, userSettingsRepository,
                stressTestRunRepository))
                .isInstanceOf(NullPointerException.class);
    }
}
