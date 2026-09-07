package com.divurve.domain.plan;

import static java.util.Objects.requireNonNull;

import com.divurve.common.architecture.UseCase;
import com.divurve.common.exception.InvalidRequestException;
import com.divurve.common.exception.NotFoundException;
import com.divurve.domain.goal.PriorityConstraint;
import com.divurve.domain.goal.entity.Goal;
import com.divurve.domain.plan.entity.Plan;
import com.divurve.domain.plan.entity.PlanCalculationMeta;
import com.divurve.domain.plan.entity.PlanStep;
import com.divurve.engine.planner.AdjustmentOption;
import com.divurve.engine.planner.AdjustmentOptionSelector;
import com.divurve.engine.planner.ExchangeCostCalculator;
import com.divurve.engine.planner.PriorityDimension;
import com.divurve.engine.planner.SkipRedistribution;
import com.divurve.engine.planner.SkipRedistributor;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

/**
 * 회차 실행 — 완료 기록과 건너뛰기 미리보기 (플래너 명세 §14·§15).
 *
 * <p>두 동작의 성격이 다르다.
 * <ul>
 *   <li><b>완료는 즉시 반영한다</b> — 이미 일어난 사실의 기록이다. 다만 같은 요청이 두 번
 *       전송돼도 두 번 반영되지 않아야 한다 (명세 §14·§21-12).</li>
 *   <li><b>건너뛰기는 반영하지 않는다</b> — 명세 §15 는 계획을 즉시 덮어쓰지 말고 변경 계획을
 *       계산해 사용자 승인을 받으라고 규정한다. 남은 회차의 부담이 늘어나는 것은 사용자가
 *       받아들일지 결정할 문제이지 시스템이 대신 정할 일이 아니다.</li>
 * </ul>
 */
@UseCase
public class PlanStepExecutionService {

    private final PlanRepository planRepository;
    private final PlanStepRepository planStepRepository;
    private final SkipRedistributor skipRedistributor;
    private final ExchangeCostCalculator exchangeCostCalculator;
    private final AdjustmentOptionSelector adjustmentOptionSelector;
    private final Clock clock;

    public PlanStepExecutionService(
            PlanRepository planRepository,
            PlanStepRepository planStepRepository,
            SkipRedistributor skipRedistributor,
            ExchangeCostCalculator exchangeCostCalculator,
            AdjustmentOptionSelector adjustmentOptionSelector,
            Clock clock) {
        this.planRepository = requireNonNull(planRepository, "planRepository");
        this.planStepRepository = requireNonNull(planStepRepository, "planStepRepository");
        this.skipRedistributor = requireNonNull(skipRedistributor, "skipRedistributor");
        this.exchangeCostCalculator = requireNonNull(exchangeCostCalculator, "exchangeCostCalculator");
        this.adjustmentOptionSelector =
                requireNonNull(adjustmentOptionSelector, "adjustmentOptionSelector");
        this.clock = requireNonNull(clock, "clock");
    }

    /**
     * 회차 완료를 기록한다 (명세 §14).
     *
     * <p>{@code executionKey} 가 이미 반영된 키라면 <b>아무것도 저장하지 않고</b> 그때의 결과를
     * 그대로 돌려준다. 네트워크 재시도로 같은 요청이 두 번 도착해도 남은 금액이 두 번 줄지
     * 않는다 (§21-12).
     *
     * @param planId         계획 ID
     * @param seq            회차 번호
     * @param targetAmount   목표 외화 금액 (남은 금액 계산용)
     * @param executedAmount 실제 확보한 외화 금액
     * @param executedRate   실제 적용된 환율
     * @param executedDate   실행 날짜. {@code null} 이면 오늘
     * @param executionKey   멱등 키. {@code null} 이면 멱등 보장이 없다
     * @return 완료 결과
     * @throws NotFoundException       회차를 찾을 수 없는 경우
     * @throws InvalidRequestException 이미 완료·건너뛴 회차인 경우
     */
    @Transactional
    public CompleteResult completeStep(
            UUID planId,
            int seq,
            double targetAmount,
            double executedAmount,
            Double executedRate,
            LocalDate executedDate,
            String executionKey) {
        Optional<PlanStep> alreadyApplied = findAlreadyApplied(executionKey);
        if (alreadyApplied.isPresent()) {
            return toResult(planId, targetAmount, alreadyApplied.get(), true);
        }

        PlanStep step = requireStep(planId, seq);
        markCompleted(
                step,
                executedAmount,
                executedRate,
                executedDate == null ? LocalDate.now(clock) : executedDate,
                executionKey);
        planStepRepository.save(step);

        return toResult(planId, targetAmount, step, false);
    }

