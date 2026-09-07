package com.divurve.engine.planner;

/**
 * 계획을 그대로 둘 수 없을 때 사용자가 고를 수 있는 조정 선택지 (플래너 명세 §15).
 *
 * <p>불변조건 §21-8 — <b>시스템은 이 중 하나를 대신 고르지 않는다.</b> 예산을 넘거나 재분배할
 * 회차가 없으면 그 사실을 알리고 선택지를 제시할 뿐이다. 어느 조건을 포기할지는 사용자의 결정이다.
 *
 * <p>코드 문자열은 이미 {@code /plans/{id}/steps/{seq}/skip} 응답이 쓰고 있던 어휘를 그대로 쓴다 —
 * 프론트가 붙어 있는 값이라 이름을 바꾸면 브레이킹 체인지가 된다.
 */
public enum AdjustmentOption {

    /** 회차 예산을 바꾼다. {@link PriorityDimension#BUDGET} 을 포기하는 선택이다. */
    CHANGE_ROUND_BUDGET(PriorityDimension.BUDGET),

    /** 목표 외화 금액을 바꾼다. {@link PriorityDimension#AMOUNT} 를 포기하는 선택이다. */
    CHANGE_TARGET_AMOUNT(PriorityDimension.AMOUNT),

    /** 목표 날짜를 미룬다. {@link PriorityDimension#DATE} 를 포기하는 선택이다. */
    CHANGE_TARGET_DATE(PriorityDimension.DATE),

    /**
     * 계획을 일시 정지한다. 어떤 조건도 포기하지 않는 대신 진행을 멈춘다 — 그래서
     * {@code sacrifices} 가 {@code null} 이며, 우선 조건이 무엇이든 순서가 바뀌지 않는다.
     */
    PAUSE_PLAN(null);

    private final PriorityDimension sacrifices;

    AdjustmentOption(PriorityDimension sacrifices) {
        this.sacrifices = sacrifices;
    }

    /**
     * 이 선택지가 포기하는 조건. {@link #PAUSE_PLAN} 은 아무것도 포기하지 않아 {@code null} 이다.
     */
    public PriorityDimension sacrifices() {
        return sacrifices;
    }

    /** 주어진 우선 조건을 이 선택지가 깨뜨리는지 (명세 §17). */
    public boolean breaks(PriorityDimension priority) {
        return sacrifices != null && sacrifices == priority;
    }
}
