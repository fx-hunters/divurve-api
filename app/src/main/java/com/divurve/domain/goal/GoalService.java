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
import com.divurve.engine.planner.Cadence;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
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
    private static final String FIELD_ALLOCATED_HOLDING_AMOUNT = "allocated_holding_amount";
    private static final String FIELD_PREFERRED_CADENCE = "preferred_cadence";
    private static final String FIELD_PRIORITY_CONSTRAINT = "priority_constraint";
    private static final String FIELD_START_DATE = "start_date";
    private static final String FIELD_REVIEW_HORIZON_MONTHS = "review_horizon_months";

    /**
     * 마감형 준비 주기 기본값 (명세 §5.2). {@code PlanCalculationService} 의 계산 기본값과 같은
     * 값을 저장 시점에 고정한다 — 열거에서 끌어와 둘이 어긋날 여지를 없앤다.
     */
    private static final String DEFAULT_DEADLINE_CADENCE =
            Cadence.WEEKLY.name().toLowerCase(Locale.ROOT);

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
    public Goal create(UUID ownerId, GoalCreateCommand command) {
        Objects.requireNonNull(command, "command");
        requirePositiveAmount(command.targetAmount());
        requireSupportedCurrency(command.currencyCode());
        requireKnownPurpose(command.purpose());
        requireTargetDateNotPast(command.targetDate());
        requireNonNegativeAllocation(command.allocatedHoldingAmount());
        requireKnownCadence(command.preferredCadence(), FIELD_PREFERRED_CADENCE);
        requireKnownPriorityConstraint(command.priorityConstraint());
        requireRecurringPlannerFields(command);

        User owner = userRepository.findById(ownerId)
                .orElseThrow(() -> new NotFoundException("사용자를 찾을 수 없습니다."));

        Goal goal = Goal.builder(
                        owner, command.name(), command.kind(), command.purpose(), command.currencyCode())
                .goalType(command.goalType())
                .targetAmount(command.targetAmount())
                .targetDate(command.targetDate())
                .recurInterval(command.recurInterval())
                .budgetAmount(command.budgetAmount())
                .budgetCurrencyCode(command.budgetCurrencyCode())
                .budgetPeriod(command.budgetPeriod())
                .isSpeculative(command.isSpeculative())
                .allocatedHoldingAmount(command.allocatedHoldingAmount())
                .preferredCadence(resolvePreferredCadence(command))
                .priorityConstraint(resolvePriorityConstraint(command))
                .recurStartDate(command.startDate())
                .reviewHorizonMonths(command.reviewHorizonMonths())
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
     * 배정할 보유 외화는 음수일 수 없다 (이슈 #195).
     *
     * <p>상한은 두지 않는다 — 배정액이 목표 금액을 넘어도 거절하지 않는다. 계산이
     * {@code max(T - H, 0)} 으로 흡수하고 {@code TARGET_ALREADY_MET} 경고를 내는 쪽이 사용자에게
     * 더 정확하다. 배정 합계가 실제 보유 외화를 넘는지는 계획 계산 시점에
     * {@code PlanAllocationGuard} 가 다른 목표의 배정액까지 합쳐 본다.
     */
    private void requireNonNegativeAllocation(double allocatedHoldingAmount) {
        if (allocatedHoldingAmount < 0) {
            throw new InvalidRequestException(
                    "배정할 보유 외화는 0 이상이어야 합니다.", FIELD_ALLOCATED_HOLDING_AMOUNT);
        }
    }

    /** 주기 코드는 {@link Cadence} 가 아는 값이어야 한다. 비어 있으면 기본값이 채운다. */
    private void requireKnownCadence(String code, String field) {
        if (code == null || code.isBlank()) {
            return;
        }
        try {
            Cadence.from(code);
        } catch (IllegalArgumentException e) {
            throw new InvalidRequestException("지원하지 않는 주기입니다: " + code, field);
        }
    }

    /**
     * 우선 조건은 명세가 정한 세 값 중 하나여야 한다 (명세 §5.1).
     *
     * <p>{@code PlanScenarioService} 가 {@code switch} 로 문자열을 정확히 비교하므로, 대소문자만
     * 다른 값을 저장하면 조용히 금액 우선으로 떨어진다. 여기서 걸러 두고 저장은
     * {@link #resolvePriorityConstraint} 가 소문자 상수로 정규화한다.
     */
    private void requireKnownPriorityConstraint(String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        if (canonicalPriorityConstraint(value) == null) {
            throw new InvalidRequestException(
                    "지원하지 않는 우선 조건입니다: " + value, FIELD_PRIORITY_CONSTRAINT);
        }
    }

    /**
     * 정기형은 시작일과 점검 기간이 있어야 계획을 계산할 수 있다 (명세 §5.3).
     *
     * <p>없이 저장하면 목표는 만들어지는데 {@code POST /goals/&#123;id&#125;/plans} 가 400 을 내는
     * 목표가 남는다. 저장 시점에 막아 그 상태 자체를 만들지 않는다.
     */
    private void requireRecurringPlannerFields(GoalCreateCommand command) {
        if (!command.isRecurring()) {
            return;
        }
        if (command.startDate() == null) {
            throw new InvalidRequestException("첫 계획 시작일을 입력해 주세요.", FIELD_START_DATE);
        }
        if (command.reviewHorizonMonths() == null || command.reviewHorizonMonths() < 1) {
            throw new InvalidRequestException(
                    "점검 기간은 1개월 이상이어야 합니다.", FIELD_REVIEW_HORIZON_MONTHS);
        }
        requireKnownCadence(command.recurInterval(), FIELD_PREFERRED_CADENCE);
    }

    /**
     * 준비 주기 기본값 (명세 §5.2).
     *
     * <p>정기형은 반복 주기가 곧 준비 주기다 — 따로 받을 이유가 없고, 둘이 다르면
     * {@code PlanInput.from} 이 어느 쪽을 쓸지 사용자가 알 수 없다.
     */
    private static String resolvePreferredCadence(GoalCreateCommand command) {
        if (command.preferredCadence() != null && !command.preferredCadence().isBlank()) {
            return command.preferredCadence();
        }
        return command.isRecurring() ? command.recurInterval() : DEFAULT_DEADLINE_CADENCE;
    }

    /**
     * 우선 조건 기본값 (명세 §5.1·§17).
     *
     * <p>유형마다 다르다 — 마감형은 금액, 정기형은 예산이다. {@code V16__planner_expand.sql} 도
     * 기존 정기형 행을 {@code budget} 으로 백필했다. 빌더 기본값 하나로는 그 규칙을 지킬 수 없어
     * 유형과 무관하게 {@code amount} 가 됐다.
     */
    private static String resolvePriorityConstraint(GoalCreateCommand command) {
        String canonical = canonicalPriorityConstraint(command.priorityConstraint());
        if (canonical != null) {
            return canonical;
        }
        return command.isRecurring() ? PriorityConstraint.BUDGET : PriorityConstraint.AMOUNT;
    }

    /** 아는 우선 조건이면 소문자 상수로, 모르는 값이거나 비어 있으면 {@code null} 로 돌려준다. */
    private static String canonicalPriorityConstraint(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return switch (value.toLowerCase(Locale.ROOT)) {
            case PriorityConstraint.AMOUNT -> PriorityConstraint.AMOUNT;
            case PriorityConstraint.DATE -> PriorityConstraint.DATE;
            case PriorityConstraint.BUDGET -> PriorityConstraint.BUDGET;
            default -> null;
        };
    }

    /** 이름을 빈 문자열·공백으로 바꾸는 수정은 막는다. */
    private void requireNonBlankName(String name) {
        if (name.isBlank()) {
            throw new InvalidRequestException("목표 이름은 공백일 수 없습니다.", FIELD_NAME);
        }
    }
}
