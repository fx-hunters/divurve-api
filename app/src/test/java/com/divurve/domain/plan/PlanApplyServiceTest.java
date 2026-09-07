package com.divurve.domain.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.divurve.common.exception.DuplicateResourceException;
import com.divurve.common.exception.InvalidRequestException;
import com.divurve.common.exception.NotFoundException;
import com.divurve.domain.goal.entity.Goal;
import com.divurve.domain.plan.entity.Plan;
import com.divurve.domain.plan.entity.PlanStep;
import com.divurve.domain.user.entity.User;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * {@link PlanApplyService} — 변경 계획 적용 (플래너 명세 §18).
 *
 * <p>불변조건 네 개를 여기서 지킨다 — 버전 증가와 superseded 전이(§21-10), 완료 회차 보존(§21-11),
 * 적용 전까지 활성 계획 미변경(§21-9), 그리고 경합 시 409.
 */
@DisplayName("PlanApplyService")
class PlanApplyServiceTest {

    private static final UUID GOAL_ID = UUID.randomUUID();
    private static final UUID DRAFT_ID = UUID.randomUUID();
    private static final UUID ACTIVE_ID = UUID.randomUUID();
    private static final LocalDate TODAY = LocalDate.of(2026, 9, 7);

    private PlanRepository planRepository;
    private PlanStepRepository planStepRepository;
    private PlanConfirmService planConfirmService;
    private PlanApplyService service;

    private Goal goal;
    private Plan draft;
    private Plan active;

    @BeforeEach
    void setUp() {
        planRepository = mock(PlanRepository.class);
        planStepRepository = mock(PlanStepRepository.class);
        planConfirmService = mock(PlanConfirmService.class);
        service = new PlanApplyService(planRepository, planStepRepository, planConfirmService);

        goal = Goal.builder(User.createDemo("a@b.com", "사용자"), "여행 자금", "onetime", "travel", "USD")
                .targetAmount(4000.0)
                .build();
        goal.setIdForTest(GOAL_ID);

        draft = Plan.builder(goal, 1).status(PlanStatus.DRAFT).build();
        draft.setIdForTest(DRAFT_ID);
        active = Plan.builder(goal, 1).status(PlanStatus.ACTIVE).build();
        active.setIdForTest(ACTIVE_ID);

        when(planRepository.findById(DRAFT_ID)).thenReturn(Optional.of(draft));
        when(planConfirmService.nextVersion(GOAL_ID)).thenReturn(2);
        when(planStepRepository.findByPlan_IdOrderBySeqAsc(any())).thenReturn(List.of());
    }

    private PlanStep step(Plan plan, int seq, double amount, String status) {
        return PlanStep.create(plan, seq, TODAY.plusWeeks(seq - 1L), amount, 0.0, status);
    }

    private PlanStep completed(Plan plan, int seq, double executed, String key) {
        PlanStep step = step(plan, seq, executed, PlanStepStatus.SCHEDULED);
        step.markAsCompleted(executed, 1350.0, TODAY.minusWeeks(1), key);
        return step;
    }

    @Test
    @DisplayName("없는 계획은 404 다")
    void apply_UnknownPlan_Throws() {
        UUID missing = UUID.randomUUID();
        when(planRepository.findById(missing)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.apply(missing))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("계획을 찾을 수 없습니다");
    }

    @Test
    @DisplayName("draft 가 아닌 계획은 적용할 수 없다 — 이미 적용된 계획을 또 올리면 버전이 헛돈다")
    void apply_NonDraft_Throws() {
        when(planRepository.findById(ACTIVE_ID)).thenReturn(Optional.of(active));

        assertThatThrownBy(() -> service.apply(ACTIVE_ID))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("draft")
                .hasFieldOrPropertyWithValue("field", "id");
    }

    @Test
    @DisplayName("이전 활성 계획이 없으면 그대로 활성이 된다")
    void apply_NoPreviousActive_JustActivates() {
        when(planRepository.findByGoal_IdAndStatus(GOAL_ID, PlanStatus.ACTIVE)).thenReturn(List.of());

        Plan applied = service.apply(DRAFT_ID);

        assertThat(applied.getStatus()).isEqualTo(PlanStatus.ACTIVE);
        assertThat(applied.getVersion()).isEqualTo(2);
        verify(planStepRepository, never()).save(any());
    }

