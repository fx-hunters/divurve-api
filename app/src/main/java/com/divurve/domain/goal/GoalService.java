package com.divurve.domain.goal;

import com.divurve.common.architecture.UseCase;
import com.divurve.common.exception.InvalidRequestException;
import com.divurve.common.exception.NotFoundException;
import com.divurve.domain.fx.PerUnitFxRates;
import com.divurve.domain.goal.entity.Goal;
import com.divurve.domain.holding.DepositService;
import com.divurve.domain.holding.HoldingService;
import com.divurve.domain.user.UserRepository;
import com.divurve.domain.user.entity.User;
import com.divurve.engine.bucket.BucketAllocator;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

/**
 * 목표 CRUD 유스케이스 (이슈 #17, FR-RT-01/02/03/04/05).
 * 소유자 필터(NFR-SE-03)로 데이터를 격리하고, held_amount는 HoldingService/DepositService에서 조회한다.
 *
 * <p>입력 검증(이슈 #77) — {@code name} 공백 여부만 DTO 의 {@code @Valid} 가 컨트롤러 경계에서
 * 막고, 나머지는 전부 여기서 검증한다. 통화 화이트리스트·목적 ENUM·목표일 과거 여부는 형식이 아니라
 * 도메인 규칙이라 애초에 이 계층이 맞다. {@code target_amount} 의 0 이하 검사는 형식에 가깝지만
 * 같은 목표 규칙끼리 흩어지지 않도록 여기에 모아 뒀고, {@code field} 에 스네이크케이스 문자열을
 * 직접 쓴다.
 * <ul>
 *   <li>{@code target_amount} — 0 이하를 막는다. 상한은 두지 않는다(근거 없는 임의 상한은
 *       정책 결정이다).</li>
 *   <li>{@code currency_code} — {@link PerUnitFxRates} 로 실제 환율 조회가 가능한 통화인지 확인한다.
 *       {@code /currencies}(마스터 표시 목록)는 표시 규칙일 뿐이라 진실의 원천으로 쓰지 않았다 —
 *       GBP 처럼 마스터 목록에는 있어도 ECOS 미고시라 {@code /forecast}·매입 환율 조회가 이미 400 을
 *       내는 통화가 있다(이슈 #57). 목표 통화는 전망·환산이 가능해야 하므로, 그 판정을 이미
 *       도맡고 있는 {@link PerUnitFxRates} 하나로 통일했다.</li>
 *   <li>{@code purpose} — {@link BucketAllocator#getSafeRatioFloor} 가 실제로 인식하는 목적 코드인지
 *       확인한다. 지금까지는 계획 미리보기까지 가서야 이 ENUM 불일치가 400 으로 드러났다.</li>
 *   <li>{@code target_date} — 과거 날짜만 막는다. 오늘은 허용하고 미래 상한은 두지 않는다
 *       (정책 미확정).</li>
 * </ul>
 */
@UseCase
public class GoalService {

    private static final String FIELD_TARGET_AMOUNT = "target_amount";
    private static final String FIELD_CURRENCY_CODE = "currency_code";
    private static final String FIELD_PURPOSE = "purpose";
    private static final String FIELD_TARGET_DATE = "target_date";
    private static final String FIELD_NAME = "name";

    private final GoalRepository goalRepository;
    private final UserRepository userRepository;
    private final HoldingService holdingService;
    private final DepositService depositService;
    private final PerUnitFxRates perUnitFxRates;
    private final BucketAllocator bucketAllocator;
    private final Clock clock;

