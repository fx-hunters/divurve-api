package com.divurve.domain.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.divurve.common.exception.InvalidRequestException;
import com.divurve.common.exception.NotFoundException;
import com.divurve.domain.goal.PriorityConstraint;
import com.divurve.domain.goal.entity.Goal;
import com.divurve.domain.plan.entity.Plan;
import com.divurve.domain.plan.entity.PlanCalculationMeta;
import com.divurve.domain.plan.entity.PlanStep;
import com.divurve.domain.user.entity.User;
import com.divurve.engine.planner.AdjustmentOptionSelector;
import com.divurve.engine.planner.EqualSplitAllocator;
import com.divurve.engine.planner.ExchangeCostCalculator;
import com.divurve.engine.planner.SkipRedistributor;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * {@link PlanStepExecutionService} — 회차 완료와 건너뛰기 (플래너 명세 §14·§15).
 *
 * <p>두 동작의 성격 차이가 검증의 핵심이다 — <b>완료는 즉시 반영하되 중복은 막고</b>(§21-12),
 * <b>건너뛰기는 아무것도 저장하지 않는다</b>(§15·§21-9).
 */
@DisplayName("PlanStepExecutionService")
class PlanStepExecutionServiceTest {

    private static final UUID PLAN_ID = UUID.randomUUID();
    private static final LocalDate TODAY = LocalDate.of(2026, 9, 7);
    private static final Clock CLOCK =
            Clock.fixed(TODAY.atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC);

    private PlanRepository planRepository;
    private PlanStepRepository planStepRepository;
    private PlanStepExecutionService service;
    private Plan plan;
    private Goal goal;

    @BeforeEach
    void setUp() {
        planRepository = mock(PlanRepository.class);
        planStepRepository = mock(PlanStepRepository.class);
        service = new PlanStepExecutionService(
                planRepository, planStepRepository,
                new SkipRedistributor(new EqualSplitAllocator()),
                new ExchangeCostCalculator(), new AdjustmentOptionSelector(), CLOCK);

        User owner = User.createDemo("a@b.com", "사용자");
        goal = Goal.builder(owner, "여행 자금", "onetime", "travel", "USD")
                .targetAmount(4000.0)
                .allocatedHoldingAmount(0.0)
                .build();
        plan = Plan.builder(goal, 1).status(PlanStatus.ACTIVE).build();
        when(planRepository.findById(PLAN_ID)).thenReturn(Optional.of(plan));
    }

    private PlanStep step(int seq, double amount, String status) {
        return PlanStep.create(plan, seq, TODAY.plusWeeks(seq - 1L), amount, 0.0, status);
    }

    private PlanStep completedStep(int seq, double executed) {
        PlanStep step = step(seq, executed, PlanStepStatus.SCHEDULED);
        step.markAsCompleted(executed, 1350.0, TODAY, "key-" + seq);
        return step;
    }

    @Nested
    @DisplayName("회차 완료 (명세 §14)")
    class Complete {

        @Test
        @DisplayName("실행 금액·환율·실행일을 기록한다")
        void recordsExecution() {
            PlanStep target = step(1, 1000.0, PlanStepStatus.SCHEDULED);
            when(planStepRepository.findByPlan_IdAndSeq(PLAN_ID, 1)).thenReturn(Optional.of(target));
            when(planStepRepository.findByPlan_IdOrderBySeqAsc(PLAN_ID)).thenReturn(List.of(target));

            var result = service.completeStep(
                    PLAN_ID, 1, 4000.0, 1000.0, 1348.5, LocalDate.of(2026, 9, 8), "key-1");

            assertThat(result.status()).isEqualTo(PlanStepStatus.COMPLETED);
            assertThat(result.executedAmount()).isEqualTo(1000.0);
            assertThat(result.executedRate()).isEqualTo(1348.5);
            assertThat(result.executedDate()).isEqualTo(LocalDate.of(2026, 9, 8));
            assertThat(result.alreadyApplied()).isFalse();
        }

