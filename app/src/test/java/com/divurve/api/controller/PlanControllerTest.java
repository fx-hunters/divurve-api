package com.divurve.api.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.divurve.api.dto.plan.PlanRequest;
import com.divurve.api.dto.plan.PlanResponse;
import com.divurve.api.dto.plan.PlanVersionListResponse;
import com.divurve.api.dto.plan.ScenarioPreviewRequest;
import com.divurve.api.dto.plan.ScenarioPreviewResponse;
import com.divurve.api.dto.plan.StepCompleteRequest;
import com.divurve.api.dto.plan.StepCompleteResponse;
import com.divurve.api.dto.plan.StepSkipResponse;
import com.divurve.common.exception.NotFoundException;
import com.divurve.common.response.ApiResponse;
import com.divurve.domain.goal.GoalType;
import com.divurve.domain.goal.entity.Goal;
import com.divurve.domain.plan.PlanAccessService;
import com.divurve.domain.plan.PlanAllocationGuard;
import com.divurve.domain.plan.PlanApplyService;
import com.divurve.domain.plan.PlanCalculationService;
import com.divurve.domain.plan.PlanConfirmService;
import com.divurve.domain.plan.PlanDraft;
import com.divurve.domain.plan.PlanRateContext;
import com.divurve.domain.plan.PlanRepository;
import com.divurve.domain.plan.PlanScenarioService;
import com.divurve.domain.plan.ScenarioPreview;
import com.divurve.domain.plan.PlanStatus;
import com.divurve.domain.plan.PlanStepExecutionService;
import com.divurve.domain.plan.PlanStepRepository;
import com.divurve.domain.plan.PlanStepStatus;
import com.divurve.domain.plan.entity.Plan;
import com.divurve.domain.plan.entity.PlanCalculationMeta;
import com.divurve.domain.plan.entity.PlanCostSummary;
import com.divurve.domain.plan.entity.PlanStep;
import com.divurve.domain.user.entity.User;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link PlanController} — 계획 엔드포인트 (플래너 명세 §11·§12).
 *
 * <p>가장 중요한 검증은 <b>미리보기가 아무것도 저장하지 않는다</b>는 것이다 (§21-9).
 * 그 성질이 깨지면 사용자가 "이 조건이면 어떻게 되지?"를 눌러본 것만으로 활성 계획이 바뀐다.
 */
@DisplayName("PlanController")
class PlanControllerTest {

    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID GOAL_ID = UUID.randomUUID();
    private static final UUID PLAN_ID = UUID.randomUUID();
    private static final Instant AS_OF = Instant.parse("2026-09-07T00:00:00Z");

    private PlanAccessService planAccessService;
    private PlanRepository planRepository;
    private PlanStepRepository planStepRepository;
    private PlanCalculationService planCalculationService;
    private PlanConfirmService planConfirmService;
    private PlanStepExecutionService planStepExecutionService;
    private PlanAllocationGuard planAllocationGuard;
    private PlanScenarioService planScenarioService;
    private PlanApplyService planApplyService;
    private PlanController controller;
    private Goal goal;

    @BeforeEach
    void setUp() {
        planAccessService = mock(PlanAccessService.class);
        planRepository = mock(PlanRepository.class);
        planStepRepository = mock(PlanStepRepository.class);
        planCalculationService = mock(PlanCalculationService.class);
        planConfirmService = mock(PlanConfirmService.class);
        planStepExecutionService = mock(PlanStepExecutionService.class);
        planAllocationGuard = mock(PlanAllocationGuard.class);
        planScenarioService = mock(PlanScenarioService.class);
        planApplyService = mock(PlanApplyService.class);
        controller = new PlanController(
                planAccessService, planRepository, planStepRepository,
                planCalculationService, planConfirmService, planStepExecutionService,
                planAllocationGuard, planScenarioService, planApplyService);

        goal = Goal.builder(User.createDemo("a@b.com", "사용자"), "여행 자금", "onetime", "travel", "USD")
                .targetAmount(4000.0)
                .allocatedHoldingAmount(1000.0)
                .goalType(GoalType.DEADLINE)
                .targetDate(LocalDate.of(2026, 12, 24))
                .preferredCadence("weekly")
                .build();
        goal.setIdForTest(GOAL_ID);
    }

