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

        Goal result = goalService.create(
                ownerId,
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
                false);

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

        Goal result = goalService.create(
                ownerId, "ETF 적립", "recurring", "STOCK_ACCUMULATION", "USD",
                10000.0, null, "monthly", 300000, "KRW", "monthly", false);

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

        Goal result = goalService.create(
                ownerId, "ETF 적립", "RECURRING", "STOCK_ACCUMULATION", "USD",
                10000.0, null, "monthly", 300000, "KRW", "monthly", false);

        assertThat(result.getGoalType()).isEqualTo(GoalType.RECURRING);
    }

    @Test
    @DisplayName("kind 가 null 이거나 정기형이 아니면 마감형으로 저장한다")
    void createGoalWithUnknownKindFallsBackToDeadline() {
        givenSupportedCurrency("USD");
        when(userRepository.findById(ownerId)).thenReturn(Optional.of(owner));
        when(goalRepository.save(any(Goal.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Goal nullKind = goalService.create(
                ownerId, "USD 목표", null, "TRAVEL", "USD",
                10000.0, LocalDate.of(2026, 12, 31), null, 0, "KRW", null, false);
        Goal unknownKind = goalService.create(
                ownerId, "USD 목표", "banana", "TRAVEL", "USD",
                10000.0, LocalDate.of(2026, 12, 31), null, 0, "KRW", null, false);

        assertThat(nullKind.getGoalType()).isEqualTo(GoalType.DEADLINE);
        assertThat(unknownKind.getGoalType()).isEqualTo(GoalType.DEADLINE);
    }

    @Test
    @DisplayName("목표 생성 시 사용자 미존재 예외")
    void createGoalUserNotFound() {
        givenSupportedCurrency("USD");
        when(userRepository.findById(ownerId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> goalService.create(
                ownerId, "USD 목표", "deadline", "TRAVEL", "USD",
                10000.0, LocalDate.of(2026, 12, 31), null, 0, "KRW", null, false))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("사용자를 찾을 수 없습니다.");
    }

    @Test
    @DisplayName("목표 생성 시 환율 조회가 안 되는 통화면 400 (이슈 #77) — GBP 는 마스터 표시 목록엔 있지만 ECOS 미고시다")
    void createGoalUnsupportedCurrencyRejected() {
        when(perUnitFxRates.find("GBP")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> goalService.create(
                ownerId, "GBP 목표", "deadline", "TRAVEL", "GBP",
                10000.0, LocalDate.of(2026, 12, 31), null, 0, "KRW", null, false))
                .isInstanceOf(InvalidRequestException.class)
                .hasFieldOrPropertyWithValue("field", "currency_code");
        verifyNoInteractions(userRepository, goalRepository);
    }

    @Test
    @DisplayName("목표 생성 시 존재하지 않는 통화코드도 400 으로 막힌다")
    void createGoalUnknownCurrencyCodeRejected() {
        when(perUnitFxRates.find("XYZ")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> goalService.create(
                ownerId, "XYZ 목표", "deadline", "TRAVEL", "XYZ",
                10000.0, LocalDate.of(2026, 12, 31), null, 0, "KRW", null, false))
                .isInstanceOf(InvalidRequestException.class)
                .hasFieldOrPropertyWithValue("field", "currency_code");
    }

    @Test
    @DisplayName("목표 생성 시 target_amount 가 0 이면 400")
    void createGoalZeroAmountRejected() {
        assertThatThrownBy(() -> goalService.create(
                ownerId, "USD 목표", "deadline", "TRAVEL", "USD",
                0.0, LocalDate.of(2026, 12, 31), null, 0, "KRW", null, false))
                .isInstanceOf(InvalidRequestException.class)
                .hasFieldOrPropertyWithValue("field", "target_amount");
        verifyNoInteractions(userRepository, goalRepository, perUnitFxRates);
    }

    @Test
    @DisplayName("목표 생성 시 target_amount 가 음수이면 400")
    void createGoalNegativeAmountRejected() {
        assertThatThrownBy(() -> goalService.create(
                ownerId, "USD 목표", "deadline", "TRAVEL", "USD",
                -500.0, LocalDate.of(2026, 12, 31), null, 0, "KRW", null, false))
                .isInstanceOf(InvalidRequestException.class)
                .hasFieldOrPropertyWithValue("field", "target_amount");
    }

    @Test
    @DisplayName("목표 생성 시 BucketAllocator 가 모르는 목적 코드면 400 — 계획 미리보기까지 가지 않고 앞당겨 막는다")
    void createGoalUnknownPurposeRejected() {
        givenSupportedCurrency("USD");

        assertThatThrownBy(() -> goalService.create(
                ownerId, "USD 목표", "deadline", "travel", "USD",
                10000.0, LocalDate.of(2026, 12, 31), null, 0, "KRW", null, false))
                .isInstanceOf(InvalidRequestException.class)
                .hasFieldOrPropertyWithValue("field", "purpose");
    }

    @Test
    @DisplayName("목표 생성 시 target_date 가 과거면 400")
    void createGoalPastTargetDateRejected() {
        givenSupportedCurrency("USD");

        assertThatThrownBy(() -> goalService.create(
                ownerId, "USD 목표", "deadline", "TRAVEL", "USD",
                10000.0, LocalDate.of(2026, 9, 5), null, 0, "KRW", null, false))
                .isInstanceOf(InvalidRequestException.class)
                .hasFieldOrPropertyWithValue("field", "target_date");
    }

    @Test
    @DisplayName("목표 생성 시 target_date 가 오늘이면 허용한다")
    void createGoalTodayTargetDateAllowed() {
        givenSupportedCurrency("USD");
        when(userRepository.findById(ownerId)).thenReturn(Optional.of(owner));
        when(goalRepository.save(any(Goal.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Goal result = goalService.create(
                ownerId, "USD 목표", "deadline", "TRAVEL", "USD",
                10000.0, LocalDate.of(2026, 9, 7), null, 0, "KRW", null, false);

        assertThat(result.getTargetDate()).isEqualTo(LocalDate.of(2026, 9, 7));
    }

    @Test
    @DisplayName("목표 생성 시 target_date 가 없으면 과거 여부 검증을 건너뛰고 허용한다")
    void createGoalWithoutTargetDateAllowed() {
        givenSupportedCurrency("USD");
        when(userRepository.findById(ownerId)).thenReturn(Optional.of(owner));
        when(goalRepository.save(any(Goal.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Goal result = goalService.create(
                ownerId, "USD 목표", "deadline", "TRAVEL", "USD",
                10000.0, null, null, 0, "KRW", null, false);

        assertThat(result.getTargetDate()).isNull();
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
