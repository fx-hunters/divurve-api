package com.divurve.domain.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.divurve.common.exception.NotFoundException;
import com.divurve.domain.RepositoryTestBase;
import com.divurve.domain.goal.GoalRepository;
import com.divurve.domain.goal.GoalType;
import com.divurve.domain.goal.entity.Goal;
import com.divurve.domain.plan.entity.Plan;
import com.divurve.domain.plan.entity.PlanStep;
import com.divurve.domain.user.UserRepository;
import com.divurve.domain.user.entity.User;
import com.divurve.engine.planner.PlannerPolicy;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * {@link PlanConfirmService} — 계획 저장과 버전 관리 (플래너 명세 §11·§18·§21-10).
 *
 * <p>실제 Postgres 로 돌린다. 목표당 활성 계획이 하나라는 것은 부분 유니크 인덱스가 보장하므로,
 * 인메모리 목으로는 버전 전환이 실제로 성립하는지 확인할 수 없다 — 새 계획을 활성으로 올리기 전에
 * 이전 계획을 내려야 한다는 순서 제약이 바로 그 인덱스에서 나온다.
 */
class PlanConfirmServiceTest extends RepositoryTestBase {

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

    private PlanConfirmService service() {
        return new PlanConfirmService(goalRepository, planRepository, planStepRepository);
    }

    private Goal newGoal() {
        User owner = userRepository.save(
                User.createDemo("confirm-" + UUID.randomUUID() + "@divurve.com", "사용자"));
        return goalRepository.save(Goal.builder(owner, "여행 자금", "onetime", "travel", "USD")
                .targetAmount(4000.0).budgetAmount(1_000_000).status("active")
                .goalType(GoalType.DEADLINE)
                .build());
    }

    private PlanDraft draft(int stepCount) {
        Instant asOf = Instant.parse("2026-09-07T00:00:00Z");
        PlanRateContext rates = new PlanRateContext(
                "USD", 1300.0, 1350.0, 1400.0, 0.0175, 3000L, 1, 2, asOf, asOf, true);
        List<PlanDraft.Step> steps = java.util.stream.IntStream.rangeClosed(1, stepCount)
                .mapToObj(seq -> new PlanDraft.Step(
                        seq,
                        LocalDate.of(2026, 9, 7).plusWeeks(seq - 1L),
                        new BigDecimal("1000.00"),
                        null,
                        new PlanDraft.CostRange(1_300_000L, 1_350_000L, 1_400_000L),
                        null,
                        BigDecimal.ZERO, null, null,
                        PlanStepStatus.SCHEDULED, seq == 1))
                .toList();
        return new PlanDraft(
                asOf,
                PlannerPolicy.POLICY_VERSION,
                rates,
                new PlanDraft.GoalSummary(
                        GoalType.DEADLINE, "travel", "USD",
                        new BigDecimal("4000.00"), null, BigDecimal.ZERO, new BigDecimal("4000.00"),
                        LocalDate.of(2026, 12, 24), "amount"),
                new PlanDraft.Summary(
                        PlanStatus.DRAFT, LocalDate.of(2026, 12, 21), stepCount, 0, stepCount, 0, 1,
                        new PlanDraft.CostRange(5_200_000L, 5_400_000L, 5_600_000L),
                        "RANGE_SENSITIVE", null),
                steps,
                List.of());
    }

    @Test
    @DisplayName("첫 계획은 버전 1 이고 활성이다")
    void firstPlanIsVersionOneAndActive() {
        Goal goal = newGoal();

        Plan saved = service().confirm(goal.getId(), draft(4), null);
        entityManager.flush();

        assertThat(saved.getVersion()).isEqualTo(1);
        assertThat(saved.getStatus()).isEqualTo(PlanStatus.ACTIVE);
        assertThat(saved.isActivePlan()).isTrue();
    }

