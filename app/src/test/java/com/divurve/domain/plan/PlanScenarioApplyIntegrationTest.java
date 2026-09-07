package com.divurve.domain.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.divurve.common.exception.InvalidRequestException;
import com.divurve.domain.RepositoryTestBase;
import com.divurve.domain.goal.GoalRepository;
import com.divurve.domain.goal.GoalType;
import com.divurve.domain.goal.PriorityConstraint;
import com.divurve.domain.goal.entity.Goal;
import com.divurve.domain.plan.entity.Plan;
import com.divurve.domain.plan.entity.PlanStep;
import com.divurve.domain.user.UserRepository;
import com.divurve.domain.user.entity.User;
import com.divurve.engine.planner.AdjustmentOptionSelector;
import com.divurve.engine.planner.BudgetState;
import com.divurve.engine.planner.EqualSplitAllocator;
import com.divurve.engine.planner.ExchangeCostCalculator;
import com.divurve.engine.planner.SkipRedistributor;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 시나리오 미리보기·적용의 §21 불변조건 실연동 검증 (플래너 명세 §16·§18·§21).
 *
 * <p>인메모리 목으로는 확인할 수 없는 것들이 있어 실제 Postgres 로 돌린다:
 * <ul>
 *   <li>{@code uq_plans_active_per_goal} — 목표당 활성 계획 하나. 적용 순서(이전 것을 먼저 내린다)가
 *       이 인덱스에서 나온다.</li>
 *   <li>{@code uq_plan_steps_plan_seq} — 한 계획 안의 회차 번호 유일. 과거 회차를 복사하면서 새 회차를
 *       뒤로 미는 로직이 실제로 성립하는지는 여기서만 드러난다.</li>
 *   <li>{@code uq_plan_steps_execution_key} — 멱등 키 유일. 복사본이 키를 가져가면 여기서 터진다.</li>
 * </ul>
 */
@DisplayName("시나리오 미리보기·적용 불변조건 (§21)")
class PlanScenarioApplyIntegrationTest extends RepositoryTestBase {

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
    private EntityManager entityManager;

    private PlanCalculationService planCalculationService;
    private PlanConfirmService confirmService;
    private PlanScenarioService scenarioService;
    private PlanApplyService applyService;
    private PlanStepExecutionService executionService;
    private Goal goal;

    @BeforeEach
    void setUp() {
        confirmService = new PlanConfirmService(goalRepository, planRepository, planStepRepository);
        planCalculationService = mock(PlanCalculationService.class);
        scenarioService = new PlanScenarioService(
                planRepository, planStepRepository, planCalculationService,
                confirmService, new AdjustmentOptionSelector());
        applyService = new PlanApplyService(planRepository, planStepRepository, confirmService);
        executionService = new PlanStepExecutionService(
                planRepository, planStepRepository,
                new SkipRedistributor(new EqualSplitAllocator()),
                new ExchangeCostCalculator(), new AdjustmentOptionSelector(), CLOCK);

        User owner = userRepository.save(
                User.createDemo("scenario-" + UUID.randomUUID() + "@divurve.com", "사용자"));
        goal = goalRepository.save(Goal.builder(owner, "여행 자금", "onetime", "travel", "USD")
                .targetAmount(4000.0)
                .allocatedHoldingAmount(0.0)
                .targetDate(LocalDate.of(2026, 12, 24))
                .budgetAmount(1_000_000)
                .goalType(GoalType.DEADLINE)
                .priorityConstraint(PriorityConstraint.AMOUNT)
                .status("active")
                .build());
    }

    // ── 픽스처 ────────────────────────────────────────────────────────────