    @Test
    @DisplayName("버전이 오르고 이전 활성 계획은 superseded 로 내려간다 — 불변조건 §21-10")
    void apply_SupersedesPrevious() {
        when(planRepository.findByGoal_IdAndStatus(GOAL_ID, PlanStatus.ACTIVE))
                .thenReturn(new ArrayList<>(List.of(active)));
        when(planConfirmService.nextVersion(GOAL_ID)).thenReturn(3);

        Plan applied = service.apply(DRAFT_ID);

        assertThat(applied.getVersion()).isEqualTo(3);
        assertThat(applied.getStatus()).isEqualTo(PlanStatus.ACTIVE);
        assertThat(active.getStatus()).isEqualTo(PlanStatus.SUPERSEDED);
        assertThat(active.getSupersededBy()).isEqualTo(DRAFT_ID);
    }

    @Test
    @DisplayName("완료·건너뛴 과거 회차를 새 버전에 복사한다 — 불변조건 §21-11")
    void apply_CarriesOverExecutedSteps() {
        PlanStep done = completed(active, 1, 1000.0, "key-1");
        PlanStep skipped = step(active, 2, 1000.0, PlanStepStatus.SCHEDULED);
        skipped.markAsSkipped();
        PlanStep openInPrevious = step(active, 3, 1000.0, PlanStepStatus.SCHEDULED);
        PlanStep fresh = step(draft, 1, 1500.0, PlanStepStatus.SCHEDULED);

        when(planRepository.findByGoal_IdAndStatus(GOAL_ID, PlanStatus.ACTIVE))
                .thenReturn(new ArrayList<>(List.of(active)));
        when(planStepRepository.findByPlan_IdOrderBySeqAsc(ACTIVE_ID))
                .thenReturn(List.of(done, skipped, openInPrevious));
        when(planStepRepository.findByPlan_IdOrderBySeqAsc(DRAFT_ID))
                .thenReturn(new ArrayList<>(List.of(fresh)));

        service.apply(DRAFT_ID);

        ArgumentCaptor<PlanStep> saved = ArgumentCaptor.forClass(PlanStep.class);
        verify(planStepRepository, org.mockito.Mockito.times(2)).save(saved.capture());
        List<PlanStep> copies = saved.getAllValues();

        // 과거 회차가 앞자리(1·2)를 차지한다.
        assertThat(copies).extracting(PlanStep::getSeq).containsExactly(1, 2);
        assertThat(copies.get(0).getStatus()).isEqualTo(PlanStepStatus.COMPLETED);
        assertThat(copies.get(0).getExecutedAmount()).isEqualTo(1000.0);
        assertThat(copies.get(0).getExecutedRate()).isEqualTo(1350.0);
        assertThat(copies.get(0).getExecutedDate()).isEqualTo(TODAY.minusWeeks(1));
        assertThat(copies.get(1).getStatus()).isEqualTo(PlanStepStatus.SKIPPED);
        assertThat(copies).allSatisfy(copy -> assertThat(copy.getPlan()).isSameAs(draft));

        // 미실행 회차(seq 3)는 복사하지 않는다 — 새 계획이 이미 자기 회차를 갖고 있다.
        assertThat(copies).hasSize(2);
    }

    @Test
    @DisplayName("멱등 키는 복사하지 않는다 — 원본에 남아야 버전 교체 뒤 중복 완료를 잡는다 (§21-12)")
    void apply_DoesNotCopyExecutionKey() {
        PlanStep done = completed(active, 1, 1000.0, "key-1");
        when(planRepository.findByGoal_IdAndStatus(GOAL_ID, PlanStatus.ACTIVE))
                .thenReturn(new ArrayList<>(List.of(active)));
        when(planStepRepository.findByPlan_IdOrderBySeqAsc(ACTIVE_ID)).thenReturn(List.of(done));
        when(planStepRepository.findByPlan_IdOrderBySeqAsc(DRAFT_ID)).thenReturn(new ArrayList<>());

        service.apply(DRAFT_ID);

        ArgumentCaptor<PlanStep> saved = ArgumentCaptor.forClass(PlanStep.class);
        verify(planStepRepository).save(saved.capture());
        assertThat(saved.getValue().getExecutionKey()).isNull();
        assertThat(done.getExecutionKey()).isEqualTo("key-1");
    }