    private PlanRequest deadlineRequest(String goalId) {
        return new PlanRequest(
                goalId, GoalType.DEADLINE, "travel", "USD", 1000.0, 4000.0,
                LocalDate.of(2026, 12, 24), null, null, "weekly", null, null, null, null);
    }

    private PlanDraft draft() {
        PlanRateContext rates = new PlanRateContext(
                "USD", 1300.0, 1350.0, 1400.0, 0.0175, 3000L, 1, 2, AS_OF, AS_OF, true);
        return new PlanDraft(
                AS_OF, "plan-2026.09.1-equal-split", rates,
                new PlanDraft.GoalSummary(
                        GoalType.DEADLINE, "travel", "USD",
                        new BigDecimal("4000.00"), null, new BigDecimal("1000.00"),
                        new BigDecimal("3000.00"), LocalDate.of(2026, 12, 24), "amount"),
                new PlanDraft.Summary(
                        PlanStatus.DRAFT, LocalDate.of(2026, 12, 21), 1, 0, 1, 0, 1,
                        new PlanDraft.CostRange(3_900_000L, 4_050_000L, 4_200_000L),
                        "RANGE_SENSITIVE", null),
                List.of(new PlanDraft.Step(
                        1, LocalDate.of(2026, 9, 7), new BigDecimal("3000.00"), null,
                        new PlanDraft.CostRange(3_900_000L, 4_050_000L, 4_200_000L), null,
                        BigDecimal.ZERO, null, null, PlanStepStatus.SCHEDULED, true)),
                List.of());
    }

    private Plan storedPlan() {
        Plan plan = Plan.builder(goal, 2)
                .status(PlanStatus.ACTIVE)
                .planEndDate(LocalDate.of(2026, 12, 21))
                .calculationMeta(PlanCalculationMeta.builder("plan-2026.09.1-equal-split")
                        .rateAsOf(AS_OF).forecastAsOf(AS_OF)
                        .rates(1300.0, 1350.0, 1400.0)
                        .spreadRatio(0.0175).feeKrw(3000L).quoteUnit(1)
                        .build())
                .costSummary(PlanCostSummary.of(
                        "RANGE_SENSITIVE", 3_900_000L, 4_050_000L, 4_200_000L))
                .build();
        plan.setIdForTest(PLAN_ID);
        return plan;
    }

    private PlanStep storedStep(Plan plan, int seq, String status) {
        PlanStep step = PlanStep.create(
                plan, seq, LocalDate.of(2026, 9, 7).plusWeeks(seq - 1L), 1500.0, 0.0, status);
        step.recordCostBasis(null, 1350.0, 1_950_000L, 2_100_000L);
        return step;
    }

    @Test
    @DisplayName("미리보기는 아무것도 저장하지 않는다 — 불변조건 §21-9")
    void preview_SavesNothing() {
        when(planCalculationService.calculate(eq(USER_ID), any())).thenReturn(draft());

        ApiResponse<PlanResponse> response = controller.preview(USER_ID, deadlineRequest(null));

        assertThat(response.data().planId()).isNull();
        assertThat(response.data().version()).isNull();
        verifyNoInteractions(planConfirmService, planRepository, planStepRepository);
    }

    @Test
    @DisplayName("미리보기는 목표 저장 전에도 동작한다 — 명세 §12 장면 3·4")
    void preview_WorksWithoutSavedGoal() {
        when(planCalculationService.calculate(eq(USER_ID), any())).thenReturn(draft());

        ApiResponse<PlanResponse> response = controller.preview(USER_ID, deadlineRequest(null));

        assertThat(response.data().steps()).hasSize(1);
        assertThat(response.data().goal().remainingAmount()).isEqualTo(3000.0);
        verifyNoInteractions(planAccessService);
    }