    private PlanDraft draft(int stepCount, double perStep) {
        Instant asOf = Instant.parse("2026-09-07T00:00:00Z");
        PlanRateContext rates = new PlanRateContext(
                "USD", 1300.0, 1350.0, 1400.0, 0.0175, 3000L, 1, 2, asOf, asOf, true);
        List<PlanDraft.Step> steps = java.util.stream.IntStream.rangeClosed(1, stepCount)
                .mapToObj(seq -> new PlanDraft.Step(
                        seq,
                        TODAY.plusWeeks(seq - 1L),
                        BigDecimal.valueOf(perStep),
                        null,
                        new PlanDraft.CostRange(1_300_000L, 1_350_000L, 1_400_000L),
                        null,
                        BigDecimal.ZERO, null, null,
                        PlanStepStatus.SCHEDULED, seq == 1))
                .toList();
        return new PlanDraft(
                asOf, "v1", rates,
                new PlanDraft.GoalSummary(
                        GoalType.DEADLINE, "travel", "USD", BigDecimal.valueOf(4000.0), null,
                        BigDecimal.ZERO, BigDecimal.valueOf(4000.0), goal.getTargetDate(),
                        PriorityConstraint.AMOUNT),
                new PlanDraft.Summary(
                        PlanStatus.DRAFT, goal.getTargetDate(), stepCount, 0, stepCount, 0, 1,
                        new PlanDraft.CostRange(5_200_000L, 5_400_000L, 5_600_000L),
                        BudgetState.COVERED_IN_RANGE.name(), null),
                steps, List.of());
    }

