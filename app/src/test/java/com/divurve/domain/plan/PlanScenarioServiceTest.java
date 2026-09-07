package com.divurve.domain.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.divurve.common.exception.NotFoundException;
import com.divurve.domain.goal.GoalType;
import com.divurve.domain.goal.PriorityConstraint;
import com.divurve.domain.goal.entity.Goal;
import com.divurve.domain.plan.entity.Plan;
import com.divurve.domain.plan.entity.PlanCalculationMeta;
import com.divurve.domain.plan.entity.PlanCostSummary;
import com.divurve.domain.plan.entity.PlanStep;
import com.divurve.domain.user.entity.User;
import com.divurve.engine.planner.AdjustmentOptionSelector;
import com.divurve.engine.planner.BudgetState;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * {@link PlanScenarioService} — 상황 변화 미리보기 (플래너 명세 §16·§17).
 *
 * <p>세 가지가 핵심이다 — <b>활성 계획을 바꾸지 않는다</b>(§21-9), 재계산 입력이 시나리오대로
 * <b>바뀌어 넘어간다</b>, 그리고 유지·미유지 조건을 <b>숨기지 않는다</b>(§21-8).
 */
@DisplayName("PlanScenarioService")
class PlanScenarioServiceTest {

    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID GOAL_ID = UUID.randomUUID();
    private static final UUID PLAN_ID = UUID.randomUUID();
    private static final UUID DRAFT_ID = UUID.randomUUID();
    private static final LocalDate TODAY = LocalDate.of(2026, 9, 7);
    private static final LocalDate TARGET_DATE = LocalDate.of(2026, 12, 24);

    private PlanRepository planRepository;
    private PlanStepRepository planStepRepository;
    private PlanCalculationService planCalculationService;
    private PlanConfirmService planConfirmService;
    private PlanScenarioService service;

    private Goal goal;
    private Plan basePlan;
    private Plan draftPlan;

    @BeforeEach
    void setUp() {
        planRepository = mock(PlanRepository.class);
        planStepRepository = mock(PlanStepRepository.class);
        planCalculationService = mock(PlanCalculationService.class);
        planConfirmService = mock(PlanConfirmService.class);
        service = new PlanScenarioService(
                planRepository, planStepRepository, planCalculationService,
                planConfirmService, new AdjustmentOptionSelector());

        goal = Goal.builder(User.createDemo("a@b.com", "사용자"), "여행 자금", "onetime", "travel", "USD")
                .goalType(GoalType.DEADLINE)
                .targetAmount(4000.0)
                .allocatedHoldingAmount(1000.0)
                .targetDate(TARGET_DATE)
                .budgetAmount(500_000L)
                .budgetPeriod("monthly")
                .preferredCadence("weekly")
                .priorityConstraint(PriorityConstraint.AMOUNT)
                .build();
        goal.setIdForTest(GOAL_ID);

        basePlan = Plan.builder(goal, 2)
                .status(PlanStatus.ACTIVE)
                .planEndDate(TARGET_DATE)
                .calculationMeta(PlanCalculationMeta.builder("v1")
                        .rates(1300.0, 1350.0, 1400.0).spreadRatio(0.01).feeKrw(10_000L)
                        .quoteUnit(1).build())
                .costSummary(PlanCostSummary.of(
                        BudgetState.COVERED_IN_RANGE.name(), 3_900_000L, 4_050_000L, 4_200_000L))
                .build();
        basePlan.setIdForTest(PLAN_ID);

        draftPlan = Plan.builder(goal, 3).status(PlanStatus.DRAFT).build();
        draftPlan.setIdForTest(DRAFT_ID);

        when(planRepository.findById(PLAN_ID)).thenReturn(Optional.of(basePlan));
        when(planStepRepository.findByPlan_IdOrderBySeqAsc(PLAN_ID)).thenReturn(List.of());
        when(planConfirmService.saveDraft(any(), any(), any())).thenReturn(draftPlan);
        when(planCalculationService.calculate(any(), any())).thenReturn(draft(TARGET_DATE, 3000.0));
    }

    // ── 픽스처 ────────────────────────────────────────────────────────────

    private PlanStep step(int seq, double amount) {
        return PlanStep.create(basePlan, seq, TODAY.plusWeeks(seq - 1L), amount, 0.0,
                PlanStepStatus.SCHEDULED);
    }