    @Test
    @DisplayName("goal_id 를 주면 저장된 목표의 조건을 쓴다")
    void preview_WithGoalId_UsesStoredGoal() {
        when(planAccessService.requireGoalOwner(USER_ID, GOAL_ID)).thenReturn(goal);
        when(planCalculationService.calculate(eq(USER_ID), any())).thenReturn(draft());

        controller.preview(USER_ID, deadlineRequest(GOAL_ID.toString()));

        verify(planAccessService).requireGoalOwner(USER_ID, GOAL_ID);
    }

    @Test
    @DisplayName("미리보기도 보유 외화 중복 배정을 막는다 — 불변조건 §21-7")
    void preview_ChecksAllocation() {
        when(planCalculationService.calculate(eq(USER_ID), any())).thenReturn(draft());

        controller.preview(USER_ID, deadlineRequest(null));

        verify(planAllocationGuard).requireAllocatable(USER_ID, "USD", 1000.0, null);
    }

    @Test
    @DisplayName("응답에는 계산 전제와 면책이 함께 실린다 — 명세 §11.1·§2")
    void preview_CarriesMetaAndDisclaimer() {
        when(planCalculationService.calculate(eq(USER_ID), any())).thenReturn(draft());

        PlanResponse data = controller.preview(USER_ID, deadlineRequest(null)).data();

        assertThat(data.calculationMeta().policyVersion()).isEqualTo("plan-2026.09.1-equal-split");
        assertThat(data.calculationMeta().rates().base()).isEqualTo(1350.0);
        assertThat(data.calculationMeta().rateAsOf()).isEqualTo(AS_OF);
        assertThat(data.disclaimer()).isEqualTo(PlanResponse.DISCLAIMER);
    }

    @Test
    @DisplayName("계획 확정은 저장된 목표 조건으로 계산해 저장한다")
    void createPlan_SavesCalculatedPlan() {
        Plan saved = storedPlan();
        when(planAccessService.requireGoalOwner(USER_ID, GOAL_ID)).thenReturn(goal);
        when(planCalculationService.calculate(eq(USER_ID), any())).thenReturn(draft());
        when(planConfirmService.confirm(eq(GOAL_ID), any(), any())).thenReturn(saved);
        when(planStepRepository.findByPlan_IdOrderBySeqAsc(PLAN_ID))
                .thenReturn(List.of(storedStep(saved, 1, PlanStepStatus.SCHEDULED)));

        PlanResponse data = controller.createPlan(
                USER_ID, GOAL_ID.toString(), deadlineRequest(null)).data();

        assertThat(data.planId()).isEqualTo(PLAN_ID.toString());
        assertThat(data.version()).isEqualTo(2);
        assertThat(data.summary().status()).isEqualTo(PlanStatus.ACTIVE);
        verify(planConfirmService).confirm(eq(GOAL_ID), any(), any());
    }

    @Test
    @DisplayName("정기형 요청은 회차 예산과 반복 주기를 계산 입력으로 넘긴다")
    void preview_RecurringRequest_MapsRecurringFields() {
        when(planCalculationService.calculate(eq(USER_ID), any())).thenReturn(draft());
        PlanRequest request = new PlanRequest(
                null, GoalType.RECURRING, "investment", "USD", 0.0, 0.0, null,
                null, null, null, 500_000L, "monthly", LocalDate.of(2026, 10, 1), 6);

        controller.preview(USER_ID, request);

        org.mockito.ArgumentCaptor<com.divurve.domain.plan.PlanInput> captor =
                org.mockito.ArgumentCaptor.forClass(com.divurve.domain.plan.PlanInput.class);
        verify(planCalculationService).calculate(eq(USER_ID), captor.capture());
        assertThat(captor.getValue().budgetAmountKrw()).isEqualTo(500_000L);
        assertThat(captor.getValue().cadence()).isEqualTo("monthly");
        assertThat(captor.getValue().isRecurring()).isTrue();
    }