        @Test
        @DisplayName("남은 금액을 다시 센다 — 명세 §14")
        void recalculatesRemaining() {
            PlanStep first = completedStep(1, 1000.0);
            PlanStep second = step(2, 1000.0, PlanStepStatus.SCHEDULED);
            when(planStepRepository.findByPlan_IdAndSeq(PLAN_ID, 2)).thenReturn(Optional.of(second));
            when(planStepRepository.findByPlan_IdOrderBySeqAsc(PLAN_ID))
                    .thenReturn(List.of(first, second));

            var result = service.completeStep(PLAN_ID, 2, 4000.0, 1000.0, 1350.0, TODAY, "key-2");

            assertThat(result.remainingAmount()).isEqualTo(2000.0);
        }

        @Test
        @DisplayName("실행일을 주지 않으면 오늘로 기록한다")
        void defaultsExecutedDateToToday() {
            PlanStep target = step(1, 1000.0, PlanStepStatus.SCHEDULED);
            when(planStepRepository.findByPlan_IdAndSeq(PLAN_ID, 1)).thenReturn(Optional.of(target));
            when(planStepRepository.findByPlan_IdOrderBySeqAsc(PLAN_ID)).thenReturn(List.of(target));

            var result = service.completeStep(PLAN_ID, 1, 4000.0, 1000.0, 1350.0, null, "key-1");

            assertThat(result.executedDate()).isEqualTo(TODAY);
        }

        @Test
        @DisplayName("같은 멱등 키의 재요청은 저장하지 않고 첫 결과를 돌려준다 — 불변조건 §21-12")
        void idempotentRetry() {
            PlanStep applied = completedStep(1, 1000.0);
            when(planStepRepository.findByExecutionKey("key-1")).thenReturn(Optional.of(applied));
            when(planStepRepository.findByPlan_IdOrderBySeqAsc(PLAN_ID)).thenReturn(List.of(applied));

            var result = service.completeStep(PLAN_ID, 1, 4000.0, 1000.0, 1350.0, TODAY, "key-1");

            assertThat(result.alreadyApplied()).isTrue();
            assertThat(result.remainingAmount()).isEqualTo(3000.0);
            // 저장도, 회차 조회도 하지 않는다 — 이미 반영된 요청이다
            verify(planStepRepository, never()).save(any());
            verify(planStepRepository, never()).findByPlan_IdAndSeq(any(), org.mockito.ArgumentMatchers.anyInt());
        }

        @Test
        @DisplayName("멱등 키가 없으면 중복 방어가 없다 — 매번 새로 처리한다")
        void withoutKey_NoIdempotency() {
            PlanStep target = step(1, 1000.0, PlanStepStatus.SCHEDULED);
            when(planStepRepository.findByPlan_IdAndSeq(PLAN_ID, 1)).thenReturn(Optional.of(target));
            when(planStepRepository.findByPlan_IdOrderBySeqAsc(PLAN_ID)).thenReturn(List.of(target));

            var result = service.completeStep(PLAN_ID, 1, 4000.0, 1000.0, 1350.0, TODAY, null);

            assertThat(result.alreadyApplied()).isFalse();
            verify(planStepRepository, never()).findByExecutionKey(any());
        }

        @Test
        @DisplayName("빈 멱등 키도 없는 것으로 본다")
        void blankKey_IsTreatedAsAbsent() {
            PlanStep target = step(1, 1000.0, PlanStepStatus.SCHEDULED);
            when(planStepRepository.findByPlan_IdAndSeq(PLAN_ID, 1)).thenReturn(Optional.of(target));
            when(planStepRepository.findByPlan_IdOrderBySeqAsc(PLAN_ID)).thenReturn(List.of(target));

            assertThat(service.completeStep(PLAN_ID, 1, 4000.0, 1000.0, 1350.0, TODAY, " ")
                    .alreadyApplied()).isFalse();
        }

        @Test
        @DisplayName("다음 행동은 가장 가까운 미완료 회차다 — 명세 §11.3")
        void nextActionIsNearestOpenStep() {
            PlanStep first = completedStep(1, 1000.0);
            PlanStep second = step(2, 1000.0, PlanStepStatus.SCHEDULED);
            PlanStep third = step(3, 1000.0, PlanStepStatus.SCHEDULED);
            when(planStepRepository.findByPlan_IdAndSeq(PLAN_ID, 2)).thenReturn(Optional.of(second));
            when(planStepRepository.findByPlan_IdOrderBySeqAsc(PLAN_ID))
                    .thenReturn(List.of(first, second, third));

            var result = service.completeStep(PLAN_ID, 2, 4000.0, 1000.0, 1350.0, TODAY, "key-2");

            assertThat(result.nextActionSeq()).isEqualTo(3);
        }

