package com.divurve.domain.plan;

import static java.util.Objects.requireNonNull;

import com.divurve.common.architecture.UseCase;
import com.divurve.common.exception.NotFoundException;
import com.divurve.domain.goal.PriorityConstraint;
import com.divurve.domain.goal.entity.Goal;
import com.divurve.domain.plan.entity.Plan;
import com.divurve.domain.plan.entity.PlanStep;
import com.divurve.engine.planner.AdjustmentOption;
import com.divurve.engine.planner.AdjustmentOptionSelector;
import com.divurve.engine.planner.BudgetState;
import com.divurve.engine.planner.PriorityDimension;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

/**
 * 상황 변화 미리보기 (플래너 명세 §16·§17).
 *
 * <p><b>활성 계획을 바꾸지 않는다</b> (§21-9). 재계산 결과는 {@code draft} 로 저장하고 임시
 * 식별자만 돌려준다. 적용은 사용자가 승인한 뒤 {@link PlanApplyService} 가 한다.
 *
 * <p>재계산의 기준은 목표의 {@code priority_constraint} 다 (§17). 이 서비스는 그 값을 읽어
 * 조정 선택지 순서에 반영할 뿐 <b>바꾸지 않는다</b> — 어느 조건을 포기할지는 사용자의 결정이다.
 *
 * <p>수치는 전부 {@link PlanCalculationService} 를 거쳐 engine 이 만든다. 여기서 하는 일은
 * 시나리오에 맞게 <b>입력을 고쳐 넘기고</b>, 나온 결과를 현재 계획과 <b>비교</b>하는 것뿐이다.
 */
@UseCase
public class PlanScenarioService {

    private final PlanRepository planRepository;
    private final PlanStepRepository planStepRepository;
    private final PlanCalculationService planCalculationService;
    private final PlanConfirmService planConfirmService;
    private final AdjustmentOptionSelector adjustmentOptionSelector;

    public PlanScenarioService(
            PlanRepository planRepository,
            PlanStepRepository planStepRepository,
            PlanCalculationService planCalculationService,
            PlanConfirmService planConfirmService,
            AdjustmentOptionSelector adjustmentOptionSelector) {
        this.planRepository = requireNonNull(planRepository, "planRepository");
        this.planStepRepository = requireNonNull(planStepRepository, "planStepRepository");
        this.planCalculationService = requireNonNull(planCalculationService, "planCalculationService");
        this.planConfirmService = requireNonNull(planConfirmService, "planConfirmService");
        this.adjustmentOptionSelector =
                requireNonNull(adjustmentOptionSelector, "adjustmentOptionSelector");
    }

    /**
     * 상황이 바뀌면 계획이 어떻게 되는지 계산한다.
     *
     * @param userId 조회 사용자 — 환율·예측 조회에 쓴다
     * @param planId 비교 기준이 될 현재 계획
     * @param input  상황 변화
     * @return 변경안과 현재 계획의 차이. 활성 계획은 그대로다
     * @throws NotFoundException 계획을 찾을 수 없는 경우
     */
    @Transactional
    public ScenarioPreview preview(UUID userId, UUID planId, ScenarioInput input) {
        requireNonNull(input, "input");
        input.validate();

        Plan basePlan = planRepository.findById(planId)
                .orElseThrow(() -> new NotFoundException("계획을 찾을 수 없습니다: " + planId));
        Goal goal = basePlan.getGoal();
        List<PlanStep> baseSteps = planStepRepository.findByPlan_IdOrderBySeqAsc(planId);

        PlanDraft recalculated = planCalculationService.calculate(userId, mutate(goal, baseSteps, input));
        Plan draftPlan = planConfirmService.saveDraft(goal.getId(), recalculated, input.code().name());

        return compare(basePlan, baseSteps, draftPlan, recalculated, goal, input);
    }

    // ── 시나리오를 계산 입력으로 옮긴다 (명세 §16) ──────────────────────────

    /**
     * 목표 조건에 상황 변화를 반영한다.
     *
     * <p>어느 시나리오든 <b>이미 실행한 회차만큼은 확보한 것으로 본다</b> — 계획 중간에 다시
     * 계산하는 것이므로, 처음부터 다시 모으는 계획을 내놓으면 사용자가 이미 산 외화가 사라진다.
     */
    private PlanInput mutate(Goal goal, List<PlanStep> baseSteps, ScenarioInput input) {
        PlanInput base = PlanInput.from(goal);
        double carried = base.allocatedHoldingAmount() + executedTotal(baseSteps);

        return switch (input.code()) {
            // 환율 시나리오는 목표 조건을 바꾸지 않는다 — 조회 시점 환율로 다시 계산할 뿐이다.
            // 건너뛰기도 마찬가지다: 남은 회차는 오늘부터 다시 생성되므로 건너뛴 회차가 자연히 빠진다.
            case RATE_UP, RATE_DOWN, STEP_SKIPPED -> withHolding(base, carried);
            case BUDGET_DECREASED -> withBudget(withHolding(base, carried), input.newBudgetKrw());
            case TARGET_DATE_CHANGED -> withTargetDate(withHolding(base, carried), input.newTargetDate());
            case TARGET_AMOUNT_CHANGED ->
                    withTargetAmount(withHolding(base, carried), input.newTargetAmount());
            case HOLDING_ADDED -> withHolding(base, carried + input.addedHoldingAmount());
        };
    }