    private PlanStep completedStep(int seq, double executed) {
        PlanStep step = step(seq, executed);
        step.markAsCompleted(executed, 1350.0, TODAY, "key-" + seq);
        return step;
    }

    private PlanDraft draft(LocalDate endDate, double remaining, PlanDraft.Step... steps) {
        return draft(endDate, remaining, BudgetState.COVERED_IN_RANGE.name(), null, steps);
    }

    private PlanDraft draft(
            LocalDate endDate, double remaining, String budgetState, Long roundBudgetKrw,
            PlanDraft.Step... steps) {
        PlanRateContext rates = new PlanRateContext(
                "USD", 1300.0, 1350.0, 1400.0, 0.01, 10_000L, 1, 2,
                Instant.parse("2026-09-07T00:00:00Z"), null, false);
        return new PlanDraft(
                Instant.parse("2026-09-07T00:00:00Z"),
                "v1",
                rates,
                new PlanDraft.GoalSummary(
                        GoalType.DEADLINE, "travel", "USD", BigDecimal.valueOf(4000.0),
                        roundBudgetKrw, BigDecimal.valueOf(1000.0), BigDecimal.valueOf(remaining),
                        endDate, PriorityConstraint.AMOUNT),
                new PlanDraft.Summary(
                        PlanStatus.DRAFT, endDate, steps.length, 0, steps.length, 0,
                        steps.length == 0 ? null : 1,
                        new PlanDraft.CostRange(3_800_000L, 3_950_000L, 4_100_000L),
                        budgetState, null),
                List.of(steps),
                List.of("FORECAST_UNAVAILABLE"));
    }

    private PlanDraft.Step draftStep(int seq, LocalDate date, double amount) {
        return new PlanDraft.Step(
                seq, date, BigDecimal.valueOf(amount), null,
                new PlanDraft.CostRange(1L, 2L, 3L), null,
                BigDecimal.ZERO, null, null, PlanStepStatus.SCHEDULED, seq == 1);
    }

    private ScenarioInput input(ScenarioCode code) {
        return new ScenarioInput(code, null, null, null, null, null);
    }

    // ── 테스트 ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("없는 계획은 404 다")
    void preview_UnknownPlan_Throws() {
        UUID missing = UUID.randomUUID();
        when(planRepository.findById(missing)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.preview(USER_ID, missing, input(ScenarioCode.RATE_UP)))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("계획을 찾을 수 없습니다");
    }