        @Test
        @DisplayName("남은 회차가 없으면 다음 행동도 없다")
        void noNextActionWhenAllDone() {
            PlanStep only = step(1, 1000.0, PlanStepStatus.SCHEDULED);
            when(planStepRepository.findByPlan_IdAndSeq(PLAN_ID, 1)).thenReturn(Optional.of(only));
            when(planStepRepository.findByPlan_IdOrderBySeqAsc(PLAN_ID)).thenReturn(List.of(only));

            assertThat(service.completeStep(PLAN_ID, 1, 1000.0, 1000.0, 1350.0, TODAY, "k")
                    .nextActionSeq()).isNull();
        }

        @Test
        @DisplayName("이미 완료한 회차는 다시 완료할 수 없다")
        void alreadyCompleted_Throws() {
            PlanStep done = completedStep(1, 1000.0);
            when(planStepRepository.findByPlan_IdAndSeq(PLAN_ID, 1)).thenReturn(Optional.of(done));

            assertThatThrownBy(() -> service.completeStep(
                    PLAN_ID, 1, 4000.0, 1000.0, 1350.0, TODAY, "other-key"))
                    .isInstanceOf(InvalidRequestException.class)
                    .hasFieldOrPropertyWithValue("field", "seq");
        }

        @Test
        @DisplayName("목표를 넘겨 확보해도 남은 금액은 0 아래로 내려가지 않는다")
        void remainingNeverNegative() {
            PlanStep target = step(1, 1000.0, PlanStepStatus.SCHEDULED);
            when(planStepRepository.findByPlan_IdAndSeq(PLAN_ID, 1)).thenReturn(Optional.of(target));
            when(planStepRepository.findByPlan_IdOrderBySeqAsc(PLAN_ID)).thenReturn(List.of(target));

            assertThat(service.completeStep(PLAN_ID, 1, 1000.0, 5000.0, 1350.0, TODAY, "k")
                    .remainingAmount()).isZero();
        }

        @Test
        @DisplayName("없는 계획·회차는 404 다")
        void notFound() {
            when(planRepository.findById(PLAN_ID)).thenReturn(Optional.empty());
            assertThatThrownBy(() -> service.completeStep(
                    PLAN_ID, 1, 4000.0, 1000.0, 1350.0, TODAY, "k"))
                    .isInstanceOf(NotFoundException.class);

            when(planRepository.findById(PLAN_ID)).thenReturn(Optional.of(plan));
            when(planStepRepository.findByPlan_IdAndSeq(PLAN_ID, 9)).thenReturn(Optional.empty());
            assertThatThrownBy(() -> service.completeStep(
                    PLAN_ID, 9, 4000.0, 1000.0, 1350.0, TODAY, "k"))
                    .isInstanceOf(NotFoundException.class);
        }
    }

    @Nested
    @DisplayName("건너뛰기 미리보기 (명세 §15)")
    class Skip {

        @Test
        @DisplayName("아무것도 저장하지 않는다 — 불변조건 §21-9")
        void savesNothing() {
            PlanStep target = step(2, 1000.0, PlanStepStatus.SCHEDULED);
            when(planStepRepository.findByPlan_IdAndSeq(PLAN_ID, 2)).thenReturn(Optional.of(target));
            when(planStepRepository.findByPlan_IdOrderBySeqAsc(PLAN_ID)).thenReturn(List.of(
                    completedStep(1, 1000.0), target,
                    step(3, 1000.0, PlanStepStatus.SCHEDULED),
                    step(4, 1000.0, PlanStepStatus.SCHEDULED)));

            var preview = service.previewSkip(PLAN_ID, 2, goal);

            assertThat(preview.seq()).isEqualTo(2);
            assertThat(target.getStatus()).isEqualTo(PlanStepStatus.SCHEDULED);
            verify(planStepRepository, never()).save(any());
        }

