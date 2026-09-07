package com.divurve.api.dto.plan;

import com.divurve.domain.plan.PlanDraft;
import com.divurve.domain.plan.PlanRateContext;
import com.divurve.domain.plan.PlanStepStatus;
import com.divurve.domain.plan.entity.Plan;
import com.divurve.domain.plan.entity.PlanCalculationMeta;
import com.divurve.domain.plan.entity.PlanCostSummary;
import com.divurve.domain.plan.entity.PlanStep;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * 도메인 값을 계획 응답으로 옮긴다 (플래너 명세 §11).
 *
 * <p>두 갈래로 들어온다 — 아직 저장되지 않은 {@link PlanDraft}(미리보기)와 저장된
 * {@link Plan}+{@link PlanStep}(조회). 둘 다 같은 {@link PlanResponse} 로 나가야 사용자가
 * 미리보기에서 본 화면과 저장 후 화면이 같다.
 *
 * <p>불변조건 §21-13 — 프론트에 표시되는 금액·통화·날짜는 API 응답과 일치해야 한다. 이 매퍼가
 * 값을 바꾸거나 반올림하지 않는 이유다.
 */
public final class PlanResponseMapper {

    private PlanResponseMapper() {
    }

    /** 저장 전 미리보기 응답 (명세 §12 장면 3·4). */
    public static PlanResponse toPreviewResponse(PlanDraft draft, String goalId) {
        Objects.requireNonNull(draft, "draft");
        return new PlanResponse(
                null,
                goalId,
                null,
                toCalculationMeta(draft.calculatedAt(), draft.policyVersion(), draft.rateContext()),
                toGoalSummary(draft.goal()),
                toSummary(draft.summary()),
                draft.steps().stream().map(PlanResponseMapper::toStep).toList(),
                draft.warnings(),
                PlanResponse.DISCLAIMER);
    }

    /** 저장된 계획 응답 (확정·활성 계획 조회·버전 상세). */
    public static PlanResponse toPlanResponse(Plan plan, List<PlanStep> steps, PlanDraft.GoalSummary goal) {
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(steps, "steps");

        PlanCalculationMeta meta = plan.getCalculationMeta();
        PlanCostSummary cost = plan.getCostSummary();
        Integer nextActionSeq = nextActionSeq(steps);

        return new PlanResponse(
                plan.getId().toString(),
                plan.getGoal().getId().toString(),
                plan.getVersion(),
                toCalculationMeta(plan.getCreatedAt(), meta, goal.currencyCode()),
                toGoalSummary(goal),
                new PlanResponse.Summary(
                        plan.getStatus(),
                        plan.getPlanEndDate(),
                        steps.size(),
                        countByStatus(steps, PlanStepStatus.COMPLETED),
                        countOpen(steps),
                        countByStatus(steps, PlanStepStatus.SKIPPED),
                        nextActionSeq,
                        toCostRange(cost),
                        cost == null ? null : cost.getBudgetState(),
                        null),
                steps.stream().map(step -> toStep(step, nextActionSeq)).toList(),
                List.of(),
                PlanResponse.DISCLAIMER);
    }

    // ── 미리보기 경로 ──────────────────────────────────────────────────────

    private static PlanResponse.CalculationMeta toCalculationMeta(
            Instant calculatedAt, String policyVersion, PlanRateContext rates) {
        return new PlanResponse.CalculationMeta(
                calculatedAt,
                rates.rateAsOf(),
                rates.forecastAsOf(),
                policyVersion,
                rates.currencyCode(),
                rates.quoteUnit(),
                new PlanResponse.CalculationMeta.Rates(
                        rates.lowRate(), rates.baseRate(), rates.highRate()),
                rates.spreadRatio(),
                rates.feeKrw());
    }

    private static PlanResponse.GoalSummary toGoalSummary(PlanDraft.GoalSummary goal) {
        return new PlanResponse.GoalSummary(
                goal.goalType(),
                goal.purpose(),
                goal.currencyCode(),
                toDouble(goal.targetAmount()),
                goal.roundBudgetKrw(),
                goal.allocatedHoldingAmount().doubleValue(),
                goal.remainingAmount().doubleValue(),
                goal.targetDate());
    }

    private static PlanResponse.Summary toSummary(PlanDraft.Summary summary) {
        return new PlanResponse.Summary(
                summary.status(),
                summary.planEndDate(),
                summary.totalRounds(),
                summary.completedRounds(),
                summary.scheduledRounds(),
                summary.skippedRounds(),
                summary.nextActionSeq(),
                toCostRange(summary.costRange()),
                summary.budgetState(),
                toAcquisitionRange(summary.cumulativeAcquisition()));
    }