    @Test
    @DisplayName("goal_id 가 공백이면 없는 것으로 본다")
    void preview_BlankGoalId_IsTreatedAsAbsent() {
        when(planCalculationService.calculate(eq(USER_ID), any())).thenReturn(draft());

        controller.preview(USER_ID, deadlineRequest("  "));

        verify(planAllocationGuard).requireAllocatable(USER_ID, "USD", 1000.0, null);
        verifyNoInteractions(planAccessService);
    }

    @Test
    @DisplayName("goal_id 를 함께 보낸 확정 요청은 재계산 사유를 남긴다")
    void createPlan_WithGoalId_RecordsReason() {
        Plan saved = storedPlan();
        when(planAccessService.requireGoalOwner(USER_ID, GOAL_ID)).thenReturn(goal);
        when(planCalculationService.calculate(eq(USER_ID), any())).thenReturn(draft());
        when(planConfirmService.confirm(eq(GOAL_ID), any(), any())).thenReturn(saved);
        when(planStepRepository.findByPlan_IdOrderBySeqAsc(PLAN_ID)).thenReturn(List.of());

        controller.createPlan(USER_ID, GOAL_ID.toString(), deadlineRequest(GOAL_ID.toString()));

        verify(planConfirmService).confirm(eq(GOAL_ID), any(), eq("재계산"));
    }

    @Test
    @DisplayName("정기형 목표의 저장된 계획은 회차 예산을 함께 낸다 — 명세 §11.2")
    void getActivePlan_RecurringGoal_CarriesRoundBudget() {
        Goal recurringGoal = Goal.builder(
                        User.createDemo("a@b.com", "사용자"), "ETF 자금", "onetime", "investment", "USD")
                .goalType(GoalType.RECURRING)
                .budgetAmount(500_000)
                .build();
        recurringGoal.setIdForTest(GOAL_ID);
        Plan active = Plan.builder(recurringGoal, 1).status(PlanStatus.ACTIVE).build();
        active.setIdForTest(PLAN_ID);
        when(planAccessService.requireGoalOwner(USER_ID, GOAL_ID)).thenReturn(recurringGoal);
        when(planRepository.findFirstByGoal_IdAndStatus(GOAL_ID, PlanStatus.ACTIVE))
                .thenReturn(Optional.of(active));
        when(planStepRepository.findByPlan_IdOrderBySeqAsc(PLAN_ID)).thenReturn(List.of());

        PlanResponse data = controller.getActivePlan(USER_ID, GOAL_ID.toString()).data();

        assertThat(data.goal().roundBudgetKrw()).isEqualTo(500_000L);
        // V13 이전 계획처럼 계산 메타가 없으면 값을 지어내지 않고 비운다
        assertThat(data.calculationMeta()).isNull();
    }

    @Test
    @DisplayName("활성 계획을 조회한다")
    void getActivePlan_ReturnsActive() {
        Plan active = storedPlan();
        when(planAccessService.requireGoalOwner(USER_ID, GOAL_ID)).thenReturn(goal);
        when(planRepository.findFirstByGoal_IdAndStatus(GOAL_ID, PlanStatus.ACTIVE))
                .thenReturn(Optional.of(active));
        when(planStepRepository.findByPlan_IdOrderBySeqAsc(PLAN_ID)).thenReturn(List.of(
                storedStep(active, 1, PlanStepStatus.COMPLETED),
                storedStep(active, 2, PlanStepStatus.SCHEDULED)));

        PlanResponse data = controller.getActivePlan(USER_ID, GOAL_ID.toString()).data();

        assertThat(data.summary().totalRounds()).isEqualTo(2);
        assertThat(data.summary().completedRounds()).isEqualTo(1);
        assertThat(data.summary().scheduledRounds()).isEqualTo(1);
        assertThat(data.summary().nextActionSeq()).isEqualTo(2);
    }

    @Test
    @DisplayName("활성 계획이 없으면 404 다 — 가짜 Curve 를 만들지 않는다 (명세 §20)")
    void getActivePlan_NotFound() {
        when(planAccessService.requireGoalOwner(USER_ID, GOAL_ID)).thenReturn(goal);
        when(planRepository.findFirstByGoal_IdAndStatus(GOAL_ID, PlanStatus.ACTIVE))
                .thenReturn(Optional.empty());
        String goalId = GOAL_ID.toString();

        assertThatThrownBy(() -> controller.getActivePlan(USER_ID, goalId))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("계획을 먼저 만들어");
    }

