package com.divurve.engine.diversification;

import java.util.Objects;

/**
 * {@link DiversificationSimulator}의 비중 조정 계약 위반 (이슈 #89).
 *
 * <p>engine 은 Spring·JPA 는 물론 app 의 {@code common.exception} 도 의존하지 않으므로
 * (CLAUDE.md §3 "engine 이 app 에 의존하지 않는다"), 실패 원인을 이 예외의 {@link #reason()} 에
 * 구조화된 값으로 담아 올린다. 호출자(app 의 {@code FitService})는 이 값으로 어느 요청 필드가
 * 원인인지 판정한다 — 메시지 문자열을 파싱해 분기하면 메시지 문구가 바뀔 때 조용히 깨진다.
 */
public class DiversificationAdjustmentException extends IllegalArgumentException {

    /** 실패 원인 — 호출자가 이 값만으로 원인 필드를 판정할 수 있어야 한다. */
    public enum Reason {
        /** 조정 대상 통화가 현재 포트폴리오에 없다 — 원인은 대상 통화 지정이다. */
        UNKNOWN_CURRENCY,
        /** 조정 후 비중이 0~1 범위를 벗어났다 — 원인은 조정량이다. */
        SHARE_OUT_OF_RANGE,
        /** 외화자산 합계가 0 이하라 비중 자체를 정의할 수 없다 — 원인은 대상(포트폴리오)이다. */
        EMPTY_PORTFOLIO
    }

    private final Reason reason;

    public DiversificationAdjustmentException(String message, Reason reason) {
        super(message);
        this.reason = Objects.requireNonNull(reason, "reason은 null일 수 없습니다.");
    }

    public Reason reason() {
        return reason;
    }
}