    public GoalService(
            GoalRepository goalRepository,
            UserRepository userRepository,
            HoldingService holdingService,
            DepositService depositService,
            PerUnitFxRates perUnitFxRates,
            BucketAllocator bucketAllocator,
            Clock clock) {
        this.goalRepository = goalRepository;
        this.userRepository = userRepository;
        this.holdingService = holdingService;
        this.depositService = depositService;
        this.perUnitFxRates = Objects.requireNonNull(perUnitFxRates, "perUnitFxRates");
        this.bucketAllocator = Objects.requireNonNull(bucketAllocator, "bucketAllocator");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** 소유자의 목표 목록을 조회한다. */
    @Transactional(readOnly = true)
    public List<Goal> listByOwner(UUID ownerId) {
        return goalRepository.findByOwner_Id(ownerId);
    }

    /** 새 목표를 생성한다. */
    @Transactional
    public Goal create(UUID ownerId, String name, String kind, String purpose, String currencyCode,
            double targetAmount, LocalDate targetDate, String recurInterval,
            long budgetAmount, String budgetCurrencyCode, String budgetPeriod, boolean isSpeculative) {
        requirePositiveAmount(targetAmount);
        requireSupportedCurrency(currencyCode);
        requireKnownPurpose(purpose);
        requireTargetDateNotPast(targetDate);

        User owner = userRepository.findById(ownerId)
                .orElseThrow(() -> new NotFoundException("사용자를 찾을 수 없습니다."));

        Goal goal = Goal.builder(owner, name, kind, purpose, currencyCode)
                .goalType(resolveGoalType(kind))
                .targetAmount(targetAmount)
                .targetDate(targetDate)
                .recurInterval(recurInterval)
                .budgetAmount(budgetAmount)
                .budgetCurrencyCode(budgetCurrencyCode)
                .budgetPeriod(budgetPeriod)
                .isSpeculative(isSpeculative)
                .status("active")
                .build();

        return goalRepository.save(goal);
    }

    /** 소유자의 목표를 조회한다. */
    @Transactional(readOnly = true)
    public Goal getByIdAndOwner(UUID ownerId, UUID goalId) {
        Goal goal = goalRepository.findById(goalId)
                .orElseThrow(() -> new NotFoundException("목표를 찾을 수 없습니다."));
        if (!goal.getOwner().getId().equals(ownerId)) {
            throw new NotFoundException("목표를 찾을 수 없습니다.");
        }
        return goal;
    }

    /** 소유자의 목표를 수정한다. */
    @Transactional
    public Goal update(UUID ownerId, UUID goalId, String name, Double targetAmount,
            LocalDate targetDate, Long budgetAmount, String budgetPeriod, Boolean isSpeculative) {
        Goal goal = getByIdAndOwner(ownerId, goalId);

        if (name != null) {
            requireNonBlankName(name);
            goal.setName(name);
        }
        if (targetAmount != null) {
            requirePositiveAmount(targetAmount);
            goal.setTargetAmount(targetAmount);
        }
        if (targetDate != null) {
            requireTargetDateNotPast(targetDate);
            goal.setTargetDate(targetDate);
        }
        if (budgetAmount != null) {
            goal.setBudgetAmount(budgetAmount);
        }
        if (budgetPeriod != null) {
            goal.setBudgetPeriod(budgetPeriod);
        }
        if (isSpeculative != null) {
            goal.setSpeculative(isSpeculative);
        }

        return goal;
    }

    /** 소유자의 목표를 삭제한다. 계획 이력은 보존된다. */
    @Transactional
    public void delete(UUID ownerId, UUID goalId) {
        Goal goal = getByIdAndOwner(ownerId, goalId);
        goalRepository.delete(goal);
    }

    /** 소유자의 보유 외화금액을 조회한다 (목표금액과의 차이 계산에 사용). */
    @Transactional(readOnly = true)
    public double getHeldAmountByCurrency(UUID ownerId, String currencyCode) {
        double holdingAmount = calculateHoldingAmount(ownerId, currencyCode);
        double depositAmount = calculateDepositAmount(ownerId, currencyCode);
        return holdingAmount + depositAmount;
    }

    private double calculateHoldingAmount(UUID ownerId, String currencyCode) {
        return holdingService.list(ownerId).stream()
                .filter(holding -> currencyCode.equals(holding.getCurrencyCode()))
                .mapToDouble(holding -> holding.getQuantity() * holding.getAvgPrice())
                .sum();
    }

    private double calculateDepositAmount(UUID ownerId, String currencyCode) {
        return depositService.list(ownerId).stream()
                .filter(deposit -> currencyCode.equals(deposit.getCurrencyCode()))
                .map(deposit -> deposit.getAmount())
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .doubleValue();
    }

    /** 목표 금액은 0보다 커야 한다. 상한은 두지 않는다(근거 없는 임의 상한은 정책 결정이다). */
    private void requirePositiveAmount(double targetAmount) {
        if (targetAmount <= 0) {
            throw new InvalidRequestException("목표 금액은 0보다 커야 합니다.", FIELD_TARGET_AMOUNT);
        }
    }

    /**
     * 목표 통화가 실제로 환율 조회·전망이 가능한지 확인한다(이슈 #77). 목표는 세울 수 있는데
     * 전망·환산이 안 되는 상태를 만들지 않기 위해서다 — {@link PerUnitFxRates} 가 실패하면
     * ECOS 가 고시하지 않거나 존재하지 않는 통화코드다.
     */
    private void requireSupportedCurrency(String currencyCode) {
        if (perUnitFxRates.find(currencyCode).isEmpty()) {
            throw new InvalidRequestException(
                    "환율 조회가 지원되지 않는 통화입니다: " + currencyCode, FIELD_CURRENCY_CODE);
        }
    }

    /**
     * {@link BucketAllocator} 가 인식하는 목적 코드인지 확인한다. 여기서 걸러 두지 않으면
     * 계획 미리보기(FR-RT-06/07) 에서야 같은 위반이 400 으로 드러난다.
     */
    private void requireKnownPurpose(String purpose) {
        try {
            bucketAllocator.getSafeRatioFloor(purpose);
        } catch (IllegalArgumentException e) {
            throw new InvalidRequestException("지원하지 않는 목표 목적입니다: " + purpose, FIELD_PURPOSE);
        }
    }

    /** 목표일은 과거일 수 없다. 오늘은 허용하고 미래 상한은 두지 않는다(정책 미확정). */
    private void requireTargetDateNotPast(LocalDate targetDate) {
        if (targetDate != null && targetDate.isBefore(LocalDate.now(clock))) {
            throw new InvalidRequestException("목표일은 과거일 수 없습니다.", FIELD_TARGET_DATE);
        }
    }

    /**
     * 요청의 {@code kind} 를 계산이 읽는 {@code goal_type} 으로 옮긴다 (이슈 #193).
     *
     * <p>유형 컬럼이 둘이다 — 옛 {@code kind} 와 플래너가 읽는 {@code goal_type} 이다
     * ({@code V16__planner_expand.sql}). {@code V16} 은 기존 행을 한 번 옮겼지만 생성 경로가
     * {@code goal_type} 을 채우지 않아, 그 이후 만들어진 정기형 목표가 전부 빌더 기본값인
     * 마감형으로 저장됐다. {@link Goal#isRecurring()} 이 {@code goal_type} 을 보므로
     * {@code PlanInput.from(goal)} 이 정기형을 마감형 경로로 넘겼다.
     *
     * <p>정기형으로 인식하는 값은 {@link GoalType#RECURRING} 하나이며 대소문자를 가리지 않는다.
     * 그 외는 마감형이다 — {@code kind} 화이트리스트 검증은 이 수정의 범위가 아니라 별도로 다룬다.
     */
    private static String resolveGoalType(String kind) {
        return GoalType.RECURRING.equalsIgnoreCase(kind) ? GoalType.RECURRING : GoalType.DEADLINE;
    }

    /** 이름을 빈 문자열·공백으로 바꾸는 수정은 막는다. */
    private void requireNonBlankName(String name) {
        if (name.isBlank()) {
            throw new InvalidRequestException("목표 이름은 공백일 수 없습니다.", FIELD_NAME);
        }
    }
}