    @Test
    @DisplayName("새 회차 번호를 뒤로 민다 — 과거 회차와 번호가 겹치면 저장 자체가 실패한다")
    void apply_ShiftsNewStepSeq() {
        PlanStep done = completed(active, 1, 1000.0, "key-1");
        PlanStep first = step(draft, 1, 1500.0, PlanStepStatus.SCHEDULED);
        PlanStep second = step(draft, 2, 1500.0, PlanStepStatus.SCHEDULED);

        when(planRepository.findByGoal_IdAndStatus(GOAL_ID, PlanStatus.ACTIVE))
                .thenReturn(new ArrayList<>(List.of(active)));
        when(planStepRepository.findByPlan_IdOrderBySeqAsc(ACTIVE_ID)).thenReturn(List.of(done));
        when(planStepRepository.findByPlan_IdOrderBySeqAsc(DRAFT_ID))
                .thenReturn(new ArrayList<>(List.of(first, second)));

        service.apply(DRAFT_ID);

        assertThat(first.getSeq()).isEqualTo(2);
        assertThat(second.getSeq()).isEqualTo(3);
        // 뒤에서부터 민다 — 앞에서부터 밀면 아직 옮기지 않은 번호와 겹친다.
        org.mockito.InOrder order = org.mockito.Mockito.inOrder(planStepRepository);
        order.verify(planStepRepository).saveAndFlush(second);
        order.verify(planStepRepository).saveAndFlush(first);
    }

    @Test
    @DisplayName("과거 회차가 없으면 번호를 밀지 않는다")
    void apply_NoHistory_DoesNotShift() {
        PlanStep fresh = step(draft, 1, 1500.0, PlanStepStatus.SCHEDULED);
        when(planRepository.findByGoal_IdAndStatus(GOAL_ID, PlanStatus.ACTIVE))
                .thenReturn(new ArrayList<>(List.of(active)));
        when(planStepRepository.findByPlan_IdOrderBySeqAsc(ACTIVE_ID))
                .thenReturn(List.of(step(active, 1, 1000.0, PlanStepStatus.SCHEDULED)));
        when(planStepRepository.findByPlan_IdOrderBySeqAsc(DRAFT_ID))
                .thenReturn(new ArrayList<>(List.of(fresh)));

        service.apply(DRAFT_ID);

        assertThat(fresh.getSeq()).isEqualTo(1);
        verify(planStepRepository, never()).saveAndFlush(any());
        verify(planStepRepository, never()).save(any());
    }

    @Test
    @DisplayName("동시 적용이 겹치면 409 다 — 조용히 한쪽을 이기게 두지 않는다")
    void apply_Contention_Throws409() {
        when(planRepository.findByGoal_IdAndStatus(GOAL_ID, PlanStatus.ACTIVE)).thenReturn(List.of());
        // 첫 flush 는 이전 계획을 내리는 것이고, 경합은 draft 를 올리는 두 번째 flush 에서 드러난다.
        doNothing()
                .doThrow(new DataIntegrityViolationException("uq_plans_active_per_goal"))
                .when(planRepository).flush();

        assertThatThrownBy(() -> service.apply(DRAFT_ID))
                .isInstanceOf(DuplicateResourceException.class)
                .hasMessageContaining("다른 변경안이 먼저 적용됐습니다");
    }

    @Test
    @DisplayName("의존이 null 이면 생성을 거부한다")
    void nullDependencies_Throw() {
        assertThatThrownBy(() -> new PlanApplyService(null, planStepRepository, planConfirmService))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new PlanApplyService(planRepository, null, planConfirmService))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new PlanApplyService(planRepository, planStepRepository, null))
                .isInstanceOf(NullPointerException.class);
    }
}