    @Test
    @DisplayName("계산 전제를 결과와 함께 저장한다 — 명세 §7·§11.1")
    void storesCalculationMeta() {
        Goal goal = newGoal();

        Plan saved = service().confirm(goal.getId(), draft(4), null);
        entityManager.flush();
        entityManager.clear();

        Plan found = planRepository.findById(saved.getId()).orElseThrow();
        assertThat(found.getCalculationMeta().getPolicyVersion())
                .isEqualTo(PlannerPolicy.POLICY_VERSION);
        assertThat(found.getCalculationMeta().getBaseRate()).isEqualTo(1350.0);
        assertThat(found.getCalculationMeta().getRateLow()).isEqualTo(1300.0);
        assertThat(found.getCalculationMeta().getRateHigh()).isEqualTo(1400.0);
        assertThat(found.getCalculationMeta().getSpreadRatio()).isEqualTo(0.0175);
        assertThat(found.getCalculationMeta().getFeeKrw()).isEqualTo(3000L);
        assertThat(found.getCostSummary().getBudgetState()).isEqualTo("RANGE_SENSITIVE");
        assertThat(found.getPlanEndDate()).isEqualTo(LocalDate.of(2026, 12, 21));
    }

    @Test
    @DisplayName("회차를 seq 순서로 저장한다 — 명세 §11.4")
    void savesStepsInOrder() {
        Goal goal = newGoal();

        Plan saved = service().confirm(goal.getId(), draft(4), null);
        entityManager.flush();
        entityManager.clear();

        List<PlanStep> steps = planStepRepository.findByPlan_IdOrderBySeqAsc(saved.getId());
        assertThat(steps).extracting(PlanStep::getSeq).containsExactly(1, 2, 3, 4);
        assertThat(steps).allSatisfy(step -> {
            assertThat(step.getStatus()).isEqualTo(PlanStepStatus.SCHEDULED);
            assertThat(step.getExecutedAmount()).isZero();
            assertThat(step.getBaseRate()).isEqualTo(1350.0);
            assertThat(step.getLowCostKrw()).isEqualTo(1_300_000L);
            assertThat(step.getHighCostKrw()).isEqualTo(1_400_000L);
        });
    }

    @Test
    @DisplayName("새 버전을 적용하면 버전이 증가하고 이전 계획은 superseded 다 — 불변조건 §21-10")
    void newVersionSupersedesPrevious() {
        Goal goal = newGoal();
        PlanConfirmService service = service();

        Plan first = service.confirm(goal.getId(), draft(4), null);
        entityManager.flush();
        Plan second = service.confirm(goal.getId(), draft(3), "예산 감소");
        entityManager.flush();
        entityManager.clear();

        Plan reloadedFirst = planRepository.findById(first.getId()).orElseThrow();
        Plan reloadedSecond = planRepository.findById(second.getId()).orElseThrow();

        assertThat(reloadedSecond.getVersion()).isEqualTo(2);
        assertThat(reloadedSecond.getStatus()).isEqualTo(PlanStatus.ACTIVE);
        assertThat(reloadedSecond.getReason()).isEqualTo("예산 감소");
        assertThat(reloadedFirst.getStatus()).isEqualTo(PlanStatus.SUPERSEDED);
        assertThat(reloadedFirst.getSupersededBy()).isEqualTo(second.getId());
    }

    @Test
    @DisplayName("이전 버전의 회차 기록은 지우지 않는다 — 불변조건 §21-11")
    void previousStepsArePreserved() {
        Goal goal = newGoal();
        PlanConfirmService service = service();

        Plan first = service.confirm(goal.getId(), draft(4), null);
        entityManager.flush();
        service.confirm(goal.getId(), draft(3), "재계산");
        entityManager.flush();
        entityManager.clear();

        assertThat(planStepRepository.findByPlan_IdOrderBySeqAsc(first.getId())).hasSize(4);
    }