    /**
     * 회차를 건너뛰면 계획이 어떻게 바뀌는지 계산한다 (명세 §15).
     *
     * <p><b>아무것도 저장하지 않는다.</b> 활성 계획은 그대로이며(§21-9), 적용은 사용자가 승인한
     * 뒤 새 버전으로 이뤄진다(§18).
     *
     * @param planId 계획 ID
     * @param seq    건너뛸 회차 번호
     * @param goal   목표 (목표 금액·배정 외화·회차 예산)
     * @return 변경 계획 미리보기
     * @throws NotFoundException       회차를 찾을 수 없는 경우
     * @throws InvalidRequestException 이미 완료·건너뛴 회차인 경우
     */
    @Transactional(readOnly = true)
    public SkipPreview previewSkip(UUID planId, int seq, Goal goal) {
        Objects.requireNonNull(goal, "goal");
        Plan plan = requirePlan(planId);
        PlanStep target = requireStepOf(planId, seq);
        if (!target.isOpen()) {
            throw new InvalidRequestException(
                    "이미 " + target.getStatus() + " 상태인 회차는 건너뛸 수 없습니다: seq=" + seq, "seq");
        }

        List<PlanStep> steps = planStepRepository.findByPlan_IdOrderBySeqAsc(planId);
        double executed = steps.stream().mapToDouble(PlanStep::getExecutedAmount).sum();
        double held = goal.getAllocatedHoldingAmount() + executed;

        // 건너뛸 회차를 뺀 나머지 미실행 회차가 부담을 나눠 갖는다.
        int remainingRounds = (int) steps.stream()
                .filter(PlanStep::isOpen)
                .filter(step -> step.getSeq() != seq)
                .count();

        SkipRedistribution redistribution = skipRedistributor.redistribute(
                BigDecimal.valueOf(goal.getTargetAmount()),
                BigDecimal.valueOf(held),
                remainingRounds,
                minorUnitsOf(goal));

        Long perRoundCostKrw = perRoundCostKrw(plan, redistribution.perRoundAmount());
        boolean overBudget = exceedsBudget(goal, perRoundCostKrw);

        return new SkipPreview(
                seq,
                target.getAmount(),
                redistribution.perRoundAmount().doubleValue(),
                redistribution.newRemainingAmount().doubleValue(),
                remainingRounds,
                remainingRounds == 0,
                perRoundCostKrw,
                overBudget,
                adjustmentOptions(goal, remainingRounds == 0 || overBudget));
    }

    /**
     * 재분배 후 회차 하나에 드는 원화 (명세 §9.3).
     *
     * <p>계획이 저장될 때 쓴 환율·스프레드·수수료를 그대로 쓴다 — 지금 환율을 다시 조회하면
     * 건너뛰기 미리보기가 <b>건너뛰기 때문이 아닌</b> 변화까지 함께 보여주게 된다. 사용자가
     * 묻는 것은 "이 회차를 건너뛰면"이지 "지금 환율이면"이 아니다.
     *
     * <p>계산 메타데이터가 없는 옛 계획은 {@code null} 을 낸다 — 없는 값으로 비용을 지어내지 않는다.
     */
    private Long perRoundCostKrw(Plan plan, BigDecimal perRoundAmount) {
        PlanCalculationMeta meta = plan.getCalculationMeta();
        if (meta == null || meta.getBaseRate() == null
                || meta.getSpreadRatio() == null || meta.getFeeKrw() == null) {
            return null;
        }
        return exchangeCostCalculator.cost(
                perRoundAmount,
                BigDecimal.valueOf(meta.getBaseRate()),
                meta.getSpreadRatio(),
                meta.getFeeKrw());
    }

    /**
     * 재분배된 회차가 사용자가 정한 예산을 넘는지 (명세 §15).
     *
     * <p>예산을 입력하지 않았거나 비용을 계산할 수 없으면 <b>넘지 않는다고 보지 않고 판정 자체를
     * 하지 않는다</b> — 모르는 것을 "괜찮다"로 바꾸면 조정이 필요한 사용자에게 선택지가 가지 않는다.
     */
    private static boolean exceedsBudget(Goal goal, Long perRoundCostKrw) {
        return perRoundCostKrw != null && goal.getBudgetAmount() > 0
                && perRoundCostKrw > goal.getBudgetAmount();
    }

    /** 조정이 필요할 때만 선택지를 낸다. 필요 없는데 띄우면 사용자는 무언가 잘못됐다고 읽는다. */
    private List<String> adjustmentOptions(Goal goal, boolean needed) {
        if (!needed) {
            return List.of();
        }
        return adjustmentOptionSelector.orderedFor(priorityDimensionOf(goal)).stream()
                .map(AdjustmentOption::name)
                .toList();
    }

    /** domain 문자열 상수를 engine 열거로 옮긴다. 모르는 값은 명세 기본값인 금액 우선으로 본다. */
    private static PriorityDimension priorityDimensionOf(Goal goal) {
        return switch (String.valueOf(goal.getPriorityConstraint())) {
            case PriorityConstraint.DATE -> PriorityDimension.DATE;
            case PriorityConstraint.BUDGET -> PriorityDimension.BUDGET;
            default -> PriorityDimension.AMOUNT;
        };
    }

