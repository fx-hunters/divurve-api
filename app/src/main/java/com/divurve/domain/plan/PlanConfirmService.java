package com.divurve.domain.plan;

import static java.util.Objects.requireNonNull;

import com.divurve.common.architecture.UseCase;
import com.divurve.common.exception.NotFoundException;
import com.divurve.domain.goal.GoalRepository;
import com.divurve.domain.goal.entity.Goal;
import com.divurve.domain.plan.entity.Plan;
import com.divurve.domain.plan.entity.PlanCalculationMeta;
import com.divurve.domain.plan.entity.PlanCostSummary;
import com.divurve.domain.plan.entity.PlanStep;
import java.util.List;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

/**
 * 계산된 계획을 저장하고 버전을 관리한다 (플래너 명세 §11·§13.1·§18).
 *
 * <p>계산은 하지 않는다 — {@link PlanCalculationService} 가 만든 {@link PlanDraft} 를 그대로
 * 영속화한다. 그래야 사용자가 미리보기에서 본 수치와 저장된 계획이 어긋나지 않는다.
 *
 * <p>버전 규칙 (명세 §18·§21-10):
 * <ul>
 *   <li>새 계획은 {@code 마지막 버전 + 1} 로 저장된다.</li>
 *   <li>기존 활성 계획은 {@code superseded} 로 내려가고 새 계획 id 를 {@code superseded_by} 에 남긴다.</li>
 *   <li>목표당 활성 계획이 하나라는 것은 {@code uq_plans_active_per_goal} 부분 유니크 인덱스가
 *       보장한다 — 동시 요청이 겹치면 코드만으로는 막히지 않는다.</li>
 * </ul>
 *
 * <p>이전 구현이 받던 {@code safeRatio}·{@code splitCount}·{@code opportunityAmount}·
 * {@code triggerRate} 는 전부 사라졌다. 명세 §23 이 그 값들의 산출 근거를 불명으로 지목했고,
 * §24 가 MVP 계산 정책을 균등 회차로 확정했다.
 */
@UseCase
public class PlanConfirmService {

    private final GoalRepository goalRepository;
    private final PlanRepository planRepository;
    private final PlanStepRepository planStepRepository;

    public PlanConfirmService(
            GoalRepository goalRepository,
            PlanRepository planRepository,
            PlanStepRepository planStepRepository) {
        this.goalRepository = requireNonNull(goalRepository, "goalRepository");
        this.planRepository = requireNonNull(planRepository, "planRepository");
        this.planStepRepository = requireNonNull(planStepRepository, "planStepRepository");
    }

    /**
     * 계산된 계획을 활성 계획으로 저장한다.
     *
     * @param goalId       목표 ID
     * @param draft        계산된 계획
     * @param changeReason 버전 변경 사유 (최초 생성은 {@code null})
     * @return 저장된 계획
     * @throws NotFoundException 목표를 찾을 수 없는 경우
     */
    @Transactional
    public Plan confirm(UUID goalId, PlanDraft draft, String changeReason) {
        requireNonNull(draft, "draft");
        Goal goal = goalRepository.findById(goalId)
                .orElseThrow(() -> new NotFoundException("목표를 찾을 수 없습니다: " + goalId));

        Plan saved = planRepository.save(newPlan(goal, draft, changeReason, nextVersion(goalId)));
        supersedePrevious(goalId, saved);
        saveSteps(saved, draft.steps());
        return saved;
    }