    @Test
    @DisplayName("활성 계획은 언제나 하나뿐이다 — 불변조건 §21-9")
    void onlyOneActivePlanRemains() {
        Goal goal = newGoal();
        PlanConfirmService service = service();

        service.confirm(goal.getId(), draft(4), null);
        entityManager.flush();
        service.confirm(goal.getId(), draft(3), "재계산");
        entityManager.flush();
        service.confirm(goal.getId(), draft(2), "재계산");
        entityManager.flush();
        entityManager.clear();

        assertThat(planRepository.findByGoal_IdAndStatus(goal.getId(), PlanStatus.ACTIVE))
                .hasSize(1)
                .allSatisfy(plan -> assertThat(plan.getVersion()).isEqualTo(3));
    }

    @Test
    @DisplayName("없는 목표에는 저장하지 않는다")
    void unknownGoal_Throws() {
        UUID unknownId = UUID.randomUUID();
        PlanDraft draft = draft(1);

        assertThatThrownBy(() -> service().confirm(unknownId, draft, null))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    @DisplayName("draft 저장은 활성 계획을 건드리지 않는다 — 불변조건 §21-9")
    void saveDraft_LeavesActivePlanAlone() {
        Goal goal = newGoal();
        PlanConfirmService service = service();
        Plan active = service.confirm(goal.getId(), draft(2), null);
        entityManager.flush();
        entityManager.clear();

        Plan saved = service.saveDraft(goal.getId(), draft(3), "RATE_UP");
        entityManager.flush();
        entityManager.clear();

        assertThat(saved.getStatus()).isEqualTo(PlanStatus.DRAFT);
        assertThat(saved.getReason()).isEqualTo("RATE_UP");
        assertThat(planStepRepository.findByPlan_IdOrderBySeqAsc(saved.getId())).hasSize(3);

        Plan reloadedActive = planRepository.findById(active.getId()).orElseThrow();
        assertThat(reloadedActive.getStatus()).isEqualTo(PlanStatus.ACTIVE);
        assertThat(reloadedActive.getSupersededBy()).isNull();
    }

    @Test
    @DisplayName("draft 는 버전 번호를 소모하지 않는다 — 미리보기를 눌러도 이력에 구멍이 나지 않는다")
    void saveDraft_DoesNotConsumeVersionNumber() {
        Goal goal = newGoal();
        PlanConfirmService service = service();
        service.confirm(goal.getId(), draft(1), null);
        entityManager.flush();

        service.saveDraft(goal.getId(), draft(1), "RATE_UP");
        service.saveDraft(goal.getId(), draft(1), "RATE_DOWN");
        entityManager.flush();

        // 미리보기를 두 번 눌렀어도 다음 확정 버전은 2 다.
        assertThat(service.nextVersion(goal.getId())).isEqualTo(2);
        assertThat(service.confirm(goal.getId(), draft(1), "적용").getVersion()).isEqualTo(2);
    }

    @Test
    @DisplayName("계획이 하나도 없으면 첫 버전은 1 이다")
    void nextVersion_StartsAtOne() {
        assertThat(service().nextVersion(newGoal().getId())).isEqualTo(1);
    }

    @Test
    @DisplayName("없는 목표에는 draft 도 저장하지 않는다")
    void saveDraft_UnknownGoal_Throws() {
        UUID unknownId = UUID.randomUUID();
        PlanDraft draft = draft(1);
        PlanConfirmService service = service();

        assertThatThrownBy(() -> service.saveDraft(unknownId, draft, "RATE_UP"))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    @DisplayName("null draft 는 저장하지 않는다")
    void saveDraft_NullDraft_Throws() {
        Goal goal = newGoal();
        PlanConfirmService service = service();
        UUID goalId = goal.getId();

        assertThatThrownBy(() -> service.saveDraft(goalId, null, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("null 인자와 의존은 거부한다")
    void nullArguments_Throw() {
        Goal goal = newGoal();
        PlanConfirmService service = service();
        UUID goalId = goal.getId();

        assertThatThrownBy(() -> service.confirm(goalId, null, null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new PlanConfirmService(null, planRepository, planStepRepository))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new PlanConfirmService(goalRepository, null, planStepRepository))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new PlanConfirmService(goalRepository, planRepository, null))
                .isInstanceOf(NullPointerException.class);
    }
}