        @Test
        @DisplayName("남은 회차에 부담을 재분배한다 — 명세 §15")
        void redistributesToRemaining() {
            PlanStep target = step(2, 1000.0, PlanStepStatus.SCHEDULED);
            when(planStepRepository.findByPlan_IdAndSeq(PLAN_ID, 2)).thenReturn(Optional.of(target));
            when(planStepRepository.findByPlan_IdOrderBySeqAsc(PLAN_ID)).thenReturn(List.of(
                    completedStep(1, 1000.0), target,
                    step(3, 1000.0, PlanStepStatus.SCHEDULED),
                    step(4, 1000.0, PlanStepStatus.SCHEDULED)));

            var preview = service.previewSkip(PLAN_ID, 2, goal);

            // 목표 4000 중 1000 확보 → 남은 3000 을 회차 3·4 두 번에 나눈다
            assertThat(preview.remainingAmount()).isEqualTo(3000.0);
            assertThat(preview.remainingRounds()).isEqualTo(2);
            assertThat(preview.amountAfter()).isEqualTo(1500.0);
            assertThat(preview.amountBefore()).isEqualTo(1000.0);
            assertThat(preview.exhausted()).isFalse();
        }

        @Test
        @DisplayName("마지막 회차를 건너뛰면 재분배할 곳이 없다 — 조정이 불가피하다 (§21-8)")
        void lastStepSkip_IsExhausted() {
            PlanStep target = step(2, 1000.0, PlanStepStatus.SCHEDULED);
            when(planStepRepository.findByPlan_IdAndSeq(PLAN_ID, 2)).thenReturn(Optional.of(target));
            when(planStepRepository.findByPlan_IdOrderBySeqAsc(PLAN_ID))
                    .thenReturn(List.of(completedStep(1, 1000.0), target));

            var preview = service.previewSkip(PLAN_ID, 2, goal);

            assertThat(preview.exhausted()).isTrue();
            assertThat(preview.remainingRounds()).isZero();
            assertThat(preview.amountAfter()).isZero();
            // 남은 금액은 그대로 드러낸다 — 부족을 숨기지 않는다
            assertThat(preview.remainingAmount()).isEqualTo(3000.0);
        }

        @Test
        @DisplayName("배정한 보유 외화도 확보액에 포함한다")
        void countsAllocatedHolding() {
            Goal withHolding = Goal.builder(
                            User.createDemo("a@b.com", "사용자"), "목표", "onetime", "travel", "USD")
                    .targetAmount(4000.0)
                    .allocatedHoldingAmount(1000.0)
                    .build();
            PlanStep target = step(1, 1000.0, PlanStepStatus.SCHEDULED);
            when(planStepRepository.findByPlan_IdAndSeq(PLAN_ID, 1)).thenReturn(Optional.of(target));
            when(planStepRepository.findByPlan_IdOrderBySeqAsc(PLAN_ID)).thenReturn(List.of(
                    target, step(2, 1000.0, PlanStepStatus.SCHEDULED)));

            var preview = service.previewSkip(PLAN_ID, 1, withHolding);

            assertThat(preview.remainingAmount()).isEqualTo(3000.0);
        }

        @Test
        @DisplayName("이미 완료·건너뛴 회차는 건너뛸 수 없다")
        void notOpen_Throws() {
            PlanStep done = completedStep(1, 1000.0);
            when(planStepRepository.findByPlan_IdAndSeq(PLAN_ID, 1)).thenReturn(Optional.of(done));

            assertThatThrownBy(() -> service.previewSkip(PLAN_ID, 1, goal))
                    .isInstanceOf(InvalidRequestException.class)
                    .hasFieldOrPropertyWithValue("field", "seq");
        }