    @Test
    @DisplayName("입력이 없으면 거부한다")
    void preview_NullInput_Throws() {
        assertThatThrownBy(() -> service.preview(USER_ID, PLAN_ID, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("활성 계획을 바꾸지 않는다 — 불변조건 §21-9")
    void preview_DoesNotTouchActivePlan() {
        ScenarioPreview preview = service.preview(USER_ID, PLAN_ID, input(ScenarioCode.RATE_UP));

        assertThat(basePlan.getStatus()).isEqualTo(PlanStatus.ACTIVE);
        assertThat(basePlan.getSupersededBy()).isNull();
        verify(planRepository, never()).save(any());
        verify(planConfirmService, never()).confirm(any(), any(), any());
        assertThat(preview.draftPlanId()).isEqualTo(DRAFT_ID);
        assertThat(preview.basePlanId()).isEqualTo(PLAN_ID);
        assertThat(preview.baseVersion()).isEqualTo(2);
        assertThat(preview.draftVersion()).isEqualTo(3);
    }

    @Test
    @DisplayName("변경 이유 코드를 draft 에 남긴다 — 나중에 왜 바뀌었는지 알 수 있어야 한다")
    void preview_StoresChangeReason() {
        service.preview(USER_ID, PLAN_ID, input(ScenarioCode.RATE_DOWN));

        verify(planConfirmService).saveDraft(eq(GOAL_ID), any(), eq("RATE_DOWN"));
    }

    @Nested
    @DisplayName("시나리오별 재계산 입력 (명세 §16)")
    class Mutation {

        private PlanInput captureInput(ScenarioInput scenario) {
            service.preview(USER_ID, PLAN_ID, scenario);
            ArgumentCaptor<PlanInput> captor = ArgumentCaptor.forClass(PlanInput.class);
            verify(planCalculationService).calculate(eq(USER_ID), captor.capture());
            return captor.getValue();
        }

        @Test
        @DisplayName("실행한 회차만큼은 확보한 것으로 본다 — 이미 산 외화가 사라지면 안 된다")
        void carriesExecutedProgress() {
            when(planStepRepository.findByPlan_IdOrderBySeqAsc(PLAN_ID))
                    .thenReturn(List.of(completedStep(1, 700.0), step(2, 1000.0)));

            assertThat(captureInput(input(ScenarioCode.RATE_UP)).allocatedHoldingAmount())
                    .isEqualTo(1700.0);
        }

        @Test
        @DisplayName("환율 시나리오는 목표 조건을 바꾸지 않는다 — 조회 시점 환율로 다시 계산할 뿐이다")
        void rateScenarios_KeepGoalConditions() {
            PlanInput mutated = captureInput(input(ScenarioCode.RATE_UP));

            assertThat(mutated.targetAmount()).isEqualTo(4000.0);
            assertThat(mutated.targetDate()).isEqualTo(TARGET_DATE);
            assertThat(mutated.budgetAmountKrw()).isEqualTo(500_000L);
            assertThat(mutated.allocatedHoldingAmount()).isEqualTo(1000.0);
        }

        @Test
        @DisplayName("건너뛰기도 조건은 그대로다 — 남은 회차가 오늘부터 다시 생성되며 자연히 빠진다")
        void stepSkipped_KeepsGoalConditions() {
            PlanInput mutated = captureInput(
                    new ScenarioInput(ScenarioCode.STEP_SKIPPED, 2, null, null, null, null));

            assertThat(mutated.targetAmount()).isEqualTo(4000.0);
            assertThat(mutated.targetDate()).isEqualTo(TARGET_DATE);
        }

        @Test
        @DisplayName("예산 감소는 예산만 바꾼다")
        void budgetDecreased() {
            PlanInput mutated = captureInput(
                    new ScenarioInput(ScenarioCode.BUDGET_DECREASED, null, 300_000L, null, null, null));

            assertThat(mutated.budgetAmountKrw()).isEqualTo(300_000L);
            assertThat(mutated.targetAmount()).isEqualTo(4000.0);
            assertThat(mutated.targetDate()).isEqualTo(TARGET_DATE);
        }

        @Test
        @DisplayName("목표 날짜 변경은 날짜만 바꾼다")
        void targetDateChanged() {
            LocalDate newDate = LocalDate.of(2027, 3, 1);
            PlanInput mutated = captureInput(
                    new ScenarioInput(ScenarioCode.TARGET_DATE_CHANGED, null, null, newDate, null, null));

            assertThat(mutated.targetDate()).isEqualTo(newDate);
            assertThat(mutated.targetAmount()).isEqualTo(4000.0);
            assertThat(mutated.budgetAmountKrw()).isEqualTo(500_000L);
        }

        @Test
        @DisplayName("목표 금액 변경은 금액만 바꾼다")
        void targetAmountChanged() {
            PlanInput mutated = captureInput(new ScenarioInput(
                    ScenarioCode.TARGET_AMOUNT_CHANGED, null, null, null, 12_000.0, null));

            assertThat(mutated.targetAmount()).isEqualTo(12_000.0);
            assertThat(mutated.targetDate()).isEqualTo(TARGET_DATE);
        }

        @Test
        @DisplayName("보유 추가는 배정 외화에 더한다")
        void holdingAdded() {
            PlanInput mutated = captureInput(
                    new ScenarioInput(ScenarioCode.HOLDING_ADDED, null, null, null, null, 500.0));

            assertThat(mutated.allocatedHoldingAmount()).isEqualTo(1500.0);
        }

        @Test
        @DisplayName("보유 추가는 이미 실행한 금액 위에 더한다")
        void holdingAdded_OnTopOfExecuted() {
            when(planStepRepository.findByPlan_IdOrderBySeqAsc(PLAN_ID))
                    .thenReturn(List.of(completedStep(1, 700.0)));

            PlanInput mutated = captureInput(
                    new ScenarioInput(ScenarioCode.HOLDING_ADDED, null, null, null, null, 500.0));

            assertThat(mutated.allocatedHoldingAmount()).isEqualTo(2200.0);
        }
    }

    @Nested
    @DisplayName("변경 전후 비교 (명세 §16)")
    class Comparison {

        @Test
        @DisplayName("현재 계획 쪽 요약은 저장된 회차에서 되센다")
        void before_ComesFromStoredSteps() {
            when(planStepRepository.findByPlan_IdOrderBySeqAsc(PLAN_ID))
                    .thenReturn(List.of(completedStep(1, 700.0), step(2, 1100.0), step(3, 1200.0)));

            ScenarioPreview.Side before =
                    service.preview(USER_ID, PLAN_ID, input(ScenarioCode.RATE_UP)).before();

            assertThat(before.totalRounds()).isEqualTo(3);
            assertThat(before.openRounds()).isEqualTo(2);
            // 4000 - 1000(배정) - 700(실행) = 2300
            assertThat(before.remainingAmount()).isEqualByComparingTo(BigDecimal.valueOf(2300.0));
            assertThat(before.perRoundAmount()).isEqualByComparingTo(BigDecimal.valueOf(1100.0));
            assertThat(before.targetDate()).isEqualTo(TARGET_DATE);
            assertThat(before.costRange().baseKrw()).isEqualTo(4_050_000L);
        }

        @Test
        @DisplayName("미실행 회차가 없으면 회차당 금액을 지어내지 않는다")
        void before_NoOpenSteps_LeavesPerRoundNull() {
            when(planStepRepository.findByPlan_IdOrderBySeqAsc(PLAN_ID))
                    .thenReturn(List.of(completedStep(1, 3000.0)));

            ScenarioPreview.Side before =
                    service.preview(USER_ID, PLAN_ID, input(ScenarioCode.RATE_UP)).before();

            assertThat(before.perRoundAmount()).isNull();
            assertThat(before.roundBudgetKrw()).isNull();
            assertThat(before.openRounds()).isZero();
        }

        @Test
        @DisplayName("비용 요약이 없는 옛 계획은 범위를 만들어내지 않는다")
        void before_NoCostSummary_LeavesRangeNull() {
            Plan bare = Plan.builder(goal, 1).status(PlanStatus.ACTIVE).build();
            bare.setIdForTest(PLAN_ID);
            when(planRepository.findById(PLAN_ID)).thenReturn(Optional.of(bare));

            ScenarioPreview.Side before =
                    service.preview(USER_ID, PLAN_ID, input(ScenarioCode.RATE_UP)).before();

            assertThat(before.costRange()).isNull();
        }

        @Test
        @DisplayName("변경 후 요약은 재계산 결과에서 읽는다")
        void after_ComesFromRecalculation() {
            when(planCalculationService.calculate(any(), any())).thenReturn(draft(
                    TARGET_DATE, 3000.0,
                    draftStep(1, TODAY, 1500.0), draftStep(2, TODAY.plusWeeks(1), 1500.0)));

            ScenarioPreview.Side after =
                    service.preview(USER_ID, PLAN_ID, input(ScenarioCode.RATE_UP)).after();

            assertThat(after.totalRounds()).isEqualTo(2);
            assertThat(after.remainingAmount()).isEqualByComparingTo(BigDecimal.valueOf(3000.0));
            assertThat(after.perRoundAmount()).isEqualByComparingTo(BigDecimal.valueOf(1500.0));
            assertThat(after.costRange().baseKrw()).isEqualTo(3_950_000L);
        }

        @Test
        @DisplayName("회차가 없는 변경안은 회차당 금액을 비운다")
        void after_NoSteps_LeavesPerRoundNull() {
            ScenarioPreview.Side after =
                    service.preview(USER_ID, PLAN_ID, input(ScenarioCode.RATE_UP)).after();

            assertThat(after.totalRounds()).isZero();
            assertThat(after.perRoundAmount()).isNull();
        }

        @Test
        @DisplayName("경고는 그대로 전달한다 — 숨기지 않는다")
        void warnings_ArePassedThrough() {
            assertThat(service.preview(USER_ID, PLAN_ID, input(ScenarioCode.RATE_UP)).warnings())
                    .containsExactly("FORECAST_UNAVAILABLE");
        }
    }

    @Nested
    @DisplayName("달라진 회차 (명세 §16)")
    class StepDiff {

        @Test
        @DisplayName("금액이 바뀐 회차는 MODIFIED 다")
        void modifiedAmount() {
            when(planStepRepository.findByPlan_IdOrderBySeqAsc(PLAN_ID)).thenReturn(List.of(step(1, 1000.0)));
            when(planCalculationService.calculate(any(), any()))
                    .thenReturn(draft(TARGET_DATE, 3000.0, draftStep(1, TODAY, 1500.0)));

            List<ScenarioPreview.StepChange> changes =
                    service.preview(USER_ID, PLAN_ID, input(ScenarioCode.RATE_UP)).changedSteps();

            assertThat(changes).hasSize(1);
            assertThat(changes.get(0).changeType()).isEqualTo(ScenarioPreview.MODIFIED);
            assertThat(changes.get(0).amountBefore()).isEqualByComparingTo(BigDecimal.valueOf(1000.0));
            assertThat(changes.get(0).amountAfter()).isEqualByComparingTo(BigDecimal.valueOf(1500.0));
        }

        @Test
        @DisplayName("날짜만 바뀌어도 MODIFIED 다")
        void modifiedDate() {
            when(planStepRepository.findByPlan_IdOrderBySeqAsc(PLAN_ID)).thenReturn(List.of(step(1, 1000.0)));
            when(planCalculationService.calculate(any(), any()))
                    .thenReturn(draft(TARGET_DATE, 3000.0, draftStep(1, TODAY.plusDays(3), 1000.0)));

            List<ScenarioPreview.StepChange> changes =
                    service.preview(USER_ID, PLAN_ID, input(ScenarioCode.RATE_UP)).changedSteps();

            assertThat(changes).singleElement()
                    .extracting(ScenarioPreview.StepChange::changeType)
                    .isEqualTo(ScenarioPreview.MODIFIED);
        }

        @Test
        @DisplayName("같은 회차는 목록에 싣지 않는다 — 무엇이 바뀌었는지가 요점이다")
        void unchangedStepsAreOmitted() {
            when(planStepRepository.findByPlan_IdOrderBySeqAsc(PLAN_ID)).thenReturn(List.of(step(1, 1000.0)));
            when(planCalculationService.calculate(any(), any()))
                    .thenReturn(draft(TARGET_DATE, 3000.0, draftStep(1, TODAY, 1000.0)));

            assertThat(service.preview(USER_ID, PLAN_ID, input(ScenarioCode.RATE_UP)).changedSteps())
                    .isEmpty();
        }

        @Test
        @DisplayName("한쪽에만 있는 회차는 ADDED · REMOVED 로 표시한다")
        void addedAndRemoved() {
            when(planStepRepository.findByPlan_IdOrderBySeqAsc(PLAN_ID))
                    .thenReturn(List.of(step(1, 1000.0), step(2, 1000.0)));
            when(planCalculationService.calculate(any(), any())).thenReturn(draft(
                    TARGET_DATE, 3000.0,
                    draftStep(1, TODAY, 1000.0), draftStep(3, TODAY.plusWeeks(2), 900.0)));

            List<ScenarioPreview.StepChange> changes =
                    service.preview(USER_ID, PLAN_ID, input(ScenarioCode.RATE_UP)).changedSteps();

            assertThat(changes).extracting(
                            ScenarioPreview.StepChange::seq, ScenarioPreview.StepChange::changeType)
                    .containsExactly(
                            org.assertj.core.groups.Tuple.tuple(2, ScenarioPreview.REMOVED),
                            org.assertj.core.groups.Tuple.tuple(3, ScenarioPreview.ADDED));
            assertThat(changes.get(0).amountAfter()).isNull();
            assertThat(changes.get(0).dateAfter()).isNull();
            assertThat(changes.get(1).amountBefore()).isNull();
            assertThat(changes.get(1).dateBefore()).isNull();
        }
    }

    @Nested
    @DisplayName("유지·미유지 조건 (명세 §17)")
    class Constraints {

        @Test
        @DisplayName("바뀌지 않은 축은 유지 목록에 들어간다")
        void unchangedAxesAreKept() {
            when(planStepRepository.findByPlan_IdOrderBySeqAsc(PLAN_ID)).thenReturn(List.of());
            // before.remaining = 4000-1000 = 3000, after.remaining = 3000, 날짜·예산도 동일
            ScenarioPreview preview = service.preview(USER_ID, PLAN_ID, input(ScenarioCode.RATE_UP));

            assertThat(preview.keptConstraints()).containsExactlyInAnyOrder(
                    PriorityConstraint.AMOUNT, PriorityConstraint.DATE, PriorityConstraint.BUDGET);
            assertThat(preview.brokenConstraints()).isEmpty();
        }

        @Test
        @DisplayName("금액이 달라지면 미유지로 표시한다 — 숨기지 않는다 §21-8")
        void changedAmountIsBroken() {
            when(planCalculationService.calculate(any(), any())).thenReturn(draft(TARGET_DATE, 2500.0));

            ScenarioPreview preview = service.preview(USER_ID, PLAN_ID, input(ScenarioCode.RATE_UP));

            assertThat(preview.brokenConstraints()).contains(PriorityConstraint.AMOUNT);
            assertThat(preview.keptConstraints()).doesNotContain(PriorityConstraint.AMOUNT);
        }

        @Test
        @DisplayName("우선 조건이 깨졌으면 미유지 목록 맨 앞에 온다")
        void brokenPriorityComesFirst() {
            goal.setPriorityConstraint(PriorityConstraint.DATE);
            when(planCalculationService.calculate(any(), any()))
                    .thenReturn(draft(LocalDate.of(2027, 3, 1), 2500.0));

            ScenarioPreview preview = service.preview(USER_ID, PLAN_ID, input(ScenarioCode.RATE_UP));

            assertThat(preview.brokenConstraints()).first().isEqualTo(PriorityConstraint.DATE);
            assertThat(preview.priorityConstraint()).isEqualTo(PriorityConstraint.DATE);
        }

        @Test
        @DisplayName("우선 조건이 지켜졌으면 순서를 건드리지 않는다")
        void keptPriorityLeavesOrderAlone() {
            goal.setPriorityConstraint(PriorityConstraint.AMOUNT);
            when(planCalculationService.calculate(any(), any()))
                    .thenReturn(draft(LocalDate.of(2027, 3, 1), 3000.0));

            ScenarioPreview preview = service.preview(USER_ID, PLAN_ID, input(ScenarioCode.RATE_UP));

            assertThat(preview.keptConstraints()).contains(PriorityConstraint.AMOUNT);
            assertThat(preview.brokenConstraints()).containsExactly(PriorityConstraint.DATE);
        }

        @Test
        @DisplayName("한쪽 금액이 없으면 같다고 보지 않는다")
        void nullAmountIsNotEqual() {
            Plan bare = Plan.builder(goal, 1).status(PlanStatus.ACTIVE).planEndDate(TARGET_DATE).build();
            bare.setIdForTest(PLAN_ID);
            when(planRepository.findById(PLAN_ID)).thenReturn(Optional.of(bare));
            when(planCalculationService.calculate(any(), any())).thenReturn(new PlanDraft(
                    Instant.parse("2026-09-07T00:00:00Z"), "v1",
                    new PlanRateContext("USD", 1300.0, 1350.0, 1400.0, 0.01, 10_000L, 1, 2,
                            Instant.parse("2026-09-07T00:00:00Z"), null, false),
                    new PlanDraft.GoalSummary(
                            GoalType.DEADLINE, "travel", "USD", BigDecimal.valueOf(4000.0), null,
                            BigDecimal.valueOf(1000.0), null, TARGET_DATE, PriorityConstraint.AMOUNT),
                    new PlanDraft.Summary(
                            PlanStatus.DRAFT, TARGET_DATE, 0, 0, 0, 0, null,
                            new PlanDraft.CostRange(1L, 2L, 3L),
                            BudgetState.COVERED_IN_RANGE.name(), null),
                    List.of(), List.of()));

            ScenarioPreview preview = service.preview(USER_ID, PLAN_ID, input(ScenarioCode.RATE_UP));

            assertThat(preview.brokenConstraints()).contains(PriorityConstraint.AMOUNT);
        }
    }

    @Nested
    @DisplayName("조정 선택지 (명세 §15·§17)")
    class Adjustments {

        @Test
        @DisplayName("예산으로 감당되면 선택지를 내지 않는다 — 띄우면 사용자는 오작동으로 읽는다")
        void coveredBudget_NoOptions() {
            assertThat(service.preview(USER_ID, PLAN_ID, input(ScenarioCode.RATE_UP)).adjustmentOptions())
                    .isEmpty();
        }

        @Test
        @DisplayName("예산이 모자라면 네 선택지를 우선 조건 순서로 낸다")
        void shortfall_OffersOrderedOptions() {
            goal.setPriorityConstraint(PriorityConstraint.BUDGET);
            when(planCalculationService.calculate(any(), any())).thenReturn(draft(
                    TARGET_DATE, 3000.0,
                    BudgetState.CONSTRAINT_ADJUSTMENT_REQUIRED.name(), null));

            ScenarioPreview preview = service.preview(USER_ID, PLAN_ID, input(ScenarioCode.RATE_UP));

            assertThat(preview.adjustmentOptions()).containsExactly(
                    "CHANGE_TARGET_AMOUNT", "CHANGE_TARGET_DATE", "CHANGE_ROUND_BUDGET", "PAUSE_PLAN");
            assertThat(preview.budgetState())
                    .isEqualTo(BudgetState.CONSTRAINT_ADJUSTMENT_REQUIRED.name());
        }

        @Test
        @DisplayName("날짜 우선이면 목표 날짜 변경이 뒤로 간다")
        void datePriority_DemotesDateChange() {
            goal.setPriorityConstraint(PriorityConstraint.DATE);
            when(planCalculationService.calculate(any(), any())).thenReturn(draft(
                    TARGET_DATE, 3000.0,
                    BudgetState.CONSTRAINT_ADJUSTMENT_REQUIRED.name(), null));

            assertThat(service.preview(USER_ID, PLAN_ID, input(ScenarioCode.RATE_UP)).adjustmentOptions())
                    .containsExactly("CHANGE_ROUND_BUDGET", "CHANGE_TARGET_AMOUNT",
                            "CHANGE_TARGET_DATE", "PAUSE_PLAN");
        }

        @Test
        @DisplayName("우선 조건이 비어 있으면 명세 기본값인 금액 우선으로 본다")
        void unknownPriority_FallsBackToAmount() {
            goal.setPriorityConstraint(null);
            when(planCalculationService.calculate(any(), any())).thenReturn(draft(
                    TARGET_DATE, 3000.0,
                    BudgetState.CONSTRAINT_ADJUSTMENT_REQUIRED.name(), null));

            assertThat(service.preview(USER_ID, PLAN_ID, input(ScenarioCode.RATE_UP)).adjustmentOptions())
                    .containsExactly("CHANGE_ROUND_BUDGET", "CHANGE_TARGET_DATE",
                            "CHANGE_TARGET_AMOUNT", "PAUSE_PLAN");
        }
    }

    @Test
    @DisplayName("정기형 회차 예산은 양쪽 요약에 실린다")
    void recurringRoundBudgetIsCarried() {
        when(planStepRepository.findByPlan_IdOrderBySeqAsc(PLAN_ID))
                .thenReturn(new ArrayList<>(List.of(recurringStep())));
        when(planCalculationService.calculate(any(), any())).thenReturn(draft(
                TARGET_DATE, 3000.0, BudgetState.COVERED_IN_RANGE.name(), 400_000L,
                new PlanDraft.Step(1, TODAY, BigDecimal.valueOf(300.0), 400_000L,
                        new PlanDraft.CostRange(1L, 2L, 3L), null, BigDecimal.ZERO, null, null,
                        PlanStepStatus.SCHEDULED, true)));

        ScenarioPreview preview = service.preview(USER_ID, PLAN_ID, input(ScenarioCode.RATE_UP));

        assertThat(preview.before().roundBudgetKrw()).isEqualTo(500_000L);
        assertThat(preview.after().roundBudgetKrw()).isEqualTo(400_000L);
        assertThat(preview.brokenConstraints()).contains(PriorityConstraint.BUDGET);
    }

    private PlanStep recurringStep() {
        PlanStep step = step(1, 300.0);
        step.recordCostBasis(500_000L, 1350.0, 490_000L, 510_000L);
        return step;
    }

    @Test
    @DisplayName("의존이 null 이면 생성을 거부한다")
    void nullDependencies_Throw() {
        AdjustmentOptionSelector selector = new AdjustmentOptionSelector();
        assertThatThrownBy(() -> new PlanScenarioService(
                null, planStepRepository, planCalculationService, planConfirmService, selector))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new PlanScenarioService(
                planRepository, null, planCalculationService, planConfirmService, selector))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new PlanScenarioService(
                planRepository, planStepRepository, null, planConfirmService, selector))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new PlanScenarioService(
                planRepository, planStepRepository, planCalculationService, null, selector))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new PlanScenarioService(
                planRepository, planStepRepository, planCalculationService, planConfirmService, null))
                .isInstanceOf(NullPointerException.class);
    }
}