    @Test
    @DisplayName("버전 이력은 최신순이다 — 명세 §18")
    void listPlanVersions_NewestFirst() {
        Plan second = storedPlan();
        Plan first = Plan.builder(goal, 1).status(PlanStatus.SUPERSEDED).reason("최초").build();
        first.setIdForTest(UUID.randomUUID());
        first.supersededBy(PLAN_ID);
        when(planAccessService.requireGoalOwner(USER_ID, GOAL_ID)).thenReturn(goal);
        when(planRepository.findByGoal_IdOrderByVersionDesc(GOAL_ID))
                .thenReturn(List.of(second, first));

        PlanVersionListResponse data =
                controller.listPlanVersions(USER_ID, GOAL_ID.toString()).data();

        assertThat(data.versions()).extracting(PlanVersionListResponse.Version::version)
                .containsExactly(2, 1);
        assertThat(data.versions().get(1).supersededBy()).isEqualTo(PLAN_ID.toString());
    }

    @Test
    @DisplayName("특정 버전 상세를 조회한다 — 명세 §21-11")
    void getPlan_ReturnsVersionDetail() {
        Plan plan = storedPlan();
        when(planAccessService.requirePlanOwner(USER_ID, PLAN_ID)).thenReturn(plan);
        when(planStepRepository.findByPlan_IdOrderBySeqAsc(PLAN_ID))
                .thenReturn(List.of(storedStep(plan, 1, PlanStepStatus.COMPLETED)));

        PlanResponse data = controller.getPlan(USER_ID, PLAN_ID.toString()).data();

        assertThat(data.planId()).isEqualTo(PLAN_ID.toString());
        assertThat(data.steps()).hasSize(1);
    }

    @Test
    @DisplayName("회차 완료는 실행 정보와 멱등 키를 그대로 넘긴다 — 명세 §14")
    void completeStep_PassesExecutionDetails() {
        Plan plan = storedPlan();
        when(planAccessService.requirePlanOwner(USER_ID, PLAN_ID)).thenReturn(plan);
        when(planStepExecutionService.completeStep(
                any(), anyInt(), anyDouble(), anyDouble(), any(), any(), anyString()))
                .thenReturn(new PlanStepExecutionService.CompleteResult(
                        1, PlanStepStatus.COMPLETED, 1000.0, 1348.5,
                        LocalDate.of(2026, 9, 8), 3000.0, 2, false));

        StepCompleteResponse data = controller.completeStep(
                USER_ID, PLAN_ID.toString(), 1,
                new StepCompleteRequest(1000.0, 1348.5, LocalDate.of(2026, 9, 8), "key-1")).data();

        assertThat(data.remainingAmount()).isEqualTo(3000.0);
        assertThat(data.nextActionSeq()).isEqualTo(2);
        assertThat(data.alreadyApplied()).isFalse();
        verify(planStepExecutionService).completeStep(
                PLAN_ID, 1, 4000.0, 1000.0, 1348.5, LocalDate.of(2026, 9, 8), "key-1");
    }

    @Test
    @DisplayName("건너뛰기는 미리보기만 반환하고 계획을 바꾸지 않는다 — 명세 §15·§21-9")
    void skipStep_ReturnsPreviewOnly() {
        Plan plan = storedPlan();
        when(planAccessService.requirePlanOwner(USER_ID, PLAN_ID)).thenReturn(plan);
        when(planStepExecutionService.previewSkip(any(), anyInt(), any()))
                .thenReturn(new PlanStepExecutionService.SkipPreview(
                        2, 1000.0, 1500.0, 3000.0, 2, false, 2_100_000L, false, List.of()));

        StepSkipResponse data = controller.skipStep(USER_ID, PLAN_ID.toString(), 2).data();

        assertThat(data.applied()).isFalse();
        assertThat(data.amountBefore()).isEqualTo(1000.0);
        assertThat(data.amountAfter()).isEqualTo(1500.0);
        assertThat(data.adjustmentOptions()).isEmpty();
        verify(planRepository, never()).save(any());
    }