        @Test
        @DisplayName("목표가 null 이면 거부한다")
        void nullGoal_Throws() {
            assertThatThrownBy(() -> service.previewSkip(PLAN_ID, 1, null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("JPY 는 정수 단위로 재분배한다 — 명세 §21-6")
        void jpyUsesWholeUnits() {
            Goal jpyGoal = Goal.builder(
                            User.createDemo("a@b.com", "사용자"), "목표", "onetime", "travel", "JPY")
                    .targetAmount(100000.0)
                    .build();
            PlanStep target = step(1, 50000.0, PlanStepStatus.SCHEDULED);
            when(planStepRepository.findByPlan_IdAndSeq(PLAN_ID, 1)).thenReturn(Optional.of(target));
            when(planStepRepository.findByPlan_IdOrderBySeqAsc(PLAN_ID)).thenReturn(List.of(
                    target,
                    step(2, 50000.0, PlanStepStatus.SCHEDULED),
                    step(3, 50000.0, PlanStepStatus.SCHEDULED)));

            var preview = service.previewSkip(PLAN_ID, 1, jpyGoal);

            assertThat(preview.amountAfter()).isEqualTo(50000.0);
        }
    }

    @Nested
    @DisplayName("건너뛰기 후 예산 초과와 조정 선택지 (명세 §15·§17)")
    class SkipAdjustments {

        private Plan planWithMeta() {
            Plan withMeta = Plan.builder(goal, 1)
                    .status(PlanStatus.ACTIVE)
                    .calculationMeta(PlanCalculationMeta.builder("v1")
                            .rates(1300.0, 1350.0, 1400.0)
                            .spreadRatio(0.01)
                            .feeKrw(10_000L)
                            .quoteUnit(1)
                            .build())
                    .build();
            when(planRepository.findById(PLAN_ID)).thenReturn(Optional.of(withMeta));
            return withMeta;
        }

        private PlanStepExecutionService.SkipPreview previewWith(PlanStep... steps) {
            when(planStepRepository.findByPlan_IdAndSeq(PLAN_ID, 1))
                    .thenReturn(Optional.of(steps[0]));
            when(planStepRepository.findByPlan_IdOrderBySeqAsc(PLAN_ID)).thenReturn(List.of(steps));
            return service.previewSkip(PLAN_ID, 1, goal);
        }

        @Test
        @DisplayName("계산 근거가 없는 옛 계획은 회차 비용을 지어내지 않는다")
        void noCalculationMeta_LeavesCostNull() {
            var preview = previewWith(step(1, 1000.0, PlanStepStatus.SCHEDULED),
                    step(2, 1000.0, PlanStepStatus.SCHEDULED));

            assertThat(preview.perRoundCostKrw()).isNull();
            assertThat(preview.exceedsBudget()).isFalse();
            assertThat(preview.adjustmentOptions()).isEmpty();
        }

        /**
         * 계산 근거가 하나라도 비면 비용을 내지 않는다 — 빠진 값을 0 이나 기본값으로 채우면
         * 스프레드·수수료가 없는 것처럼 계산돼 실제보다 싼 금액이 나간다.
         */
        @org.junit.jupiter.params.ParameterizedTest(name = "{0} 가 비면 비용을 내지 않는다")
        @org.junit.jupiter.params.provider.MethodSource(
                "com.divurve.domain.plan.PlanStepExecutionServiceTest#incompleteMeta")
        void partialCalculationMeta_LeavesCostNull(String missing, PlanCalculationMeta meta) {
            Plan partial = Plan.builder(goal, 1)
                    .status(PlanStatus.ACTIVE)
                    .calculationMeta(meta)
                    .build();
            when(planRepository.findById(PLAN_ID)).thenReturn(Optional.of(partial));

            assertThat(previewWith(step(1, 1000.0, PlanStepStatus.SCHEDULED),
                    step(2, 1000.0, PlanStepStatus.SCHEDULED)).perRoundCostKrw()).isNull();
        }

        @Test
        @DisplayName("계획의 환율로 회차 비용을 낸다 — 지금 환율을 다시 조회하지 않는다")
        void usesPlanRate() {
            planWithMeta();
            goal.setBudgetAmount(0L);

            var preview = previewWith(step(1, 1000.0, PlanStepStatus.SCHEDULED),
                    step(2, 1000.0, PlanStepStatus.SCHEDULED));

            // 4000 을 남은 1회차가 떠안는다 → 4000 × 1350 × 1.01 + 10,000
            assertThat(preview.perRoundCostKrw()).isEqualTo(5_464_000L);
            // 예산을 입력하지 않았으면 초과 판정을 하지 않는다.
            assertThat(preview.exceedsBudget()).isFalse();
            assertThat(preview.adjustmentOptions()).isEmpty();
        }

        @Test
        @DisplayName("재분배된 회차가 예산을 넘으면 조정 선택지를 낸다 — 명세 §15")
        void overBudget_OffersOptions() {
            planWithMeta();
            goal.setBudgetAmount(1_000_000L);

            var preview = previewWith(step(1, 1000.0, PlanStepStatus.SCHEDULED),
                    step(2, 1000.0, PlanStepStatus.SCHEDULED));

            assertThat(preview.exceedsBudget()).isTrue();
            assertThat(preview.adjustmentOptions()).containsExactly(
                    "CHANGE_ROUND_BUDGET", "CHANGE_TARGET_DATE", "CHANGE_TARGET_AMOUNT", "PAUSE_PLAN");
        }

        @Test
        @DisplayName("예산 안에 들면 선택지를 내지 않는다")
        void withinBudget_NoOptions() {
            planWithMeta();
            goal.setBudgetAmount(9_000_000L);

            var preview = previewWith(step(1, 1000.0, PlanStepStatus.SCHEDULED),
                    step(2, 1000.0, PlanStepStatus.SCHEDULED));

            assertThat(preview.exceedsBudget()).isFalse();
            assertThat(preview.adjustmentOptions()).isEmpty();
        }

        @Test
        @DisplayName("날짜 우선이면 목표 날짜 변경이 뒤로 간다 — 명세 §17")
        void datePriority_DemotesDateChange() {
            planWithMeta();
            goal.setBudgetAmount(1_000_000L);
            goal.setPriorityConstraint(PriorityConstraint.DATE);

            assertThat(previewWith(step(1, 1000.0, PlanStepStatus.SCHEDULED),
                    step(2, 1000.0, PlanStepStatus.SCHEDULED)).adjustmentOptions())
                    .containsExactly("CHANGE_ROUND_BUDGET", "CHANGE_TARGET_AMOUNT",
                            "CHANGE_TARGET_DATE", "PAUSE_PLAN");
        }

        @Test
        @DisplayName("예산 우선이면 회차 예산 변경이 뒤로 간다 — 명세 §17")
        void budgetPriority_DemotesBudgetChange() {
            planWithMeta();
            goal.setBudgetAmount(1_000_000L);
            goal.setPriorityConstraint(PriorityConstraint.BUDGET);

            assertThat(previewWith(step(1, 1000.0, PlanStepStatus.SCHEDULED),
                    step(2, 1000.0, PlanStepStatus.SCHEDULED)).adjustmentOptions())
                    .containsExactly("CHANGE_TARGET_AMOUNT", "CHANGE_TARGET_DATE",
                            "CHANGE_ROUND_BUDGET", "PAUSE_PLAN");
        }

        @Test
        @DisplayName("선택지 목록 없이는 미리보기를 만들 수 없다")
        void nullOptions_Throw() {
            assertThatThrownBy(() -> new PlanStepExecutionService.SkipPreview(
                    1, 1.0, 1.0, 1.0, 1, false, null, false, null))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    /** 계산 메타데이터에서 값 하나씩을 뺀 조합 — 어느 하나만 비어도 비용을 낼 수 없다. */
    static java.util.stream.Stream<org.junit.jupiter.params.provider.Arguments> incompleteMeta() {
        return java.util.stream.Stream.of(
                org.junit.jupiter.params.provider.Arguments.of("base_rate",
                        PlanCalculationMeta.builder("v1").spreadRatio(0.01).feeKrw(10_000L).build()),
                org.junit.jupiter.params.provider.Arguments.of("spread_ratio",
                        PlanCalculationMeta.builder("v1").rates(null, 1350.0, null)
                                .feeKrw(10_000L).build()),
                org.junit.jupiter.params.provider.Arguments.of("fee_krw",
                        PlanCalculationMeta.builder("v1").rates(null, 1350.0, null)
                                .spreadRatio(0.01).build()));
    }

    @Test
    @DisplayName("의존이 null 이면 생성을 거부한다")
    void nullDependencies_Throw() {
        SkipRedistributor redistributor = new SkipRedistributor(new EqualSplitAllocator());
        ExchangeCostCalculator costs = new ExchangeCostCalculator();
        AdjustmentOptionSelector options = new AdjustmentOptionSelector();
        assertThatThrownBy(() -> new PlanStepExecutionService(
                null, planStepRepository, redistributor, costs, options, CLOCK))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new PlanStepExecutionService(
                planRepository, null, redistributor, costs, options, CLOCK))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new PlanStepExecutionService(
                planRepository, planStepRepository, null, costs, options, CLOCK))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new PlanStepExecutionService(
                planRepository, planStepRepository, redistributor, null, options, CLOCK))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new PlanStepExecutionService(
                planRepository, planStepRepository, redistributor, costs, null, CLOCK))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new PlanStepExecutionService(
                planRepository, planStepRepository, redistributor, costs, options, null))
                .isInstanceOf(NullPointerException.class);
    }
}