    private static PlanResponse.Step toStep(PlanDraft.Step step) {
        return new PlanResponse.Step(
                step.seq(),
                step.scheduledDate(),
                step.amount().doubleValue(),
                step.budgetKrw(),
                toCostRange(step.costRange()),
                toAcquisitionRange(step.acquisition()),
                step.executedAmount().doubleValue(),
                step.executedRate(),
                step.executedDate(),
                step.status(),
                step.nextAction());
    }

    // ── 저장된 계획 경로 ───────────────────────────────────────────────────

    private static PlanResponse.CalculationMeta toCalculationMeta(
            Instant createdAt, PlanCalculationMeta meta, String currencyCode) {
        if (meta == null) {
            // V16 이전에 저장된 계획에는 계산 메타가 없다. 값을 지어내지 않고 비워 둔다 —
            // 어떤 전제로 계산됐는지 모르는 계획에 지금의 가정을 적으면 감사 기록이 거짓이 된다.
            return null;
        }
        return new PlanResponse.CalculationMeta(
                createdAt,
                meta.getRateAsOf(),
                meta.getForecastAsOf(),
                meta.getPolicyVersion(),
                currencyCode,
                meta.getQuoteUnit() == null ? 1 : meta.getQuoteUnit(),
                new PlanResponse.CalculationMeta.Rates(
                        orZero(meta.getRateLow()), orZero(meta.getBaseRate()), orZero(meta.getRateHigh())),
                orZero(meta.getSpreadRatio()),
                meta.getFeeKrw() == null ? 0L : meta.getFeeKrw());
    }

    private static PlanResponse.Step toStep(PlanStep step, Integer nextActionSeq) {
        return new PlanResponse.Step(
                step.getSeq(),
                step.getScheduledDate(),
                step.getAmount(),
                step.getBudgetKrw(),
                new PlanResponse.CostRange(
                        orZero(step.getLowCostKrw()),
                        orZero(step.getLowCostKrw()),
                        orZero(step.getHighCostKrw())),
                null,
                step.getExecutedAmount(),
                step.getExecutedRate(),
                step.getExecutedDate(),
                step.getStatus(),
                nextActionSeq != null && nextActionSeq == step.getSeq());
    }

    /**
     * 지금 확인하거나 기록해야 할 다음 행동 (명세 §11.3·§12 장면 6).
     *
     * <p>가장 가까운 미완료 회차 <b>하나</b>다. 명세 §26 은 사용자가 최종적으로 보는 것이
     * "지금 확인하거나 기록할 다음 행동 하나"라고 못박는다 — 여러 개를 내면 무엇부터 할지가
     * 다시 사용자의 짐이 된다.
     */
    private static Integer nextActionSeq(List<PlanStep> steps) {
        return steps.stream()
                .filter(PlanStep::isOpen)
                .map(PlanStep::getSeq)
                .min(Integer::compareTo)
                .orElse(null);
    }

    private static int countByStatus(List<PlanStep> steps, String status) {
        return (int) steps.stream().filter(step -> status.equals(step.getStatus())).count();
    }

    private static int countOpen(List<PlanStep> steps) {
        return (int) steps.stream().filter(PlanStep::isOpen).count();
    }

    // ── 공통 ──────────────────────────────────────────────────────────────

    private static PlanResponse.CostRange toCostRange(PlanDraft.CostRange range) {
        return range == null ? null
                : new PlanResponse.CostRange(range.lowKrw(), range.baseKrw(), range.highKrw());
    }

    private static PlanResponse.CostRange toCostRange(PlanCostSummary cost) {
        return cost == null ? null : new PlanResponse.CostRange(
                orZero(cost.getLowCostKrw()), orZero(cost.getBaseCostKrw()), orZero(cost.getHighCostKrw()));
    }

    private static PlanResponse.AcquisitionRange toAcquisitionRange(PlanDraft.AcquisitionRange range) {
        return range == null ? null : new PlanResponse.AcquisitionRange(
                range.low().doubleValue(), range.base().doubleValue(), range.high().doubleValue());
    }

    private static Double toDouble(BigDecimal value) {
        return value == null ? null : value.doubleValue();
    }

    private static double orZero(Double value) {
        return value == null ? 0.0 : value;
    }

    private static long orZero(Long value) {
        return value == null ? 0L : value;
    }
}