    @Test
    @DisplayName("재분배할 회차가 없으면 조정 선택지를 함께 낸다 — 명세 §15")
    void skipStep_Exhausted_OffersAdjustments() {
        Plan plan = storedPlan();
        when(planAccessService.requirePlanOwner(USER_ID, PLAN_ID)).thenReturn(plan);
        when(planStepExecutionService.previewSkip(any(), anyInt(), any()))
                .thenReturn(new PlanStepExecutionService.SkipPreview(
                        2, 1000.0, 0.0, 3000.0, 0, true, null, false,
                        List.of("CHANGE_ROUND_BUDGET", "CHANGE_TARGET_AMOUNT",
                                "CHANGE_TARGET_DATE", "PAUSE_PLAN")));

        StepSkipResponse data = controller.skipStep(USER_ID, PLAN_ID.toString(), 2).data();

        assertThat(data.adjustmentOptions()).containsExactly(
                "CHANGE_ROUND_BUDGET", "CHANGE_TARGET_AMOUNT", "CHANGE_TARGET_DATE", "PAUSE_PLAN");
    }

    @Test
    @DisplayName("모든 엔드포인트가 소유자를 먼저 검증한다 — 이슈 #50 회귀 방지")
    void allEndpointsVerifyOwner() {
        when(planAccessService.requireGoalOwner(USER_ID, GOAL_ID))
                .thenThrow(new NotFoundException("목표를 찾을 수 없습니다."));
        when(planAccessService.requirePlanOwner(USER_ID, PLAN_ID))
                .thenThrow(new NotFoundException("계획을 찾을 수 없습니다."));
        String goalId = GOAL_ID.toString();
        String planId = PLAN_ID.toString();
        PlanRequest request = deadlineRequest(null);
        StepCompleteRequest completeRequest = new StepCompleteRequest(1.0, null, null, null);

        assertThatThrownBy(() -> controller.createPlan(USER_ID, goalId, request))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> controller.listPlanVersions(USER_ID, goalId))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> controller.getActivePlan(USER_ID, goalId))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> controller.getPlan(USER_ID, planId))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> controller.completeStep(USER_ID, planId, 1, completeRequest))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> controller.skipStep(USER_ID, planId, 1))
                .isInstanceOf(NotFoundException.class);

        verifyNoInteractions(planConfirmService, planStepExecutionService);
    }

    @Test
    @DisplayName("시나리오 미리보기는 소유자 검증 후 도메인 결과를 그대로 옮긴다 — 명세 §16")
    void previewScenario_MapsDomainResult() {
        when(planAccessService.requirePlanOwner(USER_ID, PLAN_ID)).thenReturn(storedPlan());
        UUID draftId = UUID.randomUUID();
        when(planScenarioService.preview(eq(USER_ID), eq(PLAN_ID), any())).thenReturn(
                new ScenarioPreview(
                        PLAN_ID, 2, draftId, 3, "TARGET_DATE_CHANGED", "amount",
                        new ScenarioPreview.Side(
                                new java.math.BigDecimal("3000"), LocalDate.of(2026, 12, 24), 3, 3,
                                new java.math.BigDecimal("1000"), null,
                                new PlanDraft.CostRange(1L, 2L, 3L)),
                        new ScenarioPreview.Side(
                                new java.math.BigDecimal("3000"), LocalDate.of(2027, 3, 1), 5, 5,
                                new java.math.BigDecimal("600"), null, null),
                        List.of(new ScenarioPreview.StepChange(
                                2, ScenarioPreview.MODIFIED, LocalDate.of(2026, 9, 14),
                                LocalDate.of(2026, 9, 21),
                                new java.math.BigDecimal("1000"), new java.math.BigDecimal("600"))),
                        List.of("amount"), List.of("date"),
                        "COVERED_IN_RANGE", List.of(), List.of("FORECAST_UNAVAILABLE")));

        ScenarioPreviewResponse data = controller.previewScenario(
                USER_ID, PLAN_ID.toString(),
                new ScenarioPreviewRequest("TARGET_DATE_CHANGED", null, null,
                        LocalDate.of(2027, 3, 1), null, null)).data();

        assertThat(data.basePlanId()).isEqualTo(PLAN_ID.toString());
        assertThat(data.draftPlanId()).isEqualTo(draftId.toString());
        assertThat(data.draftVersion()).isEqualTo(3);
        assertThat(data.changeReasonCode()).isEqualTo("TARGET_DATE_CHANGED");
        assertThat(data.keptConstraints()).containsExactly("amount");
        assertThat(data.brokenConstraints()).containsExactly("date");
        assertThat(data.changedSteps()).singleElement()
                .extracting(ScenarioPreviewResponse.StepChangeResponse::changeType)
                .isEqualTo(ScenarioPreview.MODIFIED);
        assertThat(data.before().costRange().baseKrw()).isEqualTo(2L);
        // 비용 요약이 없는 쪽은 범위를 지어내지 않는다.
        assertThat(data.after().costRange()).isNull();
        assertThat(data.warnings()).containsExactly("FORECAST_UNAVAILABLE");
        verify(planAccessService).requirePlanOwner(USER_ID, PLAN_ID);
    }

    @Test
    @DisplayName("적용은 소유자 검증 후 승격된 계획을 반환한다 — 명세 §18")
    void applyPlan_ReturnsPromotedPlan() {
        Plan applied = storedPlan();
        when(planAccessService.requirePlanOwner(USER_ID, PLAN_ID)).thenReturn(applied);
        when(planApplyService.apply(PLAN_ID)).thenReturn(applied);
        when(planStepRepository.findByPlan_IdOrderBySeqAsc(applied.getId())).thenReturn(List.of());

        PlanResponse data = controller.applyPlan(USER_ID, PLAN_ID.toString()).data();

        assertThat(data).isNotNull();
        verify(planAccessService).requirePlanOwner(USER_ID, PLAN_ID);
        verify(planApplyService).apply(PLAN_ID);
    }

    @Test
    @DisplayName("의존이 null 이면 생성을 거부한다")
    void nullDependencies_Throw() {
        assertThatThrownBy(() -> new PlanController(
                null, planRepository, planStepRepository, planCalculationService,
                planConfirmService, planStepExecutionService, planAllocationGuard,
                planScenarioService, planApplyService))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new PlanController(
                planAccessService, null, planStepRepository, planCalculationService,
                planConfirmService, planStepExecutionService, planAllocationGuard,
                planScenarioService, planApplyService))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new PlanController(
                planAccessService, planRepository, null, planCalculationService,
                planConfirmService, planStepExecutionService, planAllocationGuard,
                planScenarioService, planApplyService))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new PlanController(
                planAccessService, planRepository, planStepRepository, null,
                planConfirmService, planStepExecutionService, planAllocationGuard,
                planScenarioService, planApplyService))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new PlanController(
                planAccessService, planRepository, planStepRepository, planCalculationService,
                null, planStepExecutionService, planAllocationGuard,
                planScenarioService, planApplyService))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new PlanController(
                planAccessService, planRepository, planStepRepository, planCalculationService,
                planConfirmService, null, planAllocationGuard,
                planScenarioService, planApplyService))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new PlanController(
                planAccessService, planRepository, planStepRepository, planCalculationService,
                planConfirmService, planStepExecutionService, null,
                planScenarioService, planApplyService))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new PlanController(
                planAccessService, planRepository, planStepRepository, planCalculationService,
                planConfirmService, planStepExecutionService, planAllocationGuard, null, planApplyService))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new PlanController(
                planAccessService, planRepository, planStepRepository, planCalculationService,
                planConfirmService, planStepExecutionService, planAllocationGuard, planScenarioService, null))
                .isInstanceOf(NullPointerException.class);
    }
}