    /**
     * 계산된 계획을 <b>적용하지 않고</b> draft 로만 저장한다 (명세 §16·§18).
     *
     * <p>시나리오 미리보기가 쓴다. 활성 계획을 건드리지 않으므로 {@code superseded} 전이도,
     * 활성 승격도 하지 않는다 (§21-9). 사용자가 승인하면 {@link PlanApplyService} 가 이 계획을
     * 승격시킨다 — 그때 다시 계산하지 않기 위해 미리 저장해 둔다.
     *
     * @param goalId       목표 ID
     * @param draft        계산된 계획
     * @param changeReason 변경 이유 코드 (명세 §16)
     * @return 저장된 draft 계획
     * @throws NotFoundException 목표를 찾을 수 없는 경우
     */
    @Transactional
    public Plan saveDraft(UUID goalId, PlanDraft draft, String changeReason) {
        requireNonNull(draft, "draft");
        Goal goal = goalRepository.findById(goalId)
                .orElseThrow(() -> new NotFoundException("목표를 찾을 수 없습니다: " + goalId));

        Plan saved = planRepository.save(newPlan(goal, draft, changeReason, nextVersion(goalId)));
        saveSteps(saved, draft.steps());
        return saved;
    }

    /**
     * 다음 계획 버전 번호 (명세 §21-10).
     *
     * <p>draft 는 세지 않는다. 미리보기를 세 번 눌렀다고 버전이 3 뛰면 사용자가 보는 이력에
     * 존재하지 않는 버전 구멍이 생긴다 — 버전은 <b>적용된</b> 계획의 순번이다.
     */
    int nextVersion(UUID goalId) {
        return planRepository.findTopByGoal_IdAndStatusNotOrderByVersionDesc(goalId, PlanStatus.DRAFT)
                .map(plan -> plan.getVersion() + 1)
                .orElse(1);
    }

    /**
     * 기존 활성 계획을 내린다 (명세 §18).
     *
     * <p>새 계획을 <b>먼저</b> 저장한 뒤 이전 것을 내리면 부분 유니크 인덱스에 걸린다 —
     * 순간적으로 active 가 둘이 되기 때문이다. 그래서 새 계획은 draft 로 만들어 저장하고,
     * 이전 것을 내린 다음 활성으로 승격한다.
     */
    private void supersedePrevious(UUID goalId, Plan saved) {
        // 새 계획은 draft 로 저장했으므로 이 목록에 자기 자신은 없다.
        planRepository.findByGoal_IdAndStatus(goalId, PlanStatus.ACTIVE)
                .forEach(previous -> {
                    previous.deactivate();
                    previous.supersededBy(saved.getId());
                });
        planRepository.flush();
        saved.activate();
        planRepository.flush();
    }

    private Plan newPlan(Goal goal, PlanDraft draft, String changeReason, int version) {
        PlanRateContext rates = draft.rateContext();
        return Plan.builder(goal, version)
                // 활성 승격은 이전 계획을 내린 뒤에 한다 — 위 supersedePrevious 주석 참고.
                .status(PlanStatus.DRAFT)
                .reason(changeReason)
                .planEndDate(draft.summary().planEndDate())
                .calculationMeta(PlanCalculationMeta.builder(draft.policyVersion())
                        .rateAsOf(rates.rateAsOf())
                        .forecastAsOf(rates.forecastAsOf())
                        .rates(rates.lowRate(), rates.baseRate(), rates.highRate())
                        .spreadRatio(rates.spreadRatio())
                        .feeKrw(rates.feeKrw())
                        .quoteUnit(rates.quoteUnit())
                        .build())
                .costSummary(PlanCostSummary.of(
                        draft.summary().budgetState(),
                        draft.summary().costRange().lowKrw(),
                        draft.summary().costRange().baseKrw(),
                        draft.summary().costRange().highKrw()))
                .build();
    }

    private void saveSteps(Plan plan, List<PlanDraft.Step> steps) {
        for (PlanDraft.Step step : steps) {
            PlanStep planStep = PlanStep.create(
                    plan,
                    step.seq(),
                    step.scheduledDate(),
                    step.amount().doubleValue(),
                    0.0,
                    PlanStepStatus.SCHEDULED);
            planStep.recordCostBasis(
                    step.budgetKrw(),
                    plan.getCalculationMeta().getBaseRate(),
                    step.costRange().lowKrw(),
                    step.costRange().highKrw());
            planStepRepository.save(planStep);
        }
    }
}