    private Plan activePlanWith(int stepCount, double perStep) {
        Plan active = confirmService.confirm(goal.getId(), draft(stepCount, perStep), null);
        flushAndClear();
        return active;
    }

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }

    private ScenarioInput rateUp() {
        return new ScenarioInput(ScenarioCode.RATE_UP, null, null, null, null, null);
    }

    // ── 불변조건 ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("§21-9 미리보기는 활성 계획을 바꾸지 않는다")
    void preview_LeavesActivePlanUntouched() {
        Plan active = activePlanWith(4, 1000.0);
        when(planCalculationService.calculate(any(), any())).thenReturn(draft(2, 2000.0));

        ScenarioPreview preview = scenarioService.preview(goal.getOwner().getId(), active.getId(), rateUp());
        flushAndClear();

        Plan reloaded = planRepository.findById(active.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(PlanStatus.ACTIVE);
        assertThat(reloaded.getVersion()).isEqualTo(1);
        assertThat(reloaded.getSupersededBy()).isNull();
        assertThat(planStepRepository.findByPlan_IdOrderBySeqAsc(active.getId())).hasSize(4);

        Plan storedDraft = planRepository.findById(preview.draftPlanId()).orElseThrow();
        assertThat(storedDraft.getStatus()).isEqualTo(PlanStatus.DRAFT);
        assertThat(planRepository.findByGoal_IdAndStatus(goal.getId(), PlanStatus.ACTIVE))
                .singleElement()
                .extracting(Plan::getId)
                .isEqualTo(active.getId());
    }

    @Test
    @DisplayName("§21-10 적용하면 버전이 오르고 이전 계획은 superseded 로 내려간다")
    void apply_IncrementsVersionAndSupersedes() {
        Plan active = activePlanWith(4, 1000.0);
        when(planCalculationService.calculate(any(), any())).thenReturn(draft(2, 2000.0));
        ScenarioPreview preview = scenarioService.preview(goal.getOwner().getId(), active.getId(), rateUp());
        flushAndClear();

        Plan applied = applyService.apply(preview.draftPlanId());
        flushAndClear();

        assertThat(applied.getVersion()).isEqualTo(2);
        Plan reloadedApplied = planRepository.findById(applied.getId()).orElseThrow();
        Plan reloadedPrevious = planRepository.findById(active.getId()).orElseThrow();
        assertThat(reloadedApplied.getStatus()).isEqualTo(PlanStatus.ACTIVE);
        assertThat(reloadedPrevious.getStatus()).isEqualTo(PlanStatus.SUPERSEDED);
        assertThat(reloadedPrevious.getSupersededBy()).isEqualTo(applied.getId());
        // 활성 계획은 끝까지 하나다 — uq_plans_active_per_goal 이 이것을 강제한다.
        assertThat(planRepository.findByGoal_IdAndStatus(goal.getId(), PlanStatus.ACTIVE)).hasSize(1);
    }

    @Test
    @DisplayName("§21-11 완료·건너뛴 과거 회차가 새 버전에 보존된다")
    void apply_PreservesExecutedHistory() {
        Plan active = activePlanWith(4, 1000.0);
        List<PlanStep> steps = planStepRepository.findByPlan_IdOrderBySeqAsc(active.getId());
        executionService.completeStep(
                active.getId(), steps.get(0).getSeq(), 4000.0, 1000.0, 1348.0, TODAY, "exec-1");
        PlanStep skipped = steps.get(1);
        skipped.markAsSkipped();
        planStepRepository.save(skipped);
        flushAndClear();

        when(planCalculationService.calculate(any(), any())).thenReturn(draft(2, 1500.0));
        ScenarioPreview preview = scenarioService.preview(goal.getOwner().getId(), active.getId(), rateUp());
        flushAndClear();

        Plan applied = applyService.apply(preview.draftPlanId());
        flushAndClear();

        List<PlanStep> newSteps = planStepRepository.findByPlan_IdOrderBySeqAsc(applied.getId());
        // 과거 2회차(완료·건너뜀)가 앞자리를 차지하고, 새 회차 2개가 뒤로 밀린다.
        assertThat(newSteps).extracting(PlanStep::getSeq).containsExactly(1, 2, 3, 4);
        assertThat(newSteps).extracting(PlanStep::getStatus).containsExactly(
                PlanStepStatus.COMPLETED, PlanStepStatus.SKIPPED,
                PlanStepStatus.SCHEDULED, PlanStepStatus.SCHEDULED);
        assertThat(newSteps.get(0).getExecutedAmount()).isEqualTo(1000.0);
        assertThat(newSteps.get(0).getExecutedRate()).isEqualTo(1348.0);
        assertThat(newSteps.get(0).getExecutedDate()).isEqualTo(TODAY);
        // 멱등 키는 원본에만 남는다 — uq_plan_steps_execution_key 가 복사를 막는다.
        assertThat(newSteps.get(0).getExecutionKey()).isNull();
        assertThat(planStepRepository.findByExecutionKey("exec-1"))
                .get()
                .extracting(step -> step.getPlan().getId())
                .isEqualTo(active.getId());
    }

    @Test
    @DisplayName("§21-12 버전이 바뀌어도 같은 멱등 키의 완료 요청은 두 번 반영되지 않는다")
    void completeStep_StaysIdempotentAcrossVersions() {
        Plan active = activePlanWith(4, 1000.0);
        List<PlanStep> steps = planStepRepository.findByPlan_IdOrderBySeqAsc(active.getId());
        executionService.completeStep(
                active.getId(), steps.get(0).getSeq(), 4000.0, 1000.0, 1348.0, TODAY, "exec-1");
        flushAndClear();

        when(planCalculationService.calculate(any(), any())).thenReturn(draft(3, 1000.0));
        ScenarioPreview preview = scenarioService.preview(goal.getOwner().getId(), active.getId(), rateUp());
        flushAndClear();
        Plan applied = applyService.apply(preview.draftPlanId());
        flushAndClear();

        // 새 버전에 대고 같은 키로 다시 완료를 보낸다 (네트워크 재시도).
        PlanStepExecutionService.CompleteResult result = executionService.completeStep(
                applied.getId(), 2, 4000.0, 1000.0, 1348.0, TODAY, "exec-1");

        assertThat(result.alreadyApplied()).isTrue();
        // 남은 금액이 두 번 줄지 않는다 — 새 버전이 보존한 완료 회차 1건만 반영된다.
        assertThat(result.remainingAmount()).isEqualTo(3000.0);
        assertThat(planStepRepository.findByPlan_IdOrderBySeqAsc(applied.getId()))
                .filteredOn(PlanStep::isCompleted)
                .hasSize(1);
    }

    @Test
    @DisplayName("적용된 계획을 또 적용할 수 없다 — 버전이 헛돈다")
    void apply_Twice_Throws() {
        Plan active = activePlanWith(2, 2000.0);
        when(planCalculationService.calculate(any(), any())).thenReturn(draft(3, 1000.0));
        ScenarioPreview preview = scenarioService.preview(goal.getOwner().getId(), active.getId(), rateUp());
        flushAndClear();
        UUID draftId = preview.draftPlanId();
        applyService.apply(draftId);
        flushAndClear();

        assertThatThrownBy(() -> applyService.apply(draftId))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("draft");
    }
}
