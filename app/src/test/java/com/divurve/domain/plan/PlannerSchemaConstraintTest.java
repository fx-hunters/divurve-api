package com.divurve.domain.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.divurve.domain.RepositoryTestBase;
import com.divurve.domain.goal.GoalRepository;
import com.divurve.domain.goal.GoalType;
import com.divurve.domain.goal.PriorityConstraint;
import com.divurve.domain.goal.entity.Goal;
import com.divurve.domain.plan.entity.Plan;
import com.divurve.domain.plan.entity.PlanCalculationMeta;
import com.divurve.domain.plan.entity.PlanCostSummary;
import com.divurve.domain.plan.entity.PlanStep;
import com.divurve.domain.user.UserRepository;
import com.divurve.domain.user.entity.User;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * V16 이 추가한 스키마 제약이 실제 Postgres 에서 작동하는지 검증한다 (플래너 명세 §21).
 *
 * <p>목표당 활성 계획 하나 · 회차 번호 유일성 · 완료 요청 멱등성은 지금까지 <b>애플리케이션
 * 코드로만</b> 지켜졌다. 동시 요청이 겹치면 코드만으로는 막히지 않으므로 DB 제약으로 올렸고,
 * 이 테스트는 그 제약이 실연동에서 걸리는지 확인한다 — 인덱스 이름만 보고는 알 수 없다.
 */
class PlannerSchemaConstraintTest extends RepositoryTestBase {

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

    private Goal newGoal(String email) {
        User owner = userRepository.save(User.createDemo(email, "사용자"));
        return goalRepository.save(Goal.builder(owner, "목표", "onetime", "spend", "USD")
                .targetAmount(10000).budgetAmount(1_000_000).status("active").build());
    }

    private Plan savedPlan(Goal goal, int version, String status) {
        return planRepository.save(Plan.builder(goal, version).status(status).build());
    }

    /**
     * 제약 위반은 {@code saveAndFlush} 로 확인한다 — {@code EntityManager.flush()} 를 직접 부르면
     * Spring 의 예외 변환을 거치지 않아 Hibernate 내부 예외가 그대로 올라온다.
     */
    private Plan flushedPlan(Goal goal, int version, String status) {
        return planRepository.saveAndFlush(Plan.builder(goal, version).status(status).build());
    }

