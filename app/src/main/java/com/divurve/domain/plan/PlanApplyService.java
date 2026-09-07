package com.divurve.domain.plan;

import static java.util.Objects.requireNonNull;

import com.divurve.common.architecture.UseCase;
import com.divurve.common.exception.DuplicateResourceException;
import com.divurve.common.exception.InvalidRequestException;
import com.divurve.common.exception.NotFoundException;
import com.divurve.domain.plan.entity.Plan;
import com.divurve.domain.plan.entity.PlanStep;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

/**
 * 변경 계획 적용 (플래너 명세 §18).
 *
 * <p>미리보기가 저장해 둔 {@code draft} 를 활성으로 승격한다. <b>여기서 다시 계산하지 않는다</b> —
 * 승인과 적용 사이에 환율이 움직이면 사용자가 승인한 것과 다른 계획이 활성화되기 때문이다.
 *
 * <p>지키는 불변조건:
 * <ul>
 *   <li>§21-10 — 버전이 증가하고 이전 활성 계획은 {@code superseded} 로 내려가며
 *       {@code superseded_by} 에 새 계획 id 가 남는다.</li>
 *   <li>§21-11 — <b>완료·건너뛴 과거 회차가 새 버전에 보존된다.</b> 새 계획의 회차는 오늘부터
 *       다시 생성된 것이라 과거 기록이 없다. 복사하지 않으면 사용자가 이미 산 외화의 기록이
 *       버전 교체와 함께 사라진다.</li>
 *   <li>§21-9 — 적용 전까지 활성 계획은 그대로였다. 이 서비스가 처음으로 바꾼다.</li>
 * </ul>
 */
@UseCase
public class PlanApplyService {

    private final PlanRepository planRepository;
    private final PlanStepRepository planStepRepository;
    private final PlanConfirmService planConfirmService;

    public PlanApplyService(
            PlanRepository planRepository,
            PlanStepRepository planStepRepository,
            PlanConfirmService planConfirmService) {
        this.planRepository = requireNonNull(planRepository, "planRepository");
        this.planStepRepository = requireNonNull(planStepRepository, "planStepRepository");
        this.planConfirmService = requireNonNull(planConfirmService, "planConfirmService");
    }

    /**
     * draft 계획을 활성으로 적용한다.
     *
     * @param draftPlanId 미리보기가 돌려준 임시 계획 id
     * @return 활성이 된 계획
     * @throws NotFoundException          계획을 찾을 수 없는 경우
     * @throws InvalidRequestException    draft 가 아닌 계획을 적용하려 한 경우
     * @throws DuplicateResourceException 동시 적용이 겹쳐 활성 계획이 둘이 되려 한 경우 (409)
     */
    @Transactional
    public Plan apply(UUID draftPlanId) {
        Plan draft = planRepository.findById(draftPlanId)
                .orElseThrow(() -> new NotFoundException("계획을 찾을 수 없습니다: " + draftPlanId));
        if (!draft.isDraft()) {
            throw new InvalidRequestException(
                    "적용할 수 있는 것은 미리보기(draft) 계획뿐입니다: status=" + draft.getStatus(), "id");
        }

        UUID goalId = draft.getGoal().getId();
        List<Plan> previous = planRepository.findByGoal_IdAndStatus(goalId, PlanStatus.ACTIVE);
        previous.forEach(plan -> carryOverExecutedSteps(plan, draft));

        draft.assignVersion(planConfirmService.nextVersion(goalId));
        return promote(draft, previous);
    }

    /**
     * 이전 계획의 완료·건너뛴 회차를 새 계획으로 복사한다 (§21-11).
     *
     * <p>새 계획의 회차 번호를 <b>뒤로 민다</b>. 과거 회차가 1..n 을 차지하고 새 회차가 n+1 부터
     * 이어지게 해야 사용자가 보는 순서와 번호가 일치한다 — {@code uq_plan_steps_plan_seq} 가
     * 한 계획 안의 번호 중복을 막으므로 밀지 않으면 저장 자체가 실패한다.
     *
     * <p>{@code execution_key} 는 복사하지 않는다. 그 키에는 유니크 인덱스가 걸려 있어 복사하면
     * 제약 위반이 되고, 무엇보다 <b>원본에 남겨 두어야 멱등이 유지된다</b> — 같은 완료 요청이
     * 버전 교체 뒤에 다시 도착해도 {@code findByExecutionKey} 가 원본을 찾아 "이미 반영됨"으로
     * 답한다 (§21-12).
     */
    private void carryOverExecutedSteps(Plan previous, Plan draft) {
        List<PlanStep> history = planStepRepository.findByPlan_IdOrderBySeqAsc(previous.getId()).stream()
                .filter(step -> !step.isOpen())
                .toList();
        if (history.isEmpty()) {
            return;
        }

        List<PlanStep> upcoming = planStepRepository.findByPlan_IdOrderBySeqAsc(draft.getId());
        shiftSeq(upcoming, history.size());

        int seq = 1;
        for (PlanStep source : history) {
            planStepRepository.save(copyOf(source, draft, seq++));
        }
    }

    /**
     * 새 회차 번호를 {@code offset} 만큼 뒤로 민다.
     *
     * <p>뒤에서부터 민다 — 앞에서부터 밀면 아직 옮기지 않은 회차의 번호와 겹쳐 유니크 인덱스에
     * 걸린다. 밀 때마다 flush 하는 것도 같은 이유다: 한 번에 모아 보내면 순서가 보장되지 않는다.
     */
    private void shiftSeq(List<PlanStep> steps, int offset) {
        for (int i = steps.size() - 1; i >= 0; i--) {
            PlanStep step = steps.get(i);
            step.reassignSeq(step.getSeq() + offset);
            planStepRepository.saveAndFlush(step);
        }
    }

    private static PlanStep copyOf(PlanStep source, Plan draft, int seq) {
        PlanStep copy = PlanStep.create(
                draft,
                seq,
                source.getScheduledDate(),
                source.getAmount(),
                source.getExecutedAmount(),
                source.getStatus());
        copy.recordCostBasis(
                source.getBudgetKrw(), source.getBaseRate(),
                source.getLowCostKrw(), source.getHighCostKrw());
        copy.recordExecutionOutcome(source.getExecutedRate(), source.getExecutedDate());
        return copy;
    }

    /**
     * 이전 계획을 내리고 draft 를 활성으로 올린다.
     *
     * <p>순서가 중요하다 — 먼저 올리면 순간적으로 활성이 둘이 되어
     * {@code uq_plans_active_per_goal} 에 걸린다. 그래도 다른 요청이 같은 순간에 같은 일을 하면
     * 인덱스가 막아 주고, 그 실패는 409 로 나간다: 사용자가 두 화면에서 서로 다른 변경안을
     * 동시에 적용했다는 뜻이므로 조용히 한쪽을 이기게 두면 안 된다.
     */
    private Plan promote(Plan draft, List<Plan> previous) {
        previous.forEach(plan -> {
            plan.deactivate();
            plan.supersededBy(draft.getId());
        });
        planRepository.flush();

        draft.activate();
        try {
            planRepository.flush();
        } catch (DataIntegrityViolationException e) {
            throw new DuplicateResourceException(
                    "다른 변경안이 먼저 적용됐습니다. 최신 계획을 다시 불러와 주세요.");
        }
        return draft;
    }
}
