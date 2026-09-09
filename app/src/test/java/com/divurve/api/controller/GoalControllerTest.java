package com.divurve.api.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.divurve.api.dto.goal.GoalCreateRequest;
import com.divurve.api.dto.goal.GoalListResponse;
import com.divurve.api.dto.goal.GoalResponse;
import com.divurve.api.dto.goal.GoalUpdateRequest;
import com.divurve.common.response.ApiResponse;
import com.divurve.domain.goal.GoalCreateCommand;
import com.divurve.domain.goal.GoalService;
import com.divurve.domain.goal.GoalUpdateCommand;
import com.divurve.domain.goal.entity.Goal;
import com.divurve.domain.user.entity.User;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("GoalController 테스트")
class GoalControllerTest {

    @Mock
    private GoalService goalService;

    private GoalController goalController;

    private UUID currentUserId;
    private User owner;

    @BeforeEach
    void setUp() {
        goalController = new GoalController(goalService);
        currentUserId = UUID.randomUUID();
        owner = User.createDemo("test@example.com", "Test User");
    }

    @Test
    @DisplayName("목표 목록 조회 성공")
    void listGoalsSuccess() {
        Goal goal1 = Goal.builder(owner, "USD 목표", "deadline", "travel", "USD")
                .targetAmount(10000.0)
                .status("active")
                .build();
        goal1.setIdForTest(UUID.randomUUID());
        Goal goal2 = Goal.builder(owner, "EUR 목표", "recurring", "invest", "EUR")
                .targetAmount(5000.0)
                .status("active")
                .build();
        goal2.setIdForTest(UUID.randomUUID());

        when(goalService.listByOwner(currentUserId)).thenReturn(List.of(goal1, goal2));
        when(goalService.getHeldAmountByCurrency(currentUserId, "USD")).thenReturn(5000.0);
        when(goalService.getHeldAmountByCurrency(currentUserId, "EUR")).thenReturn(2000.0);

        ApiResponse<GoalListResponse> response = goalController.listGoals(currentUserId);

        assertThat(response.data()).isNotNull();
        assertThat(response.data().goals()).hasSize(2);
    }

    @Test
    @DisplayName("목표 목록 조회 (빈 결과)")
    void listGoalsEmpty() {
        when(goalService.listByOwner(currentUserId)).thenReturn(List.of());

        ApiResponse<GoalListResponse> response = goalController.listGoals(currentUserId);

        assertThat(response.data()).isNotNull();
        assertThat(response.data().goals()).isEmpty();
    }

    @Test
    @DisplayName("목표 생성 성공")
    void createGoalSuccess() {
        GoalCreateRequest request = new GoalCreateRequest(
                "USD 목표",
                "deadline",
                "travel",
                "USD",
                10000.0,
                LocalDate.of(2026, 12, 31),
                null,
                0,
                "KRW",
                null,
                false,
                3000.0,
                "monthly",
                "date",
                null,
                null);

        Goal createdGoal = Goal.builder(owner, "USD 목표", "deadline", "travel", "USD")
                .targetAmount(10000.0)
                .targetDate(LocalDate.of(2026, 12, 31))
                .budgetAmount(0)
                .budgetCurrencyCode("KRW")
                .isSpeculative(false)
                .status("active")
                .build();
        createdGoal.setIdForTest(UUID.randomUUID());

        // 커맨드를 값으로 정확히 맞춘다 — 컨트롤러가 요청 필드를 하나라도 흘리면 스텁이 어긋나
        // null 이 돌아오고 테스트가 깨진다. 이슈 #193 의 조용한 누락을 이 계층에서도 막는다.
        GoalCreateCommand expected = new GoalCreateCommand(
                "USD 목표",
                "deadline",
                "travel",
                "USD",
                10000.0,
                LocalDate.of(2026, 12, 31),
                null,
                0,
                "KRW",
                null,
                false,
                3000.0,
                "monthly",
                "date",
                null,
                null);
        when(goalService.create(currentUserId, expected)).thenReturn(createdGoal);
        when(goalService.getHeldAmountByCurrency(currentUserId, "USD")).thenReturn(0.0);

        ApiResponse<GoalResponse> response = goalController.createGoal(currentUserId, request);

        assertThat(response.data()).isNotNull();
        assertThat(response.data().name()).isEqualTo("USD 목표");
        assertThat(response.data().currencyCode()).isEqualTo("USD");
        assertThat(response.data().status()).isEqualTo("active");
    }

    @Test
    @DisplayName("목표 상세 조회 성공")
    void getGoalSuccess() {
        UUID goalId = UUID.randomUUID();
        Goal goal = Goal.builder(owner, "USD 목표", "deadline", "travel", "USD")
                .targetAmount(10000.0)
                .status("active")
                .build();
        goal.setIdForTest(goalId);

        when(goalService.getByIdAndOwner(currentUserId, goalId)).thenReturn(goal);
        when(goalService.getHeldAmountByCurrency(currentUserId, "USD")).thenReturn(5000.0);

        ApiResponse<GoalResponse> response = goalController.getGoal(currentUserId, goalId.toString());

        assertThat(response.data()).isNotNull();
        assertThat(response.data().name()).isEqualTo("USD 목표");
        assertThat(response.data().heldAmount()).isEqualTo(5000.0);
    }

    @Test
    @DisplayName("목표 수정 성공")
    void updateGoalSuccess() {
        UUID goalId = UUID.randomUUID();
        GoalUpdateRequest request = new GoalUpdateRequest(
                "수정된 목표",
                20000.0,
                LocalDate.of(2027, 12, 31),
                200000L,
                "year",
                true,
                1500.0,
                "biweekly",
                "budget",
                LocalDate.of(2026, 11, 1),
                12);

        Goal updatedGoal = Goal.builder(owner, "수정된 목표", "deadline", "travel", "USD")
                .targetAmount(20000.0)
                .targetDate(LocalDate.of(2027, 12, 31))
                .budgetAmount(200000)
                .budgetPeriod("year")
                .isSpeculative(true)
                .status("active")
                .build();
        updatedGoal.setIdForTest(goalId);

        // 커맨드를 값으로 정확히 맞춘다 — 컨트롤러가 요청 필드를 하나라도 흘리면 스텁이 어긋나
        // null 이 돌아오고 테스트가 깨진다.
        GoalUpdateCommand expected = new GoalUpdateCommand(
                "수정된 목표",
                20000.0,
                LocalDate.of(2027, 12, 31),
                200000L,
                "year",
                true,
                1500.0,
                "biweekly",
                "budget",
                LocalDate.of(2026, 11, 1),
                12);
        when(goalService.update(currentUserId, goalId, expected)).thenReturn(updatedGoal);
        when(goalService.getHeldAmountByCurrency(currentUserId, "USD")).thenReturn(5000.0);

        ApiResponse<GoalResponse> response = goalController.updateGoal(
                currentUserId,
                goalId.toString(),
                request);

        assertThat(response.data()).isNotNull();
        assertThat(response.data().name()).isEqualTo("수정된 목표");
        assertThat(response.data().targetAmount()).isEqualTo(20000.0);
    }

    @Test
    @DisplayName("목표 삭제 성공")
    void deleteGoalSuccess() {
        UUID goalId = UUID.randomUUID();

        doNothing().when(goalService).delete(currentUserId, goalId);

        ApiResponse<Void> response = goalController.deleteGoal(currentUserId, goalId.toString());

        assertThat(response).isNotNull();
    }
}