    private Optional<PlanStep> findAlreadyApplied(String executionKey) {
        return executionKey == null || executionKey.isBlank()
                ? Optional.empty()
                : planStepRepository.findByExecutionKey(executionKey);
    }

    private PlanStep requireStep(UUID planId, int seq) {
        requirePlan(planId);
        return requireStepOf(planId, seq);
    }

    private Plan requirePlan(UUID planId) {
        return planRepository.findById(planId)
                .orElseThrow(() -> new NotFoundException("계획을 찾을 수 없습니다: " + planId));
    }

    private PlanStep requireStepOf(UUID planId, int seq) {
        return planStepRepository.findByPlan_IdAndSeq(planId, seq)
                .orElseThrow(() -> new NotFoundException("회차를 찾을 수 없습니다: seq=" + seq));
    }

    private void markCompleted(
            PlanStep step, double executedAmount, Double executedRate, LocalDate executedDate, String key) {
        try {
            step.markAsCompleted(executedAmount, executedRate, executedDate, key);
        } catch (IllegalStateException e) {
            throw new InvalidRequestException(e.getMessage(), "seq");
        }
    }

    /**
     * 완료 후의 남은 금액과 다음 행동을 다시 센다 (명세 §14).
     *
     * <pre>
     * newHeldAmount      = 지금까지 실행한 금액의 합
     * newRemainingAmount = max(targetAmount - newHeldAmount, 0)
     * </pre>
     *
     * <p>합을 다시 세는 이유는 <b>멱등 재요청에서도 같은 값이 나와야</b> 하기 때문이다.
     * 이전 값에 이번 실행액을 더하는 방식이면 재요청이 두 번 더한다.
     */
    private CompleteResult toResult(UUID planId, double targetAmount, PlanStep step, boolean alreadyApplied) {
        List<PlanStep> steps = planStepRepository.findByPlan_IdOrderBySeqAsc(planId);
        double executed = steps.stream().mapToDouble(PlanStep::getExecutedAmount).sum();
        double remaining = Math.max(targetAmount - executed, 0.0);
        Integer nextActionSeq = steps.stream()
                .filter(PlanStep::isOpen)
                .map(PlanStep::getSeq)
                .min(Integer::compareTo)
                .orElse(null);

        return new CompleteResult(
                step.getSeq(),
                step.getStatus(),
                step.getExecutedAmount(),
                step.getExecutedRate(),
                step.getExecutedDate(),
                remaining,
                nextActionSeq,
                alreadyApplied);
    }

    /** JPY 는 소수 자릿수가 0 이다. 통화 표시 규칙은 CurrencyMaster 가 갖는다. */
    private int minorUnitsOf(Goal goal) {
        return com.divurve.domain.master.CurrencyMaster.all().stream()
                .filter(currency -> currency.currencyCode().equals(goal.getCurrencyCode()))
                .mapToInt(com.divurve.domain.master.CurrencyMaster.Currency::minorUnits)
                .findFirst()
                .orElse(2);
    }

    /**
     * 회차 완료 결과 (명세 §14).
     *
     * @param seq             회차 번호
     * @param status          회차 상태
     * @param executedAmount  실행한 외화 금액
     * @param executedRate    실행 환율
     * @param executedDate    실행일
     * @param remainingAmount 남은 외화 금액
     * @param nextActionSeq   다음 행동 회차 번호. 남은 회차가 없으면 {@code null}
     * @param alreadyApplied  이미 반영된 요청의 재전송이었는지 (§21-12)
     */
    public record CompleteResult(
            int seq,
            String status,
            double executedAmount,
            Double executedRate,
            LocalDate executedDate,
            double remainingAmount,
            Integer nextActionSeq,
            boolean alreadyApplied) {
    }

    /**
     * 건너뛰기 변경 계획 미리보기 (명세 §15). <b>저장되지 않았다.</b>
     *
     * @param seq                 건너뛸 회차 번호
     * @param amountBefore        건너뛰기 전 회차 금액
     * @param amountAfter         재분배 후 남은 회차당 금액
     * @param remainingAmount     재분배 후 남은 외화
     * @param remainingRounds     재분배를 받을 회차 수
     * @param exhausted           남은 회차가 없어 재분배할 곳이 없는지 — 조정이 불가피하다 (§21-8)
     * @param perRoundCostKrw     재분배 후 회차당 예상 원화. 계산 근거가 없으면 {@code null}
     * @param exceedsBudget       재분배된 회차가 사용자의 예산을 넘는지 (§15)
     * @param adjustmentOptions   조정 선택지 (§15·§17). 조정이 필요 없으면 빈 목록
     */
    public record SkipPreview(
            int seq,
            double amountBefore,
            double amountAfter,
            double remainingAmount,
            int remainingRounds,
            boolean exhausted,
            Long perRoundCostKrw,
            boolean exceedsBudget,
            List<String> adjustmentOptions) {

        public SkipPreview {
            adjustmentOptions = List.copyOf(Objects.requireNonNull(adjustmentOptions, "adjustmentOptions"));
        }
    }
}