    @Test
    @DisplayName("목표당 활성 계획은 하나뿐이다 — 불변조건 §21-9")
    void onlyOneActivePlanPerGoal() {
        Goal goal = newGoal("active-plan-" + UUID.randomUUID() + "@divurve.com");
        flushedPlan(goal, 1, PlanStatus.ACTIVE);

        assertThatThrownBy(() -> flushedPlan(goal, 2, PlanStatus.ACTIVE))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("활성이 아닌 계획은 여러 개 공존할 수 있다 — 버전 이력이 쌓인다")
    void multipleNonActivePlansCoexist() {
        Goal goal = newGoal("history-plan-" + UUID.randomUUID() + "@divurve.com");
        savedPlan(goal, 1, PlanStatus.SUPERSEDED);
        savedPlan(goal, 2, PlanStatus.SUPERSEDED);
        savedPlan(goal, 3, PlanStatus.DRAFT);
        savedPlan(goal, 4, PlanStatus.ACTIVE);
        entityManager.flush();

        assertThat(planRepository.findByGoal_Id(goal.getId())).hasSize(4);
    }

    @Test
    @DisplayName("한 계획 안에서 회차 번호는 유일하다")
    void planStepSeqIsUniqueWithinPlan() {
        Goal goal = newGoal("dup-seq-" + UUID.randomUUID() + "@divurve.com");
        Plan plan = savedPlan(goal, 1, PlanStatus.ACTIVE);
        planStepRepository.saveAndFlush(PlanStep.create(plan, 1, LocalDate.now(), 100.0, 0.0,
                PlanStepStatus.SCHEDULED));
        PlanStep duplicate = PlanStep.create(plan, 1, LocalDate.now(), 200.0, 0.0,
                PlanStepStatus.SCHEDULED);

        assertThatThrownBy(() -> planStepRepository.saveAndFlush(duplicate))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("같은 멱등 키로 두 회차를 완료할 수 없다 — 불변조건 §21-12")
    void executionKeyIsUnique() {
        Goal goal = newGoal("dup-key-" + UUID.randomUUID() + "@divurve.com");
        Plan plan = savedPlan(goal, 1, PlanStatus.ACTIVE);
        PlanStep first = PlanStep.create(plan, 1, LocalDate.now(), 100.0, 0.0, PlanStepStatus.SCHEDULED);
        PlanStep second = PlanStep.create(plan, 2, LocalDate.now(), 100.0, 0.0, PlanStepStatus.SCHEDULED);
        first.markAsCompleted(100.0, 1350.0, LocalDate.now(), "same-key");
        second.markAsCompleted(100.0, 1350.0, LocalDate.now(), "same-key");
        planStepRepository.saveAndFlush(first);

        assertThatThrownBy(() -> planStepRepository.saveAndFlush(second))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("아직 실행되지 않은 회차는 멱등 키가 없어 여러 개 공존한다")
    void nullExecutionKeysCoexist() {
        Goal goal = newGoal("null-key-" + UUID.randomUUID() + "@divurve.com");
        Plan plan = savedPlan(goal, 1, PlanStatus.ACTIVE);
        planStepRepository.save(PlanStep.create(plan, 1, LocalDate.now(), 100.0, 0.0,
                PlanStepStatus.SCHEDULED));
        planStepRepository.save(PlanStep.create(plan, 2, LocalDate.now(), 100.0, 0.0,
                PlanStepStatus.SCHEDULED));
        entityManager.flush();

        assertThat(planStepRepository.findByPlan_IdOrderBySeqAsc(plan.getId())).hasSize(2);
    }

    @Test
    @DisplayName("목표의 플래너 입력 필드가 저장·조회된다 — 명세 §5")
    void goalPlannerFieldsRoundTrip() {
        User owner = userRepository.save(
                User.createDemo("recurring-" + UUID.randomUUID() + "@divurve.com", "사용자"));
        Goal saved = goalRepository.save(Goal.builder(owner, "ETF 자금", "onetime", "invest", "USD")
                .targetAmount(0).budgetAmount(500_000).status("active")
                .goalType(GoalType.RECURRING)
                .allocatedHoldingAmount(1200.50)
                .priorityConstraint(PriorityConstraint.BUDGET)
                .preferredCadence("monthly")
                .recurStartDate(LocalDate.of(2026, 10, 1))
                .reviewHorizonMonths(6)
                .linkedPurposeName("S&P500 ETF")
                .build());
        entityManager.flush();
        entityManager.clear();

        Goal found = goalRepository.findById(saved.getId()).orElseThrow();
        assertThat(found.isRecurring()).isTrue();
        assertThat(found.getAllocatedHoldingAmount()).isEqualTo(1200.50);
        assertThat(found.getPriorityConstraint()).isEqualTo(PriorityConstraint.BUDGET);
        assertThat(found.getPreferredCadence()).isEqualTo("monthly");
        assertThat(found.getRecurStartDate()).isEqualTo(LocalDate.of(2026, 10, 1));
        assertThat(found.getReviewHorizonMonths()).isEqualTo(6);
        assertThat(found.getLinkedPurposeName()).isEqualTo("S&P500 ETF");
    }

    @Test
    @DisplayName("계산 메타데이터와 비용 범위가 저장·조회된다 — 명세 §11.1·§11.3")
    void planCalculationMetaRoundTrip() {
        Goal goal = newGoal("meta-" + UUID.randomUUID() + "@divurve.com");
        Instant asOf = Instant.parse("2026-09-07T00:00:00Z");
        Plan saved = planRepository.save(Plan.builder(goal, 1)
                .status(PlanStatus.ACTIVE)
                .planEndDate(LocalDate.of(2026, 12, 24))
                .calculationMeta(PlanCalculationMeta.builder("plan-2026.09.1-equal-split")
                        .rateAsOf(asOf)
                        .forecastAsOf(asOf)
                        .rates(1300.0, 1350.0, 1400.0)
                        .spreadRatio(0.0175)
                        .feeKrw(3000L)
                        .quoteUnit(1)
                        .build())
                .costSummary(PlanCostSummary.of("RANGE_SENSITIVE", 1_325_750L, 1_376_625L, 1_427_500L))
                .build());
        entityManager.flush();
        entityManager.clear();

        Plan found = planRepository.findById(saved.getId()).orElseThrow();
        assertThat(found.getPlanEndDate()).isEqualTo(LocalDate.of(2026, 12, 24));
        assertThat(found.getCalculationMeta().getPolicyVersion()).isEqualTo("plan-2026.09.1-equal-split");
        assertThat(found.getCalculationMeta().getRateAsOf()).isEqualTo(asOf);
        assertThat(found.getCalculationMeta().getForecastAsOf()).isEqualTo(asOf);
        assertThat(found.getCalculationMeta().getRateLow()).isEqualTo(1300.0);
        assertThat(found.getCalculationMeta().getBaseRate()).isEqualTo(1350.0);
        assertThat(found.getCalculationMeta().getRateHigh()).isEqualTo(1400.0);
        assertThat(found.getCalculationMeta().getSpreadRatio()).isEqualTo(0.0175);
        assertThat(found.getCalculationMeta().getFeeKrw()).isEqualTo(3000L);
        assertThat(found.getCalculationMeta().getQuoteUnit()).isEqualTo(1);
        assertThat(found.getCostSummary().getBudgetState()).isEqualTo("RANGE_SENSITIVE");
        assertThat(found.getCostSummary().getLowCostKrw()).isEqualTo(1_325_750L);
        assertThat(found.getCostSummary().getBaseCostKrw()).isEqualTo(1_376_625L);
        assertThat(found.getCostSummary().getHighCostKrw()).isEqualTo(1_427_500L);
    }

    @Test
    @DisplayName("회차의 실행 기록과 비용 근거가 저장·조회된다 — 명세 §11.4·§14")
    void planStepExecutionFieldsRoundTrip() {
        Goal goal = newGoal("step-exec-" + UUID.randomUUID() + "@divurve.com");
        Plan plan = savedPlan(goal, 1, PlanStatus.ACTIVE);
        PlanStep step = PlanStep.create(plan, 1, LocalDate.of(2026, 10, 1), 500.0, 0.0,
                PlanStepStatus.SCHEDULED);
        step.recordCostBasis(500_000L, 1350.0, 650_000L, 700_000L);
        step.markAsCompleted(500.0, 1348.5, LocalDate.of(2026, 10, 2), "exec-" + UUID.randomUUID());
        PlanStep saved = planStepRepository.save(step);
        entityManager.flush();
        entityManager.clear();

        PlanStep found = planStepRepository.findById(saved.getId()).orElseThrow();
        assertThat(found.getStatus()).isEqualTo(PlanStepStatus.COMPLETED);
        assertThat(found.getBudgetKrw()).isEqualTo(500_000L);
        assertThat(found.getBaseRate()).isEqualTo(1350.0);
        assertThat(found.getLowCostKrw()).isEqualTo(650_000L);
        assertThat(found.getHighCostKrw()).isEqualTo(700_000L);
        assertThat(found.getExecutedRate()).isEqualTo(1348.5);
        assertThat(found.getExecutedDate()).isEqualTo(LocalDate.of(2026, 10, 2));
        assertThat(found.getExecutionKey()).isNotNull();
    }

    @Test
    @DisplayName("계획을 비활성화하면 상태도 superseded 로 함께 옮겨진다")
    void deactivateMovesStatusToSuperseded() {
        Goal goal = newGoal("deactivate-" + UUID.randomUUID() + "@divurve.com");
        Plan plan = savedPlan(goal, 1, PlanStatus.ACTIVE);
        // superseded_by 는 plans 를 참조하는 FK 다 — 실제로 존재하는 계획만 가리킬 수 있다.
        Plan successor = savedPlan(goal, 2, PlanStatus.DRAFT);
        UUID newPlanId = successor.getId();

        plan.deactivate();
        plan.supersededBy(newPlanId);
        entityManager.flush();
        entityManager.clear();

        Plan found = planRepository.findById(plan.getId()).orElseThrow();
        assertThat(found.getStatus()).isEqualTo(PlanStatus.SUPERSEDED);
        assertThat(found.isActivePlan()).isFalse();
        assertThat(found.getSupersededBy()).isEqualTo(newPlanId);
    }
}
