package com.divurve.domain.goal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.divurve.common.exception.InvalidRequestException;
import com.divurve.common.exception.NotFoundException;
import com.divurve.domain.fx.PerUnitFxRates;
import com.divurve.domain.goal.entity.Goal;
import com.divurve.domain.holding.DepositService;
import com.divurve.domain.holding.HoldingService;
import com.divurve.domain.holding.entity.Deposit;
import com.divurve.domain.holding.entity.Holding;
import com.divurve.domain.user.UserRepository;
import com.divurve.domain.user.entity.User;
import com.divurve.engine.bucket.BucketAllocator;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("GoalService 테스트")
class GoalServiceTest {

    /** 검증 기준 "오늘" — target_date 과거 여부 판정을 결정적으로 만든다. */
    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2026-09-07T00:00:00Z"), ZoneOffset.UTC);

    @Mock
    private GoalRepository goalRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private HoldingService holdingService;

    @Mock
    private DepositService depositService;

    @Mock
    private PerUnitFxRates perUnitFxRates;

    @Mock
    private User owner;

    /** 순수 계산기라 목업 없이 실제 인스턴스를 쓴다 — 목적 코드 판정도 실제 규칙 그대로 검증된다. */
    private final BucketAllocator bucketAllocator = new BucketAllocator();

    private GoalService goalService;
    private UUID ownerId;

    @BeforeEach
    void setUp() {
        goalService = new GoalService(goalRepository, userRepository, holdingService, depositService,
                perUnitFxRates, bucketAllocator, FIXED_CLOCK);
        ownerId = UUID.randomUUID();
    }

    /**
     * 플래너 필드를 비운 생성 입력. 그 다섯 값은 전부 선택이라 비우면 서버가 기본값을 정한다
     * — 기존 케이스는 그 기본값 경로를 그대로 쓴다.
     */
    private static GoalCreateCommand command(String name, String kind, String purpose,
            String currencyCode, double targetAmount, LocalDate targetDate, String recurInterval,
            long budgetAmount, String budgetCurrencyCode, String budgetPeriod, boolean isSpeculative) {
        return new GoalCreateCommand(name, kind, purpose, currencyCode, targetAmount, targetDate,
                recurInterval, budgetAmount, budgetCurrencyCode, budgetPeriod, isSpeculative,
                0.0, null, null, null, null);
    }

    /** 지원 통화(예 USD)의 정상 요청 흐름에서 공통으로 필요한 스텁. */
    private void givenSupportedCurrency(String currencyCode) {
        when(perUnitFxRates.find(currencyCode)).thenReturn(Optional.of(BigDecimal.ONE));
    }

    @Test
    @DisplayName("목표 생성 성공")
    void createGoalSuccess() {
        givenSupportedCurrency("USD");
        when(userRepository.findById(ownerId)).thenReturn(Optional.of(owner));
        when(goalRepository.save(any(Goal.class))).thenAnswer(invocation -> {
            Goal goal = invocation.getArgument(0);
            return goal;
        });

        Goal result = goalService.create(ownerId, command(
                "USD 목표",
                "deadline",
                "TRAVEL",
                "USD",
                10000.0,
                LocalDate.of(2026, 12, 31),
                null,
                0,
                "KRW",
                null,
                false));

        assertThat(result).isNotNull();
        assertThat(result.getName()).isEqualTo("USD 목표");
        assertThat(result.getKind()).isEqualTo("deadline");
        assertThat(result.getGoalType()).isEqualTo(GoalType.DEADLINE);
        assertThat(result.getCurrencyCode()).isEqualTo("USD");
        assertThat(result.getStatus()).isEqualTo("active");
    }