    private static double executedTotal(List<PlanStep> steps) {
        return steps.stream().mapToDouble(PlanStep::getExecutedAmount).sum();
    }

    private static PlanInput withHolding(PlanInput base, double holding) {
        return new PlanInput(
                base.goalType(), base.purpose(), base.currencyCode(), holding, base.targetAmount(),
                base.targetDate(), base.budgetAmountKrw(), base.budgetPeriod(), base.cadence(),
                base.startDate(), base.reviewHorizonMonths());
    }

    private static PlanInput withBudget(PlanInput base, long budgetKrw) {
        return new PlanInput(
                base.goalType(), base.purpose(), base.currencyCode(), base.allocatedHoldingAmount(),
                base.targetAmount(), base.targetDate(), budgetKrw, base.budgetPeriod(),
                base.cadence(), base.startDate(), base.reviewHorizonMonths());
    }

    private static PlanInput withTargetDate(PlanInput base, LocalDate targetDate) {
        return new PlanInput(
                base.goalType(), base.purpose(), base.currencyCode(), base.allocatedHoldingAmount(),
                base.targetAmount(), targetDate, base.budgetAmountKrw(), base.budgetPeriod(),
                base.cadence(), base.startDate(), base.reviewHorizonMonths());
    }

    private static PlanInput withTargetAmount(PlanInput base, double targetAmount) {
        return new PlanInput(
                base.goalType(), base.purpose(), base.currencyCode(), base.allocatedHoldingAmount(),
                targetAmount, base.targetDate(), base.budgetAmountKrw(), base.budgetPeriod(),
                base.cadence(), base.startDate(), base.reviewHorizonMonths());
    }

    // ── 현재 계획과 변경안을 비교한다 (명세 §16 응답) ───────────────────────

    private ScenarioPreview compare(
            Plan basePlan,
            List<PlanStep> baseSteps,
            Plan draftPlan,
            PlanDraft recalculated,
            Goal goal,
            ScenarioInput input) {
        ScenarioPreview.Side before = sideOf(basePlan, baseSteps, goal);
        ScenarioPreview.Side after = sideOf(recalculated);

        List<String> kept = new ArrayList<>();
        List<String> broken = new ArrayList<>();
        classifyConstraints(before, after, goal, kept, broken);

        return new ScenarioPreview(
                basePlan.getId(),
                basePlan.getVersion(),
                draftPlan.getId(),
                draftPlan.getVersion(),
                input.code().name(),
                goal.getPriorityConstraint(),
                before,
                after,
                stepChanges(baseSteps, recalculated.steps()),
                kept,
                broken,
                recalculated.summary().budgetState(),
                adjustmentOptionsFor(goal, recalculated),
                recalculated.warnings());
    }

    /** 현재 계획 쪽 요약 — 저장된 회차에서 되센다. */
    private ScenarioPreview.Side sideOf(Plan plan, List<PlanStep> steps, Goal goal) {
        List<PlanStep> open = steps.stream().filter(PlanStep::isOpen).toList();
        BigDecimal remaining = BigDecimal.valueOf(
                Math.max(goal.getTargetAmount() - goal.getAllocatedHoldingAmount() - executedTotal(steps), 0.0));
        return new ScenarioPreview.Side(
                remaining,
                plan.getPlanEndDate(),
                steps.size(),
                open.size(),
                open.isEmpty() ? null : BigDecimal.valueOf(open.get(0).getAmount()),
                open.isEmpty() ? null : open.get(0).getBudgetKrw(),
                costRangeOf(plan));
    }

    /** 변경안 쪽 요약 — 아직 저장 전 계산값에서 읽는다. */
    private ScenarioPreview.Side sideOf(PlanDraft draft) {
        List<PlanDraft.Step> steps = draft.steps();
        return new ScenarioPreview.Side(
                draft.goal().remainingAmount(),
                draft.summary().planEndDate(),
                steps.size(),
                draft.summary().scheduledRounds(),
                steps.isEmpty() ? null : steps.get(0).amount(),
                steps.isEmpty() ? null : steps.get(0).budgetKrw(),
                draft.summary().costRange());
    }

    private static PlanDraft.CostRange costRangeOf(Plan plan) {
        return Optional.ofNullable(plan.getCostSummary())
                .map(cost -> new PlanDraft.CostRange(
                        cost.getLowCostKrw(), cost.getBaseCostKrw(), cost.getHighCostKrw()))
                .orElse(null);
    }

