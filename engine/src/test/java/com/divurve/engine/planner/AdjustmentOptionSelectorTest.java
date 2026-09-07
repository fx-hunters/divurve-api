package com.divurve.engine.planner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * {@link AdjustmentOptionSelector} — 조정 선택지 순서 (플래너 명세 §15·§17).
 *
 * <p>핵심은 두 가지다 — 선택지를 <b>감추지 않는다</b>는 것과, 사용자의 우선 조건을 깨는 선택지가
 * <b>앞에 오지 않는다</b>는 것.
 */
@DisplayName("AdjustmentOptionSelector")
class AdjustmentOptionSelectorTest {

    private final AdjustmentOptionSelector selector = new AdjustmentOptionSelector();

    @ParameterizedTest
    @EnumSource(PriorityDimension.class)
    @DisplayName("우선 조건이 무엇이든 네 선택지를 모두 낸다 — 감추면 막다른 화면이 된다")
    void orderedFor_AlwaysReturnsEveryOption(PriorityDimension priority) {
        assertThat(selector.orderedFor(priority))
                .containsExactlyInAnyOrder(AdjustmentOption.values());
    }

    @ParameterizedTest
    @EnumSource(PriorityDimension.class)
    @DisplayName("우선 조건을 깨는 선택지는 일시 정지 바로 앞으로 밀린다 — 명세 §17")
    void orderedFor_DemotesTheOptionThatBreaksPriority(PriorityDimension priority) {
        List<AdjustmentOption> ordered = selector.orderedFor(priority);

        AdjustmentOption breaking = ordered.get(ordered.size() - 2);
        assertThat(breaking.breaks(priority)).isTrue();
        assertThat(ordered.get(ordered.size() - 1)).isEqualTo(AdjustmentOption.PAUSE_PLAN);
    }

    @ParameterizedTest
    @EnumSource(PriorityDimension.class)
    @DisplayName("앞의 두 선택지는 우선 조건을 깨지 않는다")
    void orderedFor_LeadsWithNonBreakingOptions(PriorityDimension priority) {
        List<AdjustmentOption> ordered = selector.orderedFor(priority);

        assertThat(ordered.subList(0, 2))
                .allSatisfy(option -> assertThat(option.breaks(priority)).isFalse());
    }

    @Test
    @DisplayName("예산 우선이면 회차 예산 변경이 뒤로 간다")
    void orderedFor_Budget() {
        assertThat(selector.orderedFor(PriorityDimension.BUDGET)).containsExactly(
                AdjustmentOption.CHANGE_TARGET_AMOUNT,
                AdjustmentOption.CHANGE_TARGET_DATE,
                AdjustmentOption.CHANGE_ROUND_BUDGET,
                AdjustmentOption.PAUSE_PLAN);
    }

    @Test
    @DisplayName("금액 우선이면 목표 금액 변경이 뒤로 간다")
    void orderedFor_Amount() {
        assertThat(selector.orderedFor(PriorityDimension.AMOUNT)).containsExactly(
                AdjustmentOption.CHANGE_ROUND_BUDGET,
                AdjustmentOption.CHANGE_TARGET_DATE,
                AdjustmentOption.CHANGE_TARGET_AMOUNT,
                AdjustmentOption.PAUSE_PLAN);
    }

    @Test
    @DisplayName("날짜 우선이면 목표 날짜 변경이 뒤로 간다")
    void orderedFor_Date() {
        assertThat(selector.orderedFor(PriorityDimension.DATE)).containsExactly(
                AdjustmentOption.CHANGE_ROUND_BUDGET,
                AdjustmentOption.CHANGE_TARGET_AMOUNT,
                AdjustmentOption.CHANGE_TARGET_DATE,
                AdjustmentOption.PAUSE_PLAN);
    }

    @Test
    @DisplayName("같은 우선 조건이면 순서가 흔들리지 않는다 — 화면의 선택지가 요청마다 바뀌면 안 된다")
    void orderedFor_IsStable() {
        assertThat(selector.orderedFor(PriorityDimension.AMOUNT))
                .isEqualTo(selector.orderedFor(PriorityDimension.AMOUNT));
    }

    @Test
    @DisplayName("일시 정지는 아무 조건도 포기하지 않는다")
    void pausePlan_SacrificesNothing() {
        assertThat(AdjustmentOption.PAUSE_PLAN.sacrifices()).isNull();
        assertThat(AdjustmentOption.PAUSE_PLAN.breaks(PriorityDimension.AMOUNT)).isFalse();
        assertThat(AdjustmentOption.CHANGE_TARGET_AMOUNT.sacrifices())
                .isEqualTo(PriorityDimension.AMOUNT);
    }

    @Test
    @DisplayName("우선 조건 없이는 정렬하지 않는다")
    void orderedFor_NullPriority_Throws() {
        assertThatThrownBy(() -> selector.orderedFor(null))
                .isInstanceOf(NullPointerException.class);
    }
}