    @Test
    @DisplayName("정기형으로 만든 목표는 goal_type 이 recurring 이다 (이슈 #193)")
    void createRecurringGoalPersistsGoalType() {
        givenSupportedCurrency("USD");
        when(userRepository.findById(ownerId)).thenReturn(Optional.of(owner));
        when(goalRepository.save(any(Goal.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Goal result = goalService.create(ownerId, new GoalCreateCommand(
                "ETF 적립", "recurring", "STOCK_ACCUMULATION", "USD", 10000.0, null,
                "monthly", 300000, "KRW", "monthly", false,
                0.0, null, null, LocalDate.of(2026, 10, 1), 6));

        // goal_type 이 kind 를 따라가지 않으면 PlanInput.from(goal) 이 정기형을 마감형으로 넘긴다.
        assertThat(result.getGoalType()).isEqualTo(GoalType.RECURRING);
        assertThat(result.isRecurring()).isTrue();
    }

    @Test
    @DisplayName("kind 의 대소문자가 달라도 정기형으로 인식한다")
    void createRecurringGoalIgnoresKindCase() {
        givenSupportedCurrency("USD");
        when(userRepository.findById(ownerId)).thenReturn(Optional.of(owner));
        when(goalRepository.save(any(Goal.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Goal result = goalService.create(ownerId, new GoalCreateCommand(
                "ETF 적립", "RECURRING", "STOCK_ACCUMULATION", "USD", 10000.0, null,
                "monthly", 300000, "KRW", "monthly", false,
                0.0, null, null, LocalDate.of(2026, 10, 1), 6));

        assertThat(result.getGoalType()).isEqualTo(GoalType.RECURRING);
    }

    @Test
    @DisplayName("kind 가 null 이거나 정기형이 아니면 마감형으로 저장한다")
    void createGoalWithUnknownKindFallsBackToDeadline() {
        givenSupportedCurrency("USD");
        when(userRepository.findById(ownerId)).thenReturn(Optional.of(owner));
        when(goalRepository.save(any(Goal.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Goal nullKind = goalService.create(ownerId, command(
                "USD 목표",
                null,
                "TRAVEL",
                "USD",
                10000.0,
                LocalDate.of(2026, 12, 31),
                null,
                0,
                "KRW",
                null,
                false));
        Goal unknownKind = goalService.create(ownerId, command(
                "USD 목표",
                "banana",
                "TRAVEL",
                "USD",
                10000.0,
                LocalDate.of(2026, 12, 31),
                null,
                0,
                "KRW",
                null,
                false));

        assertThat(nullKind.getGoalType()).isEqualTo(GoalType.DEADLINE);
        assertThat(unknownKind.getGoalType()).isEqualTo(GoalType.DEADLINE);
    }

    @Test
    @DisplayName("목표 생성 시 사용자 미존재 예외")
    void createGoalUserNotFound() {
        givenSupportedCurrency("USD");
        when(userRepository.findById(ownerId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> goalService.create(ownerId, command(
                "USD 목표",
                "deadline",
                "TRAVEL",
                "USD",
                10000.0,
                LocalDate.of(2026, 12, 31),
                null,
                0,
                "KRW",
                null,
                false)))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("사용자를 찾을 수 없습니다.");
    }

    @Test
    @DisplayName("목표 생성 시 환율 조회가 안 되는 통화면 400 (이슈 #77) — GBP 는 마스터 표시 목록엔 있지만 ECOS 미고시다")
    void createGoalUnsupportedCurrencyRejected() {
        when(perUnitFxRates.find("GBP")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> goalService.create(ownerId, command(
                "GBP 목표",
                "deadline",
                "TRAVEL",
                "GBP",
                10000.0,
                LocalDate.of(2026, 12, 31),
                null,
                0,
                "KRW",
                null,
                false)))
                .isInstanceOf(InvalidRequestException.class)
                .hasFieldOrPropertyWithValue("field", "currency_code");
        verifyNoInteractions(userRepository, goalRepository);
    }

    @Test
    @DisplayName("목표 생성 시 존재하지 않는 통화코드도 400 으로 막힌다")
    void createGoalUnknownCurrencyCodeRejected() {
        when(perUnitFxRates.find("XYZ")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> goalService.create(ownerId, command(
                "XYZ 목표",
                "deadline",
                "TRAVEL",
                "XYZ",
                10000.0,
                LocalDate.of(2026, 12, 31),
                null,
                0,
                "KRW",
                null,
                false)))
                .isInstanceOf(InvalidRequestException.class)
                .hasFieldOrPropertyWithValue("field", "currency_code");
    }

    @Test
    @DisplayName("목표 생성 시 target_amount 가 0 이면 400")
    void createGoalZeroAmountRejected() {
        assertThatThrownBy(() -> goalService.create(ownerId, command(
                "USD 목표",
                "deadline",
                "TRAVEL",
                "USD",
                0.0,
                LocalDate.of(2026, 12, 31),
                null,
                0,
                "KRW",
                null,
                false)))
                .isInstanceOf(InvalidRequestException.class)
                .hasFieldOrPropertyWithValue("field", "target_amount");
        verifyNoInteractions(userRepository, goalRepository, perUnitFxRates);
    }

    @Test
    @DisplayName("목표 생성 시 target_amount 가 음수이면 400")
    void createGoalNegativeAmountRejected() {
        assertThatThrownBy(() -> goalService.create(ownerId, command(
                "USD 목표",
                "deadline",
                "TRAVEL",
                "USD",
                -500.0,
                LocalDate.of(2026, 12, 31),
                null,
                0,
                "KRW",
                null,
                false)))
                .isInstanceOf(InvalidRequestException.class)
                .hasFieldOrPropertyWithValue("field", "target_amount");
    }

    @Test
    @DisplayName("목표 생성 시 BucketAllocator 가 모르는 목적 코드면 400 — 계획 미리보기까지 가지 않고 앞당겨 막는다")
    void createGoalUnknownPurposeRejected() {
        givenSupportedCurrency("USD");

        assertThatThrownBy(() -> goalService.create(ownerId, command(
                "USD 목표",
                "deadline",
                "travel",
                "USD",
                10000.0,
                LocalDate.of(2026, 12, 31),
                null,
                0,
                "KRW",
                null,
                false)))
                .isInstanceOf(InvalidRequestException.class)
                .hasFieldOrPropertyWithValue("field", "purpose");
    }

    @Test
    @DisplayName("목표 생성 시 target_date 가 과거면 400")
    void createGoalPastTargetDateRejected() {
        givenSupportedCurrency("USD");

        assertThatThrownBy(() -> goalService.create(ownerId, command(
                "USD 목표",
                "deadline",
                "TRAVEL",
                "USD",
                10000.0,
                LocalDate.of(2026, 9, 5),
                null,
                0,
                "KRW",
                null,
                false)))
                .isInstanceOf(InvalidRequestException.class)
                .hasFieldOrPropertyWithValue("field", "target_date");
    }

    @Test
    @DisplayName("목표 생성 시 target_date 가 오늘이면 허용한다")
    void createGoalTodayTargetDateAllowed() {
        givenSupportedCurrency("USD");
        when(userRepository.findById(ownerId)).thenReturn(Optional.of(owner));
        when(goalRepository.save(any(Goal.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Goal result = goalService.create(ownerId, command(
                "USD 목표",
                "deadline",
                "TRAVEL",
                "USD",
                10000.0,
                LocalDate.of(2026, 9, 7),
                null,
                0,
                "KRW",
                null,
                false));

        assertThat(result.getTargetDate()).isEqualTo(LocalDate.of(2026, 9, 7));
    }

    @Test
    @DisplayName("목표 생성 시 target_date 가 없으면 과거 여부 검증을 건너뛰고 허용한다")
    void createGoalWithoutTargetDateAllowed() {
        givenSupportedCurrency("USD");
        when(userRepository.findById(ownerId)).thenReturn(Optional.of(owner));
        when(goalRepository.save(any(Goal.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Goal result = goalService.create(ownerId, command(
                "USD 목표",
                "deadline",
                "TRAVEL",
                "USD",
                10000.0,
                null,
                null,
                0,
                "KRW",
                null,
                false));

        assertThat(result.getTargetDate()).isNull();
    }

    // ── 플래너 계산 필드 (이슈 #195) ────────────────────────────────────────

    /** 마감형 생성 입력. 플래너 필드 세 개만 바꿔 가며 쓴다. */
    private static GoalCreateCommand deadlineCommand(
            double allocatedHoldingAmount, String preferredCadence, String priorityConstraint) {
        return new GoalCreateCommand("USD 목표", "deadline", "TRAVEL", "USD", 10000.0,
                LocalDate.of(2026, 12, 31), null, 0, "KRW", null, false,
                allocatedHoldingAmount, preferredCadence, priorityConstraint, null, null);
    }

    /** 정기형 생성 입력. 반복 주기·시작일·점검 기간을 바꿔 가며 쓴다. */
    private static GoalCreateCommand recurringCommand(
            String recurInterval, LocalDate startDate, Integer reviewHorizonMonths) {
        return new GoalCreateCommand("ETF 적립", "recurring", "STOCK_ACCUMULATION", "USD", 10000.0,
                null, recurInterval, 300000, "KRW", "monthly", false,
                0.0, null, null, startDate, reviewHorizonMonths);
    }

    private void givenSavableGoal() {
        givenSupportedCurrency("USD");
        when(userRepository.findById(ownerId)).thenReturn(Optional.of(owner));
        when(goalRepository.save(any(Goal.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    @DisplayName("플래너 필드를 입력하면 그대로 저장한다")
    void createGoalPersistsPlannerFields() {
        givenSavableGoal();

        Goal result = goalService.create(ownerId, deadlineCommand(3000.0, "monthly", "date"));

        assertThat(result.getAllocatedHoldingAmount()).isEqualTo(3000.0);
        assertThat(result.getPreferredCadence()).isEqualTo("monthly");
        assertThat(result.getPriorityConstraint()).isEqualTo(PriorityConstraint.DATE);
    }

    @Test
    @DisplayName("마감형 기본값 — 배정 0, 준비 주기 weekly, 우선 조건 amount")
    void createDeadlineGoalUsesDeadlineDefaults() {
        givenSavableGoal();

        Goal result = goalService.create(ownerId, deadlineCommand(0.0, null, null));

        assertThat(result.getAllocatedHoldingAmount()).isZero();
        assertThat(result.getPreferredCadence()).isEqualTo("weekly");
        assertThat(result.getPriorityConstraint()).isEqualTo(PriorityConstraint.AMOUNT);
    }

    @Test
    @DisplayName("빈 문자열도 미입력으로 보고 기본값을 채운다")
    void createGoalTreatsBlankPlannerFieldsAsAbsent() {
        givenSavableGoal();

        Goal result = goalService.create(ownerId, deadlineCommand(0.0, "  ", "  "));

        assertThat(result.getPreferredCadence()).isEqualTo("weekly");
        assertThat(result.getPriorityConstraint()).isEqualTo(PriorityConstraint.AMOUNT);
    }

    @Test
    @DisplayName("정기형 기본값 — 준비 주기는 반복 주기를 따르고 우선 조건은 budget")
    void createRecurringGoalUsesRecurringDefaults() {
        givenSavableGoal();

        Goal result = goalService.create(
                ownerId, recurringCommand("monthly", LocalDate.of(2026, 10, 1), 6));

        // V16 이 기존 정기형 행을 budget 으로 백필했다. 신규 생성분도 같은 규칙을 따라야 한다.
        assertThat(result.getPriorityConstraint()).isEqualTo(PriorityConstraint.BUDGET);
        assertThat(result.getPreferredCadence()).isEqualTo("monthly");
        assertThat(result.getRecurStartDate()).isEqualTo(LocalDate.of(2026, 10, 1));
        assertThat(result.getReviewHorizonMonths()).isEqualTo(6);
    }

    @Test
    @DisplayName("우선 조건 세 값을 대소문자와 무관하게 소문자 상수로 저장한다")
    void createGoalNormalizesPriorityConstraintCase() {
        givenSavableGoal();

        Goal amount = goalService.create(ownerId, deadlineCommand(0.0, null, "AMOUNT"));
        Goal date = goalService.create(ownerId, deadlineCommand(0.0, null, "Date"));
        Goal budget = goalService.create(ownerId, deadlineCommand(0.0, null, "budget"));

        // PlanScenarioService 가 switch 로 정확히 비교한다 — 대문자로 저장하면 예외 없이
        // 조용히 금액 우선으로 떨어진다.
        assertThat(amount.getPriorityConstraint()).isEqualTo(PriorityConstraint.AMOUNT);
        assertThat(date.getPriorityConstraint()).isEqualTo(PriorityConstraint.DATE);
        assertThat(budget.getPriorityConstraint()).isEqualTo(PriorityConstraint.BUDGET);
    }

    @Test
    @DisplayName("배정 보유 외화가 음수면 거절한다")
    void createGoalNegativeAllocationRejected() {
        givenSupportedCurrency("USD");

        assertThatThrownBy(() -> goalService.create(ownerId, deadlineCommand(-1.0, null, null)))
                .isInstanceOf(InvalidRequestException.class)
                .extracting(e -> ((InvalidRequestException) e).getField())
                .isEqualTo("allocated_holding_amount");
    }

    @Test
    @DisplayName("배정 보유 외화가 목표 금액을 넘어도 거절하지 않는다")
    void createGoalAllocationAboveTargetAllowed() {
        givenSavableGoal();

        Goal result = goalService.create(ownerId, deadlineCommand(999999.0, null, null));

        // 계산이 max(T - H, 0) 으로 흡수하고 TARGET_ALREADY_MET 경고를 내는 쪽이 더 정확하다.
        assertThat(result.getAllocatedHoldingAmount()).isEqualTo(999999.0);
    }

    @Test
    @DisplayName("알 수 없는 준비 주기는 거절한다")
    void createGoalUnknownCadenceRejected() {
        givenSupportedCurrency("USD");

        assertThatThrownBy(() -> goalService.create(ownerId, deadlineCommand(0.0, "daily", null)))
                .isInstanceOf(InvalidRequestException.class)
                .extracting(e -> ((InvalidRequestException) e).getField())
                .isEqualTo("preferred_cadence");
    }

    @Test
    @DisplayName("알 수 없는 우선 조건은 거절한다")
    void createGoalUnknownPriorityConstraintRejected() {
        givenSupportedCurrency("USD");

        assertThatThrownBy(() -> goalService.create(ownerId, deadlineCommand(0.0, null, "vibes")))
                .isInstanceOf(InvalidRequestException.class)
                .extracting(e -> ((InvalidRequestException) e).getField())
                .isEqualTo("priority_constraint");
    }

    @Test
    @DisplayName("정기형인데 시작일이 없으면 거절한다")
    void createRecurringGoalWithoutStartDateRejected() {
        givenSupportedCurrency("USD");

        assertThatThrownBy(() -> goalService.create(ownerId, recurringCommand("monthly", null, 6)))
                .isInstanceOf(InvalidRequestException.class)
                .extracting(e -> ((InvalidRequestException) e).getField())
                .isEqualTo("start_date");
    }

    @Test
    @DisplayName("정기형인데 점검 기간이 없거나 1개월 미만이면 거절한다")
    void createRecurringGoalWithoutReviewHorizonRejected() {
        givenSupportedCurrency("USD");
        LocalDate startDate = LocalDate.of(2026, 10, 1);

        assertThatThrownBy(() -> goalService.create(ownerId, recurringCommand("monthly", startDate, null)))
                .isInstanceOf(InvalidRequestException.class)
                .extracting(e -> ((InvalidRequestException) e).getField())
                .isEqualTo("review_horizon_months");
        assertThatThrownBy(() -> goalService.create(ownerId, recurringCommand("monthly", startDate, 0)))
                .isInstanceOf(InvalidRequestException.class)
                .extracting(e -> ((InvalidRequestException) e).getField())
                .isEqualTo("review_horizon_months");
    }

    @Test
    @DisplayName("정기형의 반복 주기가 알 수 없는 값이면 거절한다")
    void createRecurringGoalUnknownIntervalRejected() {
        givenSupportedCurrency("USD");

        assertThatThrownBy(() -> goalService.create(
                ownerId, recurringCommand("daily", LocalDate.of(2026, 10, 1), 6)))
                .isInstanceOf(InvalidRequestException.class)
                .extracting(e -> ((InvalidRequestException) e).getField())
                .isEqualTo("preferred_cadence");
    }

    @Test
    @DisplayName("소유자별 목표 목록 조회")
    void listByOwnerSuccess() {
        Goal goal1 = Goal.builder(owner, "USD 목표", "deadline", "travel", "USD")
                .targetAmount(10000.0)
                .status("active")
                .build();
        Goal goal2 = Goal.builder(owner, "EUR 목표", "recurring", "invest", "EUR")
                .targetAmount(5000.0)
                .status("active")
                .build();

        when(goalRepository.findByOwner_Id(ownerId)).thenReturn(List.of(goal1, goal2));

        List<Goal> results = goalService.listByOwner(ownerId);

        assertThat(results).hasSize(2);
        assertThat(results.get(0).getName()).isEqualTo("USD 목표");
        assertThat(results.get(1).getName()).isEqualTo("EUR 목표");
    }

    @Test
    @DisplayName("소유자별 목표 목록 조회 (빈 결과)")
    void listByOwnerEmpty() {
        when(goalRepository.findByOwner_Id(ownerId)).thenReturn(List.of());

        List<Goal> results = goalService.listByOwner(ownerId);

        assertThat(results).isEmpty();
    }

    @Test
    @DisplayName("목표 단일 조회 성공")
    void getByIdAndOwnerSuccess() {
        UUID goalId = UUID.randomUUID();
        Goal goal = Goal.builder(owner, "USD 목표", "deadline", "travel", "USD")
                .targetAmount(10000.0)
                .status("active")
                .build();

        when(goalRepository.findById(goalId)).thenReturn(Optional.of(goal));
        when(owner.getId()).thenReturn(ownerId);

        Goal result = goalService.getByIdAndOwner(ownerId, goalId);

        assertThat(result).isNotNull();
        assertThat(result.getName()).isEqualTo("USD 목표");
    }

    @Test
    @DisplayName("목표 조회 시 목표 미존재 예외")
    void getByIdAndOwnerNotFound() {
        UUID goalId = UUID.randomUUID();

        when(goalRepository.findById(goalId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> goalService.getByIdAndOwner(ownerId, goalId))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("목표를 찾을 수 없습니다.");
    }

    @Test
    @DisplayName("목표 조회 시 소유자 불일치 예외")
    void getByIdAndOwnerOwnerMismatch() {
        UUID goalId = UUID.randomUUID();
        UUID otherOwnerId = UUID.randomUUID();
        Goal goal = Goal.builder(owner, "USD 목표", "deadline", "travel", "USD")
                .targetAmount(10000.0)
                .status("active")
                .build();

        when(goalRepository.findById(goalId)).thenReturn(Optional.of(goal));
        when(owner.getId()).thenReturn(otherOwnerId);

        assertThatThrownBy(() -> goalService.getByIdAndOwner(ownerId, goalId))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("목표를 찾을 수 없습니다.");
    }

    @Test
    @DisplayName("목표 수정 성공")
    void updateGoalSuccess() {
        UUID goalId = UUID.randomUUID();
        Goal goal = Goal.builder(owner, "USD 목표", "deadline", "travel", "USD")
                .targetAmount(10000.0)
                .budgetAmount(100000)
                .budgetPeriod("month")
                .isSpeculative(false)
                .status("active")
                .build();

        when(goalRepository.findById(goalId)).thenReturn(Optional.of(goal));
        when(owner.getId()).thenReturn(ownerId);

        Goal result = goalService.update(
                ownerId,
                goalId,
                "수정된 목표",
                20000.0,
                LocalDate.of(2027, 12, 31),
                200000L,
                "year",
                true);

        assertThat(result.getName()).isEqualTo("수정된 목표");
        assertThat(result.getTargetAmount()).isEqualTo(20000.0);
        assertThat(result.getTargetDate()).isEqualTo(LocalDate.of(2027, 12, 31));
        assertThat(result.getBudgetAmount()).isEqualTo(200000L);
        assertThat(result.getBudgetPeriod()).isEqualTo("year");
        assertThat(result.isSpeculative()).isTrue();
    }

    @Test
    @DisplayName("목표 수정 부분 필드만 업데이트")
    void updateGoalPartial() {
        UUID goalId = UUID.randomUUID();
        Goal goal = Goal.builder(owner, "USD 목표", "deadline", "travel", "USD")
                .targetAmount(10000.0)
                .budgetAmount(100000)
                .isSpeculative(false)
                .status("active")
                .build();

        when(goalRepository.findById(goalId)).thenReturn(Optional.of(goal));
        when(owner.getId()).thenReturn(ownerId);

        Goal result = goalService.update(
                ownerId,
                goalId,
                "수정된 이름",
                null,
                null,
                null,
                null,
                null);

        assertThat(result.getName()).isEqualTo("수정된 이름");
        assertThat(result.getTargetAmount()).isEqualTo(10000.0);
        assertThat(result.getBudgetAmount()).isEqualTo(100000L);
        assertThat(result.isSpeculative()).isFalse();
    }

    /**
     * {@code name} 만 null 인 경우. 기존 테스트는 항상 name 을 넘겨 주어
     * {@code if (name != null)} 의 false 분기가 미커버로 남아 있었다(이슈 #40).
     */
    @Test
    @DisplayName("목표 수정 시 이름을 생략하면 기존 이름이 유지된다")
    void updateGoalWithoutName() {
        UUID goalId = UUID.randomUUID();
        Goal goal = Goal.builder(owner, "USD 목표", "deadline", "travel", "USD")
                .targetAmount(10000.0)
                .budgetAmount(100000)
                .isSpeculative(false)
                .status("active")
                .build();

        when(goalRepository.findById(goalId)).thenReturn(Optional.of(goal));
        when(owner.getId()).thenReturn(ownerId);

        Goal result = goalService.update(
                ownerId,
                goalId,
                null,
                20000.0,
                null,
                null,
                null,
                null);

        assertThat(result.getName()).isEqualTo("USD 목표");
        assertThat(result.getTargetAmount()).isEqualTo(20000.0);
    }

    @Test
    @DisplayName("목표 수정 시 이름을 공백으로 바꾸려 하면 400")
    void updateGoalBlankNameRejected() {
        UUID goalId = UUID.randomUUID();
        Goal goal = Goal.builder(owner, "USD 목표", "deadline", "travel", "USD")
                .targetAmount(10000.0)
                .status("active")
                .build();

        when(goalRepository.findById(goalId)).thenReturn(Optional.of(goal));
        when(owner.getId()).thenReturn(ownerId);

        assertThatThrownBy(() -> goalService.update(
                ownerId, goalId, "   ", null, null, null, null, null))
                .isInstanceOf(InvalidRequestException.class)
                .hasFieldOrPropertyWithValue("field", "name");
    }

    @Test
    @DisplayName("목표 수정 시 target_date 를 과거로 바꾸려 하면 400")
    void updateGoalPastTargetDateRejected() {
        UUID goalId = UUID.randomUUID();
        Goal goal = Goal.builder(owner, "USD 목표", "deadline", "travel", "USD")
                .targetAmount(10000.0)
                .status("active")
                .build();

        when(goalRepository.findById(goalId)).thenReturn(Optional.of(goal));
        when(owner.getId()).thenReturn(ownerId);

        assertThatThrownBy(() -> goalService.update(
                ownerId, goalId, null, null, LocalDate.of(2026, 9, 5), null, null, null))
                .isInstanceOf(InvalidRequestException.class)
                .hasFieldOrPropertyWithValue("field", "target_date");
    }

    @Test
    @DisplayName("목표 수정 시 target_amount 를 0 이하로 바꾸려 하면 400")
    void updateGoalNonPositiveAmountRejected() {
        UUID goalId = UUID.randomUUID();
        Goal goal = Goal.builder(owner, "USD 목표", "deadline", "travel", "USD")
                .targetAmount(10000.0)
                .status("active")
                .build();

        when(goalRepository.findById(goalId)).thenReturn(Optional.of(goal));
        when(owner.getId()).thenReturn(ownerId);

        assertThatThrownBy(() -> goalService.update(
                ownerId, goalId, null, 0.0, null, null, null, null))
                .isInstanceOf(InvalidRequestException.class)
                .hasFieldOrPropertyWithValue("field", "target_amount");
    }

    @Test
    @DisplayName("목표 삭제 성공")
    void deleteGoalSuccess() {
        UUID goalId = UUID.randomUUID();
        Goal goal = Goal.builder(owner, "USD 목표", "deadline", "travel", "USD")
                .targetAmount(10000.0)
                .status("active")
                .build();

        when(goalRepository.findById(goalId)).thenReturn(Optional.of(goal));
        when(owner.getId()).thenReturn(ownerId);
        doNothing().when(goalRepository).delete(goal);

        goalService.delete(ownerId, goalId);

        verify(goalRepository).delete(goal);
    }

    @Test
    @DisplayName("보유 외화금액 조회 성공")
    void getHeldAmountByCurrencySuccess() {
        Holding holding = Holding.create(owner, "AAPL", "USD", 100.0, 150.0);
        Deposit deposit = Deposit.create(owner, "USD", java.math.BigDecimal.valueOf(5000.0));

        when(holdingService.list(ownerId)).thenReturn(List.of(holding));
        when(depositService.list(ownerId)).thenReturn(List.of(deposit));

        double heldAmount = goalService.getHeldAmountByCurrency(ownerId, "USD");

        assertThat(heldAmount).isEqualTo(20000.0);
    }

    @Test
    @DisplayName("보유 외화금액 조회 (보유 없음)")
    void getHeldAmountByCurrencyEmpty() {
        when(holdingService.list(ownerId)).thenReturn(List.of());
        when(depositService.list(ownerId)).thenReturn(List.of());

        double heldAmount = goalService.getHeldAmountByCurrency(ownerId, "USD");

        assertThat(heldAmount).isEqualTo(0.0);
    }
}
