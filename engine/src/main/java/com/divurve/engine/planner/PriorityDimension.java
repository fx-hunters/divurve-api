package com.divurve.engine.planner;

/**
 * 상황이 바뀌었을 때 우선 유지할 조건 (플래너 명세 §5.1·§17).
 *
 * <p>domain 의 {@code PriorityConstraint} 문자열 상수와 같은 어휘를 engine 쪽에서 타입으로 받는다.
 * engine 은 app 에 의존할 수 없으므로 상수를 공유하지 못한다 — 대신 domain 이 문자열을 이 열거로
 * 옮겨 넘긴다. 문자열을 그대로 받으면 오타가 컴파일을 통과해 조정 선택지 순서가 조용히 어긋난다.
 */
public enum PriorityDimension {

    /** 목표 외화 금액을 유지한다. */
    AMOUNT,

    /** 목표 날짜를 유지한다. */
    DATE,

    /** 원화 예산을 넘기지 않는다. */
    BUDGET
}
