package com.divurve.api.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.divurve.api.dto.plan.PlanRequest;
import com.divurve.api.dto.plan.PlanResponse;
import com.divurve.api.dto.plan.StepCompleteRequest;
import com.divurve.common.exception.NotFoundException;
import com.divurve.domain.RepositoryTestBase;
import com.divurve.domain.forecast.ForecastService;
import com.divurve.domain.fx.PerUnitFxRates;
import com.divurve.domain.goal.GoalRepository;
import com.divurve.domain.master.CurrencyPairRepository;
import com.divurve.domain.master.CurrencyRepository;
import com.divurve.domain.master.MasterDataService;
import com.divurve.domain.goal.GoalService;
import com.divurve.domain.goal.GoalType;
import com.divurve.domain.goal.entity.Goal;
import com.divurve.domain.plan.PlanAccessService;
import com.divurve.domain.plan.PlanAllocationGuard;
import com.divurve.domain.plan.PlanApplyService;
import com.divurve.domain.plan.PlanCalculationService;
import com.divurve.domain.plan.PlanConfirmService;
import com.divurve.domain.plan.PlanRepository;
import com.divurve.domain.plan.PlanScenarioService;
import com.divurve.domain.plan.PlanStatus;
import com.divurve.domain.plan.PlanStepExecutionService;
import com.divurve.domain.plan.PlanRateContextProvider;
import com.divurve.domain.plan.PlanStepRepository;
import com.divurve.domain.plan.PlanStepStatus;
import com.divurve.domain.user.UserRepository;
import com.divurve.domain.user.entity.User;
import com.divurve.engine.planner.BudgetFeasibilityEvaluator;
import com.divurve.engine.planner.BusinessDayCalendar;
import com.divurve.engine.planner.AdjustmentOptionSelector;
import com.divurve.engine.planner.EqualSplitAllocator;
import com.divurve.engine.planner.ExchangeCostCalculator;
import com.divurve.engine.planner.RecurringAcquisitionCalculator;
import com.divurve.engine.planner.RoundScheduleGenerator;
import com.divurve.engine.planner.SkipRedistributor;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 플래너 전체 경로를 실제 Postgres 로 훑는다 — 계산 → 저장 → 조회 → 실행 (플래너 명세 §11~§15).
 *
 * <p>단위 테스트가 잡지 못하는 것을 본다. 회차 합이 남은 외화와 맞는지는 계산기 단위로도 확인할
 * 수 있지만, <b>그 값이 DB 를 왕복하고도 유지되는지</b>는 여기서만 확인된다. 멱등 키의 유니크
 * 인덱스와 활성 계획 부분 유니크 인덱스도 마찬가지다 — 인메모리 목으로는 걸리지 않는다.
 *
 * <p>환율만 목으로 고정한다. 외부 어댑터(ECOS)에 의존하면 테스트가 네트워크 상태에 흔들리고,
 * 그러면 검증하려는 계산·저장 경로의 실패와 구분할 수 없다.
 */
class PlannerEndToEndIntegrationTest extends RepositoryTestBase {

    /** 2026-09-07 은 월요일 — 주간 회차가 매주 월요일에 떨어진다. */
    private static final LocalDate TODAY = LocalDate.of(2026, 9, 7);
    private static final Clock CLOCK =
            Clock.fixed(TODAY.atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC);

    @Autowired
    private PlanRepository planRepository;

    @Autowired
    private PlanStepRepository planStepRepository;

    @Autowired
    private GoalRepository goalRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private CurrencyRepository currencyRepository;

    @Autowired
    private CurrencyPairRepository currencyPairRepository;

    @Autowired
    private EntityManager entityManager;

    private PlanController controller;
    private UUID ownerId;
    private UUID goalId;

