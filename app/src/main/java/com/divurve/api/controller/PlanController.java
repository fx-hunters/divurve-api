package com.divurve.api.controller;

import static java.util.Objects.requireNonNull;

import com.divurve.api.config.auth.CurrentUser;
import com.divurve.api.dto.plan.PlanRequest;
import com.divurve.api.dto.plan.PlanResponse;
import com.divurve.api.dto.plan.PlanResponseMapper;
import com.divurve.api.dto.plan.PlanVersionListResponse;
import com.divurve.api.dto.plan.ScenarioPreviewRequest;
import com.divurve.api.dto.plan.ScenarioPreviewResponse;
import com.divurve.api.dto.plan.StepCompleteRequest;
import com.divurve.api.dto.plan.StepCompleteResponse;
import com.divurve.api.dto.plan.StepSkipResponse;
import com.divurve.common.architecture.WebAdapter;
import com.divurve.common.exception.NotFoundException;
import com.divurve.common.response.ApiResponse;
import com.divurve.domain.goal.entity.Goal;
import com.divurve.domain.plan.PlanAccessService;
import com.divurve.domain.plan.PlanAllocationGuard;
import com.divurve.domain.plan.PlanApplyService;
import com.divurve.domain.plan.PlanCalculationService;
import com.divurve.domain.plan.PlanConfirmService;
import com.divurve.domain.plan.PlanDraft;
import com.divurve.domain.plan.PlanInput;
import com.divurve.domain.plan.PlanRepository;
import com.divurve.domain.plan.PlanScenarioService;
import com.divurve.domain.plan.PlanStatus;
import com.divurve.domain.plan.PlanStepExecutionService;
import com.divurve.domain.plan.PlanStepRepository;
import com.divurve.domain.plan.entity.Plan;
import com.divurve.domain.plan.entity.PlanStep;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 계획 엔드포인트 (플래너 명세 §11·§12). 경로가 {@code /plans} 와 {@code /goals/{id}/plans} 를
 * 넘나들어 base 는 {@code /api/v1} 로 둔다.
 *
 * <p>미리보기와 확정을 나눈 것이 이 컨트롤러의 핵심이다 — 명세 §12 는 조건 확인(장면 3)과
 * 계획 생성(장면 4·5)을 별도 장면으로 두고, §18 은 사용자의 승인 전에는 활성 계획이 바뀌지
 * 않을 것을 요구한다(§21-9). {@code POST /plans/preview} 는 <b>아무것도 저장하지 않는다.</b>
 *
 * <p>모든 엔드포인트가 {@link PlanAccessService} 로 소유자를 먼저 검증한다 (이슈 #50).
 * 미소유 시 403 이 아니라 404 로 존재 자체를 숨긴다.
 */
@WebAdapter
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Plan", description = "계획 미리보기·확정·조회 (플래너 명세 §11)")
public class PlanController {

    private final PlanAccessService planAccessService;
    private final PlanRepository planRepository;
    private final PlanStepRepository planStepRepository;
    private final PlanCalculationService planCalculationService;
    private final PlanConfirmService planConfirmService;
    private final PlanStepExecutionService planStepExecutionService;
    private final PlanAllocationGuard planAllocationGuard;
    private final PlanScenarioService planScenarioService;
    private final PlanApplyService planApplyService;

    public PlanController(
            PlanAccessService planAccessService,
            PlanRepository planRepository,
            PlanStepRepository planStepRepository,
            PlanCalculationService planCalculationService,
            PlanConfirmService planConfirmService,
            PlanStepExecutionService planStepExecutionService,
            PlanAllocationGuard planAllocationGuard,
            PlanScenarioService planScenarioService,
            PlanApplyService planApplyService) {
        this.planAccessService = requireNonNull(planAccessService, "planAccessService");
        this.planRepository = requireNonNull(planRepository, "planRepository");
        this.planStepRepository = requireNonNull(planStepRepository, "planStepRepository");
        this.planCalculationService = requireNonNull(planCalculationService, "planCalculationService");
        this.planConfirmService = requireNonNull(planConfirmService, "planConfirmService");
        this.planStepExecutionService = requireNonNull(planStepExecutionService, "planStepExecutionService");
        this.planAllocationGuard = requireNonNull(planAllocationGuard, "planAllocationGuard");
        this.planScenarioService = requireNonNull(planScenarioService, "planScenarioService");
        this.planApplyService = requireNonNull(planApplyService, "planApplyService");
    }

    @Operation(
            summary = "계획 미리보기",
            description = "목표 조건으로 계획을 계산한다. 아무것도 저장하지 않는다 — 활성 계획은 그대로다"
                    + "(명세 §21-9). goal_id 를 주면 저장된 목표의 조건을 쓰고, 주지 않으면 요청 본문의"
                    + " 조건으로 계산한다(명세 §12 장면 3·4).")
    @PostMapping("/plans/preview")
    public ApiResponse<PlanResponse> preview(
            @CurrentUser UUID userId,
            @Valid @RequestBody PlanRequest request) {
        PlanInput input = resolveInput(userId, request);
        planAllocationGuard.requireAllocatable(
                userId, input.currencyCode(), input.allocatedHoldingAmount(), parseGoalId(request));

        PlanDraft draft = planCalculationService.calculate(userId, input);
        return ApiResponse.of(PlanResponseMapper.toPreviewResponse(draft, request.goalId()));
    }

    @Operation(
            summary = "계획 확정·저장",
            description = "계산 결과를 새 버전으로 저장하고 활성화한다. 기존 활성 계획은 superseded 로"
                    + " 내려가고 새 계획 id 를 superseded_by 에 남긴다(명세 §18·§21-10).")
    @PostMapping("/goals/{id}/plans")
    public ApiResponse<PlanResponse> createPlan(
            @CurrentUser UUID userId,
            @PathVariable String id,
            @Valid @RequestBody PlanRequest request) {
        UUID goalId = UUID.fromString(id);
        Goal goal = planAccessService.requireGoalOwner(userId, goalId);

        PlanInput input = PlanInput.from(goal);
        planAllocationGuard.requireAllocatable(
                userId, input.currencyCode(), input.allocatedHoldingAmount(), goalId);

        PlanDraft draft = planCalculationService.calculate(userId, input);
        Plan saved = planConfirmService.confirm(goalId, draft, request.goalId() == null ? null : "재계산");

        List<PlanStep> steps = planStepRepository.findByPlan_IdOrderBySeqAsc(saved.getId());
        return ApiResponse.of(PlanResponseMapper.toPlanResponse(saved, steps, draft.goal()));
    }

    @Operation(
            summary = "계획 버전 이력",
            description = "목표의 모든 계획 버전을 최신순으로 반환한다. 과거 버전은 지우지 않는다 —"
                    + " 완료 회차 기록이 거기 남아 있다(명세 §21-11).")
    @GetMapping("/goals/{id}/plans")
    public ApiResponse<PlanVersionListResponse> listPlanVersions(
            @CurrentUser UUID userId,
            @PathVariable String id) {
        UUID goalId = UUID.fromString(id);
        planAccessService.requireGoalOwner(userId, goalId);

        return ApiResponse.of(
                PlanVersionListResponse.from(planRepository.findByGoal_IdOrderByVersionDesc(goalId)));
    }

    @Operation(
            summary = "활성 계획과 회차",
            description = "현재 적용 중인 계획을 반환한다. 활성 계획이 없으면 404 다 — 가짜 Curve 를"
                    + " 만들지 않는다(명세 §20).")
    @GetMapping("/goals/{id}/plans/active")
    public ApiResponse<PlanResponse> getActivePlan(
            @CurrentUser UUID userId,
            @PathVariable String id) {
        UUID goalId = UUID.fromString(id);
        Goal goal = planAccessService.requireGoalOwner(userId, goalId);

        Plan activePlan = planRepository.findFirstByGoal_IdAndStatus(goalId, PlanStatus.ACTIVE)
                .orElseThrow(() -> new NotFoundException(
                        "활성 계획이 없습니다. 계획을 먼저 만들어 주세요."));

        return ApiResponse.of(toStoredResponse(activePlan, goal));
    }

    @Operation(
            summary = "계획 버전 상세",
            description = "특정 계획 버전의 회차까지 반환한다. 과거 버전의 완료 기록을 확인할 때 쓴다"
                    + "(명세 §18·§21-11).")
    @GetMapping("/plans/{id}")
    public ApiResponse<PlanResponse> getPlan(
            @CurrentUser UUID userId,
            @PathVariable String id) {
        Plan plan = planAccessService.requirePlanOwner(userId, UUID.fromString(id));
        return ApiResponse.of(toStoredResponse(plan, plan.getGoal()));
    }

    @Operation(
            summary = "회차 완료 기록",
            description = "실행 금액·환율·실행일을 기록한다. 같은 execution_key 로 재요청하면 두 번"
                    + " 반영되지 않고 첫 결과를 그대로 돌려준다(명세 §14·§21-12).")
    @PostMapping("/plans/{id}/steps/{seq}/complete")
    public ApiResponse<StepCompleteResponse> completeStep(
            @CurrentUser UUID userId,
            @PathVariable String id,
            @PathVariable int seq,
            @Valid @RequestBody StepCompleteRequest request) {
        UUID planId = UUID.fromString(id);
        Plan plan = planAccessService.requirePlanOwner(userId, planId);

        PlanStepExecutionService.CompleteResult result = planStepExecutionService.completeStep(
                planId,
                seq,
                plan.getGoal().getTargetAmount(),
                request.executedAmount(),
                request.executedRate(),
                request.executedDate(),
                request.executionKey());

        return ApiResponse.of(StepCompleteResponse.from(result));
    }

    @Operation(
            summary = "회차 건너뛰기",
            description = "건너뛴 뒤의 변경 계획을 미리보기로 반환한다. 계획을 즉시 덮어쓰지 않는다 —"
                    + " 적용은 사용자 승인을 거친다(명세 §15·§21-9).")
    @PostMapping("/plans/{id}/steps/{seq}/skip")
    public ApiResponse<StepSkipResponse> skipStep(
            @CurrentUser UUID userId,
            @PathVariable String id,
            @PathVariable int seq) {
        UUID planId = UUID.fromString(id);
        Plan plan = planAccessService.requirePlanOwner(userId, planId);

        return ApiResponse.of(StepSkipResponse.from(
                planStepExecutionService.previewSkip(planId, seq, plan.getGoal())));
    }

    @Operation(
            summary = "상황 변화 미리보기",
            description = "환율·예산·목표·보유 변화로 계획을 다시 계산해 현재 계획과의 차이를 반환한다."
                    + " 활성 계획은 바뀌지 않는다 — 결과는 draft 로 저장되고, 적용은 draft_plan_id 로"
                    + " apply 를 호출해야 일어난다(명세 §16·§17·§21-9).")
    @PostMapping("/plans/{id}/scenarios/preview")
    public ApiResponse<ScenarioPreviewResponse> previewScenario(
            @CurrentUser UUID userId,
            @PathVariable String id,
            @Valid @RequestBody ScenarioPreviewRequest request) {
        UUID planId = UUID.fromString(id);
        planAccessService.requirePlanOwner(userId, planId);

        return ApiResponse.of(ScenarioPreviewResponse.from(
                planScenarioService.preview(userId, planId, request.toInput())));
    }

    @Operation(
            summary = "변경 계획 적용",
            description = "미리보기가 만든 draft 계획을 활성으로 승격한다. 버전이 오르고 이전 활성 계획은"
                    + " superseded 로 내려가며, 완료된 과거 회차는 새 버전에 보존된다"
                    + "(명세 §18·§21-10·§21-11). 다른 변경안이 먼저 적용됐으면 409.")
    @PostMapping("/plans/{id}/apply")
    public ApiResponse<PlanResponse> applyPlan(
            @CurrentUser UUID userId,
            @PathVariable String id) {
        UUID draftPlanId = UUID.fromString(id);
        planAccessService.requirePlanOwner(userId, draftPlanId);

        Plan applied = planApplyService.apply(draftPlanId);
        return ApiResponse.of(toStoredResponse(applied, applied.getGoal()));
    }

    // ── 내부 ──────────────────────────────────────────────────────────────

    /**
     * 계산 입력을 정한다. {@code goal_id} 가 있으면 저장된 목표가 우선이다 — 저장된 조건과
     * 다른 값으로 계산해 보여주면 사용자가 본 계획과 실제 목표가 어긋난다.
     */
    private PlanInput resolveInput(UUID userId, PlanRequest request) {
        if (request.goalId() == null || request.goalId().isBlank()) {
            return new PlanInput(
                    request.goalType(),
                    request.purpose(),
                    request.currencyCode(),
                    request.allocatedHoldingAmount(),
                    request.targetAmount(),
                    request.targetDate(),
                    request.recurringBudgetAmount() != null
                            ? request.recurringBudgetAmount() : request.budgetAmount(),
                    request.budgetPeriod(),
                    request.recurInterval() != null ? request.recurInterval() : request.preferredCadence(),
                    request.startDate(),
                    request.reviewHorizonMonths());
        }
        UUID goalId = UUID.fromString(request.goalId());
        return PlanInput.from(planAccessService.requireGoalOwner(userId, goalId));
    }

    private UUID parseGoalId(PlanRequest request) {
        return request.goalId() == null || request.goalId().isBlank()
                ? null : UUID.fromString(request.goalId());
    }

    private PlanResponse toStoredResponse(Plan plan, Goal goal) {
        List<PlanStep> steps = planStepRepository.findByPlan_IdOrderBySeqAsc(plan.getId());
        return PlanResponseMapper.toPlanResponse(plan, steps, storedGoalSummary(plan, goal));
    }

    /** 저장된 계획의 목표 요약. 남은 금액은 완료된 회차의 실행액을 반영해 다시 센다 (명세 §14). */
    private PlanDraft.GoalSummary storedGoalSummary(Plan plan, Goal goal) {
        double executed = planStepRepository.findByPlan_IdOrderBySeqAsc(plan.getId()).stream()
                .mapToDouble(PlanStep::getExecutedAmount)
                .sum();
        double held = goal.getAllocatedHoldingAmount() + executed;
        return new PlanDraft.GoalSummary(
                goal.getGoalType(),
                goal.getPurpose(),
                goal.getCurrencyCode(),
                java.math.BigDecimal.valueOf(goal.getTargetAmount()),
                goal.isRecurring() ? goal.getBudgetAmount() : null,
                java.math.BigDecimal.valueOf(held),
                java.math.BigDecimal.valueOf(Math.max(goal.getTargetAmount() - held, 0.0)),
                goal.getTargetDate(),
                goal.getPriorityConstraint());
    }
}
