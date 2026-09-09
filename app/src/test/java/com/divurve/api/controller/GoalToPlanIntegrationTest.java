package com.divurve.api.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.divurve.api.dto.plan.PlanRequest;
import com.divurve.api.dto.plan.PlanResponse;
import com.divurve.domain.RepositoryTestBase;
import com.divurve.domain.forecast.ForecastService;
import com.divurve.domain.fx.PerUnitFxRates;
import com.divurve.domain.goal.GoalCreateCommand;
import com.divurve.domain.goal.GoalRepository;
import com.divurve.domain.goal.GoalService;
import com.divurve.domain.goal.GoalType;
import com.divurve.domain.goal.entity.Goal;
import com.divurve.domain.holding.DepositService;
import com.divurve.domain.holding.HoldingService;
import com.divurve.domain.holding.entity.Holding;
import com.divurve.domain.master.CurrencyPairRepository;
import com.divurve.domain.master.CurrencyRepository;
import com.divurve.domain.master.MasterDataService;
import com.divurve.domain.plan.PlanAccessService;
import com.divurve.domain.plan.PlanAllocationGuard;
import com.divurve.domain.plan.PlanApplyService;
import com.divurve.domain.plan.PlanCalculationService;
import com.divurve.domain.plan.PlanConfirmService;
import com.divurve.domain.plan.PlanRateContextProvider;
import com.divurve.domain.plan.PlanRepository;
import com.divurve.domain.plan.PlanScenarioService;
import com.divurve.domain.plan.PlanStepExecutionService;
import com.divurve.domain.plan.PlanStepRepository;
import com.divurve.domain.user.UserRepository;
import com.divurve.domain.user.entity.User;
import com.divurve.engine.bucket.BucketAllocator;
import com.divurve.engine.planner.AdjustmentOptionSelector;
import com.divurve.engine.planner.BudgetFeasibilityEvaluator;
import com.divurve.engine.planner.BusinessDayCalendar;
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
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 목표 생성부터 계획 계산까지 실제 Postgres 로 관통한다 (이슈 #199).
 *
 * <p>{@code PlannerEndToEndIntegrationTest} 와 다른 점은 <b>{@link GoalService} 가 실제 객체</b>라는
 * 것이다. 그쪽은 목표를 빌더로 직접 만들고 {@code GoalService} 를 목으로 둔다 — 계획 경로만 보기
 * 때문이다. 그래서 <b>생성 경로가 값을 흘려도 잡히지 않는다.</b> 이슈 #193 이 정확히 그 사각지대에
 * 있었다: 요청의 {@code kind} 를 받고도 {@code goal_type} 을 채우지 않아 정기형 목표가 전부 마감형으로
 * 저장됐는데, 저장까지만 보는 테스트도 계획만 보는 테스트도 그것을 보지 못했다.
 *
 * <p>검증하는 것은 프론트(fx-hunters/divurve-web#111) 문서 §6 의 시나리오 1~4 다. 전부 수치 대조다.
 *
 * <p>환율과 보유 외화만 목으로 고정한다. 외부 어댑터에 의존하면 테스트가 네트워크 상태에 흔들리고,
 * 그러면 검증하려는 저장·계산 경로의 실패와 구분할 수 없다.
 */
class GoalToPlanIntegrationTest extends RepositoryTestBase {

    /** 2026-09-07 은 월요일 — 주간 회차가 매주 월요일에 떨어진다. */
    private static final LocalDate TODAY = LocalDate.of(2026, 9, 7);
    private static final Clock CLOCK =
            Clock.fixed(TODAY.atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC);

    private static final LocalDate TARGET_DATE = LocalDate.of(2026, 12, 24);
    private static final double TARGET_AMOUNT = 5000.0;
    private static final double ALLOCATED = 3000.0;

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

    private PlanController planController;
    private GoalService goalService;
    private UUID ownerId;

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

        // 보유 외화 10,000 USD — 배정 검증(§21-7)을 통과시키기 위한 최소 장치다.
        Holding holding = mock(Holding.class);
        when(holding.getCurrencyCode()).thenReturn("USD");
        when(holding.getQuantity()).thenReturn(10_000.0);
        when(holding.getAvgPrice()).thenReturn(1.0);
        HoldingService holdingService = mock(HoldingService.class);
        when(holdingService.list(any())).thenReturn(List.of(holding));
        DepositService depositService = mock(DepositService.class);
        when(depositService.list(any())).thenReturn(List.of());

        goalService = new GoalService(goalRepository, userRepository, holdingService,
                depositService, perUnitFxRates, new BucketAllocator(), CLOCK);

        PlanConfirmService confirmService =
                new PlanConfirmService(goalRepository, planRepository, planStepRepository);
        AdjustmentOptionSelector adjustmentOptionSelector = new AdjustmentOptionSelector();

        planController = new PlanController(
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
                User.createDemo("goal2plan-" + UUID.randomUUID() + "@divurve.com", "사용자"));
        ownerId = owner.getId();
        entityManager.flush();
    }

    // ── 입력 만들기 ────────────────────────────────────────────────────────

    /** 마감형 목표 생성 입력. 배정 보유 외화와 준비 주기만 바꿔 가며 쓴다. */
    private static GoalCreateCommand deadlineGoal(double allocated, String preferredCadence) {
        return new GoalCreateCommand("미국 여행 자금", GoalType.DEADLINE, "TRAVEL", "USD",
                TARGET_AMOUNT, TARGET_DATE, null, 1_000_000, "KRW", "monthly", false,
                allocated, preferredCadence, null, null, null);
    }

    /** 정기형 목표 생성 입력. 시작일과 점검 기간까지 채운다 (명세 §5.3). */
    private static GoalCreateCommand recurringGoal() {
        return new GoalCreateCommand("해외 ETF 자금", GoalType.RECURRING, "STOCK_ACCUMULATION", "USD",
                TARGET_AMOUNT, null, "monthly", 300_000, "KRW", "monthly", false,
                0.0, null, null, LocalDate.of(2026, 10, 1), 6);
    }

    /** 저장 없이 계산만 하는 미리보기 요청. 저장된 목표와 같은 조건을 담는다. */
    private static PlanRequest previewRequest(double allocated, String preferredCadence) {
        return new PlanRequest(null, GoalType.DEADLINE, "TRAVEL", "USD", allocated, TARGET_AMOUNT,
                TARGET_DATE, 1_000_000L, "monthly", preferredCadence, null, null, null, null);
    }

    private UUID saveGoal(GoalCreateCommand command) {
        Goal goal = goalService.create(ownerId, command);
        entityManager.flush();
        entityManager.clear();
        return goal.getId();
    }

    // ── 시나리오 ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("시나리오 1 — 미리보기와 확정 계획의 수치가 같다")
    void previewAndConfirmedPlanAgree() {
        PlanResponse preview = planController.preview(ownerId, previewRequest(ALLOCATED, "weekly"))
                .data();

        UUID goalId = saveGoal(deadlineGoal(ALLOCATED, "weekly"));
        PlanResponse confirmed = planController
                .createPlan(ownerId, goalId.toString(), previewRequest(ALLOCATED, "weekly"))
                .data();

        // 이 세 가지가 갈리는 것이 프론트 요청의 출발점이었다 — 배정 보유 외화가 저장되지 않아
        // 확정 계획이 남은 금액을 목표 금액 전체로 봤다(이슈 #195).
        assertThat(confirmed.goal().remainingAmount())
                .isEqualTo(preview.goal().remainingAmount());
        assertThat(confirmed.steps()).hasSameSizeAs(preview.steps());
        assertThat(confirmed.steps().stream().map(PlanResponse.Step::amount).toList())
                .isEqualTo(preview.steps().stream().map(PlanResponse.Step::amount).toList());
    }

    @Test
    @DisplayName("시나리오 1 — 저장된 목표가 배정 보유 외화를 계산에 실어 보낸다")
    void confirmedPlanUsesStoredAllocation() {
        UUID goalId = saveGoal(deadlineGoal(ALLOCATED, "weekly"));

        PlanResponse confirmed = planController
                .createPlan(ownerId, goalId.toString(), previewRequest(ALLOCATED, "weekly"))
                .data();

        // R = max(T - H, 0) = 5000 - 3000
        assertThat(confirmed.goal().remainingAmount()).isEqualTo(2000.0);
        double sum = confirmed.steps().stream().mapToDouble(PlanResponse.Step::amount).sum();
        assertThat(sum).isEqualTo(2000.0);
    }

    @Test
    @DisplayName("시나리오 2 — 목표를 이미 채웠으면 회차를 만들지 않는다")
    void targetAlreadyMetProducesNoSteps() {
        PlanResponse preview = planController
                .preview(ownerId, previewRequest(TARGET_AMOUNT, "weekly")).data();

        assertThat(preview.steps()).isEmpty();
        assertThat(preview.warnings()).contains(PlanCalculationService.WARNING_TARGET_ALREADY_MET);
        assertThat(preview.summary().status()).isEqualTo("completed");
    }

    @Test
    @DisplayName("시나리오 3 — 준비 주기를 monthly 로 저장하면 회차 간격이 한 달이다")
    void storedMonthlyCadenceDrivesRoundSpacing() {
        UUID goalId = saveGoal(deadlineGoal(ALLOCATED, "monthly"));

        PlanResponse confirmed = planController
                .createPlan(ownerId, goalId.toString(), previewRequest(ALLOCATED, "monthly"))
                .data();

        List<LocalDate> dates = confirmed.steps().stream()
                .map(PlanResponse.Step::scheduledDate)
                .toList();
        assertThat(dates).hasSizeGreaterThan(1);
        for (int i = 1; i < dates.size(); i++) {
            // preferred_cadence 가 저장되지 않으면 마감형은 기본값인 주간으로 떨어진다(이슈 #195).
            assertThat(dates.get(i - 1).plusMonths(1)).isEqualTo(dates.get(i));
            assertThat(ChronoUnit.DAYS.between(dates.get(i - 1), dates.get(i))).isGreaterThan(7L);
        }
    }

    @Test
    @DisplayName("시나리오 4 — 저장된 정기형 목표로 계획이 만들어진다")
    void storedRecurringGoalProducesPlan() {
        UUID goalId = saveGoal(recurringGoal());

        PlanResponse confirmed = planController
                .createPlan(ownerId, goalId.toString(), previewRequest(0.0, null))
                .data();

        // 유형이 저장되지 않으면 정기형이 마감형 경로로 계산된다(이슈 #193). 목표일이 없으므로
        // 그 경우 400 이 나고 이 테스트가 깨진다.
        assertThat(confirmed.planId()).isNotNull();
        assertThat(confirmed.goal().goalType()).isEqualTo(GoalType.RECURRING);
        assertThat(confirmed.steps()).isNotEmpty();
        // 정기형은 회차 예산이 고정이고 확보 외화가 범위로 나온다 (명세 §10.3).
        assertThat(confirmed.steps().get(0).budgetKrw()).isEqualTo(300_000L);
    }

    @Test
    @DisplayName("정기형 목표는 우선 조건 budget 과 반복 주기를 준비 주기로 저장한다")
    void storedRecurringGoalCarriesTypeSpecificDefaults() {
        UUID goalId = saveGoal(recurringGoal());

        Goal stored = goalRepository.findById(goalId).orElseThrow();

        assertThat(stored.getGoalType()).isEqualTo(GoalType.RECURRING);
        assertThat(stored.getPriorityConstraint()).isEqualTo("budget");
        assertThat(stored.getPreferredCadence()).isEqualTo("monthly");
        assertThat(stored.getRecurStartDate()).isEqualTo(LocalDate.of(2026, 10, 1));
        assertThat(stored.getReviewHorizonMonths()).isEqualTo(6);
    }
}