    @BeforeEach
    void setUp() {
        PerUnitFxRates perUnitFxRates = mock(PerUnitFxRates.class);
        when(perUnitFxRates.find("USD")).thenReturn(Optional.of(new BigDecimal("1350")));
        ForecastService forecastService = mock(ForecastService.class);
        when(forecastService.getForecast(any(), anyString(), anyInt())).thenReturn(
                new ForecastService.ForecastView(
                        "USDKRW", 30, TODAY, 1350.0, 1350.0, List.of(), List.of(), List.of(),
                        new ForecastService.IntervalView(1300.0, 1400.0, 0.07),
                        new ForecastService.VolatilityView(0.08, 0.4, "normal"),
                        new ForecastService.UserImpactView(0L, 0L),
                        new ForecastService.LabelsView("band", "path"),
                        new ForecastService.ModelInfoView(List.of(0.8), "가정", "한계"),
                        "불확실", "면책"));

        ExchangeCostCalculator exchangeCostCalculator = new ExchangeCostCalculator();
        EqualSplitAllocator equalSplitAllocator = new EqualSplitAllocator();
        // 통화 마스터는 마이그레이션 시드가 채운 실제 표를 읽는다(이슈 #111). 하드코딩 상수였을 때와
        // 같은 값이 나오는지가 이 테스트의 기대 수치로 그대로 검증된다.
        MasterDataService masterDataService =
                new MasterDataService(currencyRepository, currencyPairRepository);
        PlanCalculationService calculationService = new PlanCalculationService(
                new PlanRateContextProvider(
                        perUnitFxRates, forecastService, masterDataService, CLOCK),
                new BusinessDayCalendar(),
                new RoundScheduleGenerator(),
                equalSplitAllocator,
                exchangeCostCalculator,
                new BudgetFeasibilityEvaluator(),
                new RecurringAcquisitionCalculator(exchangeCostCalculator),
                CLOCK);
        GoalService goalService = mock(GoalService.class);
        when(goalService.getHeldAmountByCurrency(any(), anyString())).thenReturn(100_000.0);

        PlanConfirmService confirmService =
                new PlanConfirmService(goalRepository, planRepository, planStepRepository);
        AdjustmentOptionSelector adjustmentOptionSelector = new AdjustmentOptionSelector();

        controller = new PlanController(
                new PlanAccessService(goalRepository, planRepository),
                planRepository,
                planStepRepository,
                calculationService,
                confirmService,
                new PlanStepExecutionService(
                        planRepository, planStepRepository,
                        new SkipRedistributor(equalSplitAllocator), exchangeCostCalculator,
                        adjustmentOptionSelector, masterDataService, CLOCK),
                new PlanAllocationGuard(goalRepository, goalService),
                new PlanScenarioService(
                        planRepository, planStepRepository, calculationService,
                        confirmService, adjustmentOptionSelector),
                new PlanApplyService(planRepository, planStepRepository, confirmService));

        User owner = userRepository.save(
                User.createDemo("planner-" + UUID.randomUUID() + "@divurve.com", "사용자"));
        ownerId = owner.getId();
        Goal goal = goalRepository.save(Goal.builder(owner, "미국 여행 자금", "onetime", "travel", "USD")
                .goalType(GoalType.DEADLINE)
                .targetAmount(5000.0)
                .targetDate(LocalDate.of(2026, 12, 24))
                .allocatedHoldingAmount(1000.0)
                .budgetAmount(1_000_000)
                .budgetPeriod("monthly")
                .preferredCadence("weekly")
                .status("active")
                .build());
        goalId = goal.getId();
        entityManager.flush();
    }

    private PlanRequest request() {
        return new PlanRequest(
                null, GoalType.DEADLINE, "travel", "USD", 1000.0, 5000.0,
                LocalDate.of(2026, 12, 24), null, null, "weekly", null, null, null, null);
    }

    @Test
    @DisplayName("미리보기는 계획을 저장하지 않는다 — 불변조건 §21-9")
    void preview_PersistsNothing() {
        PlanResponse preview = controller.preview(ownerId, request()).data();
        entityManager.flush();

        assertThat(preview.planId()).isNull();
        assertThat(preview.steps()).isNotEmpty();
        assertThat(planRepository.findByGoal_Id(goalId)).isEmpty();
    }

    @Test
    @DisplayName("확정한 계획의 회차 합은 남은 외화와 같고 DB 왕복 후에도 유지된다 — 불변조건 §21-2")
    void createPlan_StepSumSurvivesRoundTrip() {
        PlanResponse created = controller.createPlan(ownerId, goalId.toString(), request()).data();
        entityManager.flush();
        entityManager.clear();

        PlanResponse loaded = controller.getActivePlan(ownerId, goalId.toString()).data();

        double sum = loaded.steps().stream().mapToDouble(PlanResponse.Step::amount).sum();
        assertThat(sum).isEqualTo(4000.0);
        assertThat(loaded.planId()).isEqualTo(created.planId());
        assertThat(loaded.summary().status()).isEqualTo(PlanStatus.ACTIVE);
    }

    @Test
    @DisplayName("모든 회차는 마감 버퍼를 뺀 종료일 이전이다 — 불변조건 §21-4")
    void createPlan_AllStepsBeforePlanEndDate() {
        PlanResponse created = controller.createPlan(ownerId, goalId.toString(), request()).data();
        entityManager.flush();

        // 여행 목적이므로 12/24 에서 3영업일을 뺀 12/21 이 종료일이다
        assertThat(created.summary().planEndDate()).isEqualTo(LocalDate.of(2026, 12, 21));
        assertThat(created.steps()).isNotEmpty().allSatisfy(step ->
                assertThat(step.scheduledDate()).isBeforeOrEqualTo(LocalDate.of(2026, 12, 21)));
    }