    /**
     * 유지된 조건과 그렇지 못한 조건을 가른다 (명세 §16·§17).
     *
     * <p>비교는 세 축 전부에 대해 한다 — 우선 조건만 보면 "우선 조건은 지켰지만 나머지가 다
     * 무너진" 변경안이 아무 경고 없이 나간다.
     */
    private static void classifyConstraints(
            ScenarioPreview.Side before,
            ScenarioPreview.Side after,
            Goal goal,
            List<String> kept,
            List<String> broken) {
        classify(PriorityConstraint.AMOUNT,
                sameAmount(before.remainingAmount(), after.remainingAmount()), kept, broken);
        classify(PriorityConstraint.DATE,
                java.util.Objects.equals(before.targetDate(), after.targetDate()), kept, broken);
        classify(PriorityConstraint.BUDGET,
                java.util.Objects.equals(before.roundBudgetKrw(), after.roundBudgetKrw()), kept, broken);
        // 우선 조건이 유지 목록에 없으면 사용자가 지키기로 한 조건이 깨진 것이다 — 그 사실을 앞세운다.
        if (broken.remove(goal.getPriorityConstraint())) {
            broken.add(0, goal.getPriorityConstraint());
        }
    }

    private static void classify(String constraint, boolean unchanged, List<String> kept, List<String> broken) {
        (unchanged ? kept : broken).add(constraint);
    }

    /**
     * 두 금액이 같은지. 변경 전 금액은 늘 계산되므로 {@code null} 이 아니고, 변경 후만 비어 있을 수
     * 있다 — 그때는 <b>같다고 보지 않는다</b>. 모르는 것을 "안 바뀌었다"로 바꾸면 유지 목록에
     * 근거 없이 올라간다.
     */
    private static boolean sameAmount(BigDecimal before, BigDecimal after) {
        return after != null && before.compareTo(after) == 0;
    }

    /**
     * 예산으로 감당되지 않을 때만 선택지를 낸다 (명세 §15·§21-8).
     *
     * <p>감당되는데도 선택지를 띄우면 사용자는 무언가 잘못됐다고 읽는다.
     */
    private List<String> adjustmentOptionsFor(Goal goal, PlanDraft draft) {
        if (!BudgetState.CONSTRAINT_ADJUSTMENT_REQUIRED.name().equals(draft.summary().budgetState())) {
            return List.of();
        }
        return adjustmentOptionSelector.orderedFor(priorityDimensionOf(goal)).stream()
                .map(AdjustmentOption::name)
                .toList();
    }

    /** domain 의 문자열 상수를 engine 열거로 옮긴다. 모르는 값은 명세 기본값인 금액 우선으로 본다. */
    private static PriorityDimension priorityDimensionOf(Goal goal) {
        return switch (String.valueOf(goal.getPriorityConstraint())) {
            case PriorityConstraint.DATE -> PriorityDimension.DATE;
            case PriorityConstraint.BUDGET -> PriorityDimension.BUDGET;
            default -> PriorityDimension.AMOUNT;
        };
    }

    /**
     * 달라진 회차만 추린다 (명세 §16).
     *
     * <p>같은 회차 번호끼리 맞춘다. 재계산은 오늘부터 새 번호를 매기므로 번호가 곧 순서이고,
     * 순서가 같은 두 회차를 비교하는 것이 사용자가 화면에서 하는 비교와 같다.
     */
    private static List<ScenarioPreview.StepChange> stepChanges(
            List<PlanStep> before, List<PlanDraft.Step> after) {
        Map<Integer, PlanStep> beforeBySeq = new LinkedHashMap<>();
        before.forEach(step -> beforeBySeq.put(step.getSeq(), step));
        Map<Integer, PlanDraft.Step> afterBySeq = new LinkedHashMap<>();
        after.forEach(step -> afterBySeq.put(step.seq(), step));

        List<ScenarioPreview.StepChange> changes = new ArrayList<>();
        for (int seq : new TreeSet<>(union(beforeBySeq, afterBySeq))) {
            PlanStep left = beforeBySeq.get(seq);
            PlanDraft.Step right = afterBySeq.get(seq);
            if (left == null) {
                changes.add(new ScenarioPreview.StepChange(
                        seq, ScenarioPreview.ADDED, null, right.scheduledDate(), null, right.amount()));
            } else if (right == null) {
                changes.add(new ScenarioPreview.StepChange(
                        seq, ScenarioPreview.REMOVED, left.getScheduledDate(), null,
                        BigDecimal.valueOf(left.getAmount()), null));
            } else if (differs(left, right)) {
                changes.add(new ScenarioPreview.StepChange(
                        seq, ScenarioPreview.MODIFIED, left.getScheduledDate(), right.scheduledDate(),
                        BigDecimal.valueOf(left.getAmount()), right.amount()));
            }
        }
        return changes;
    }

    private static TreeSet<Integer> union(Map<Integer, ?> left, Map<Integer, ?> right) {
        TreeSet<Integer> all = new TreeSet<>(left.keySet());
        all.addAll(right.keySet());
        return all;
    }

    private static boolean differs(PlanStep before, PlanDraft.Step after) {
        return !java.util.Objects.equals(before.getScheduledDate(), after.scheduledDate())
                || BigDecimal.valueOf(before.getAmount()).compareTo(after.amount()) != 0;
    }
}
