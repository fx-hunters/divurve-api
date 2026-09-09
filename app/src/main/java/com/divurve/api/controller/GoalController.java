package com.divurve.api.controller;

import static java.util.Objects.requireNonNull;

import com.divurve.api.config.auth.CurrentUser;
import com.divurve.api.dto.goal.GoalCreateRequest;
import com.divurve.api.dto.goal.GoalListResponse;
import com.divurve.api.dto.goal.GoalResponse;
import com.divurve.api.dto.goal.GoalUpdateRequest;
import com.divurve.common.architecture.WebAdapter;
import com.divurve.common.response.ApiResponse;
import com.divurve.domain.goal.GoalCreateCommand;
import com.divurve.domain.goal.GoalService;
import com.divurve.domain.goal.GoalUpdateCommand;
import com.divurve.domain.goal.entity.Goal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 목표 엔드포인트 (API 명세 v2 §3, 플래너 명세 §5).
 * 소유자 기준 필터를 적용한다 (NFR-SE-03).
 *
 * <p><b>Route 기능 플래그를 제거했다</b> (이슈 #84). 플래그는 요구사항 v2 §4.12 가 Route 의
 * 계산 규칙을 전부 미확정으로 둔 동안 확정되지 않은 수치가 API 로 새어 나가지 않게 막던
 * 장치였다. 플래너 명세가 계산 정책을 확정하면서 막을 대상이 사라졌으므로, 쓰기 3종의 501 과
 * {@code GET /goals} 의 빈 배열 응답을 함께 걷어냈다.
 *
 * <p>소유자는 {@link CurrentUser} 로 주입받는다. 이슈 #50 이전에는 하드코딩된 UUID 상수를
 * 5개 메서드가 공유해, <b>모든 사용자가 같은 목표 목록을 보고 서로의 목표를 수정·삭제할 수 있었다</b>.
 */
@WebAdapter
@RestController
@RequestMapping("/api/v1/goals")
@Tag(name = "Goal", description = "목표 CRUD")
public class GoalController {

    private final GoalService goalService;

    public GoalController(GoalService goalService) {
        this.goalService = requireNonNull(goalService, "goalService");
    }

    @Operation(summary = "목표 목록")
    @GetMapping
    public ApiResponse<GoalListResponse> listGoals(@CurrentUser UUID userId) {
        List<Goal> goals = goalService.listByOwner(userId);
        List<GoalResponse> responses = goals.stream()
                .map(goal -> toGoalResponse(userId, goal))
                .toList();
        return ApiResponse.of(new GoalListResponse(responses));
    }

    @Operation(summary = "목표 생성")
    @PostMapping
    public ApiResponse<GoalResponse> createGoal(
            @CurrentUser UUID userId,
            @Valid @RequestBody GoalCreateRequest request) {
        Goal goal = goalService.create(userId, new GoalCreateCommand(
                request.name(),
                request.kind(),
                request.purpose(),
                request.currencyCode(),
                request.targetAmount(),
                request.targetDate(),
                request.recurInterval(),
                request.budgetAmount(),
                request.budgetCurrencyCode(),
                request.budgetPeriod(),
                request.isSpeculative(),
                request.allocatedHoldingAmount(),
                request.preferredCadence(),
                request.priorityConstraint(),
                request.startDate(),
                request.reviewHorizonMonths()));
        return ApiResponse.of(toGoalResponse(userId, goal));
    }

    @Operation(summary = "목표 상세")
    @GetMapping("/{id}")
    public ApiResponse<GoalResponse> getGoal(
            @CurrentUser UUID userId,
            @PathVariable String id) {
        UUID goalId = UUID.fromString(id);
        Goal goal = goalService.getByIdAndOwner(userId, goalId);
        return ApiResponse.of(toGoalResponse(userId, goal));
    }

    @Operation(summary = "목표 수정")
    @PutMapping("/{id}")
    public ApiResponse<GoalResponse> updateGoal(
            @CurrentUser UUID userId,
            @PathVariable String id,
            @Valid @RequestBody GoalUpdateRequest request) {
        UUID goalId = UUID.fromString(id);
        Goal goal = goalService.update(userId, goalId, new GoalUpdateCommand(
                request.name(),
                request.targetAmount(),
                request.targetDate(),
                request.budgetAmount(),
                request.budgetPeriod(),
                request.isSpeculative(),
                request.allocatedHoldingAmount(),
                request.preferredCadence(),
                request.priorityConstraint(),
                request.startDate(),
                request.reviewHorizonMonths()));
        return ApiResponse.of(toGoalResponse(userId, goal));
    }

    @Operation(summary = "목표 삭제")
    @DeleteMapping("/{id}")
    public ApiResponse<Void> deleteGoal(
            @CurrentUser UUID userId,
            @PathVariable String id) {
        UUID goalId = UUID.fromString(id);
        goalService.delete(userId, goalId);
        return ApiResponse.of(null);
    }

    private GoalResponse toGoalResponse(UUID userId, Goal goal) {
        double heldAmount = goalService.getHeldAmountByCurrency(userId, goal.getCurrencyCode());
        return new GoalResponse(
                goal.getId().toString(),
                goal.getName(),
                goal.getKind(),
                goal.getPurpose(),
                goal.getCurrencyCode(),
                goal.getTargetAmount(),
                goal.getTargetDate(),
                goal.getRecurInterval(),
                goal.getBudgetAmount(),
                goal.getBudgetCurrencyCode(),
                goal.getBudgetPeriod(),
                goal.isSpeculative(),
                goal.getStatus(),
                heldAmount);
    }
}