    @Test
    @DisplayName("회차 완료가 남은 금액을 줄이고, 같은 키의 재요청은 두 번 반영되지 않는다 — §21-12")
    void completeStep_IsIdempotent() {
        PlanResponse created = controller.createPlan(ownerId, goalId.toString(), request()).data();
        entityManager.flush();
        String planId = created.planId();
        StepCompleteRequest complete = new StepCompleteRequest(
                200.0, 1348.5, LocalDate.of(2026, 9, 8), "exec-key-1");

        var first = controller.completeStep(ownerId, planId, 1, complete).data();
        entityManager.flush();
        var retry = controller.completeStep(ownerId, planId, 1, complete).data();
        entityManager.flush();

        assertThat(first.alreadyApplied()).isFalse();
        assertThat(first.remainingAmount()).isEqualTo(4800.0);
        assertThat(retry.alreadyApplied()).isTrue();
        // 재요청이 남은 금액을 두 번 줄이지 않았다
        assertThat(retry.remainingAmount()).isEqualTo(4800.0);
        assertThat(retry.executedAmount()).isEqualTo(200.0);
    }

    @Test
    @DisplayName("건너뛰기는 미리보기만 낼 뿐 회차 상태를 바꾸지 않는다 — 명세 §15·§21-9")
    void skipStep_DoesNotMutate() {
        PlanResponse created = controller.createPlan(ownerId, goalId.toString(), request()).data();
        entityManager.flush();
        String planId = created.planId();

        var preview = controller.skipStep(ownerId, planId, 1).data();
        entityManager.flush();
        entityManager.clear();

        assertThat(preview.applied()).isFalse();
        assertThat(preview.amountAfter()).isGreaterThan(preview.amountBefore());
        assertThat(planStepRepository.findByPlan_IdAndSeq(UUID.fromString(planId), 1))
                .get()
                .satisfies(step -> assertThat(step.getStatus())
                        .isEqualTo(PlanStepStatus.SCHEDULED));
    }

    @Test
    @DisplayName("새 버전을 적용하면 이전 계획은 superseded 로 내려가고 회차는 보존된다 — §21-10·11")
    void createPlan_Twice_SupersedesAndPreserves() {
        PlanResponse first = controller.createPlan(ownerId, goalId.toString(), request()).data();
        entityManager.flush();
        controller.completeStep(ownerId, first.planId(), 1,
                new StepCompleteRequest(200.0, 1350.0, TODAY, "k1"));
        entityManager.flush();

        PlanResponse second = controller.createPlan(ownerId, goalId.toString(), request()).data();
        entityManager.flush();
        entityManager.clear();

        assertThat(second.version()).isEqualTo(2);
        PlanResponse previous = controller.getPlan(ownerId, first.planId()).data();
        assertThat(previous.summary().status()).isEqualTo(PlanStatus.SUPERSEDED);
        // 완료 기록은 이전 버전에 그대로 남는다
        assertThat(previous.steps()).anySatisfy(step -> {
            assertThat(step.status()).isEqualTo(PlanStepStatus.COMPLETED);
            assertThat(step.executedAmount()).isEqualTo(200.0);
        });
        assertThat(controller.listPlanVersions(ownerId, goalId.toString()).data().versions())
                .hasSize(2);
    }

    @Test
    @DisplayName("남의 계획은 존재 자체를 숨긴다 — 이슈 #50 회귀 방지")
    void otherOwner_GetsNotFound() {
        PlanResponse created = controller.createPlan(ownerId, goalId.toString(), request()).data();
        entityManager.flush();
        UUID stranger = userRepository.save(
                User.createDemo("stranger-" + UUID.randomUUID() + "@divurve.com", "타인")).getId();
        String planId = created.planId();
        String goalIdText = goalId.toString();

        assertThatThrownBy(() -> controller.getPlan(stranger, planId))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> controller.getActivePlan(stranger, goalIdText))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    @DisplayName("활성 계획이 없으면 404 다 — 가짜 Curve 를 만들지 않는다 (명세 §20)")
    void noActivePlan_Returns404() {
        String goalIdText = goalId.toString();

        assertThatThrownBy(() -> controller.getActivePlan(ownerId, goalIdText))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("계획을 먼저 만들어");
    }
}
