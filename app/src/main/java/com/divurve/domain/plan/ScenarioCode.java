package com.divurve.domain.plan;

import com.divurve.common.exception.InvalidRequestException;
import java.util.Arrays;

/**
 * 계획을 다시 계산하게 만드는 상황 변화 (플래너 명세 §16).
 *
 * <p>닫힌 집합이다. 임의의 문자열을 받으면 프론트 오타가 조용히 "아무 변화 없음"으로 계산돼
 * 사용자는 바뀌지 않은 계획을 변경안으로 보게 된다 — 그래서 모르는 코드는 400 으로 돌려보낸다.
 *
 * <p>각 코드는 <b>변경 이유 코드</b>이기도 하다 (명세 §16 응답의 {@code change_reason_code}).
 * 별도 어휘를 두지 않는 이유는 둘이 언제나 일대일이기 때문이다 — 두 벌로 나누면 매핑 표가
 * 생기고, 표가 생기면 어긋난다.
 */
public enum ScenarioCode {

    /** 환율이 올랐다 — 같은 외화를 사는 데 원화가 더 든다. */
    RATE_UP(true),

    /** 환율이 내렸다. */
    RATE_DOWN(true),

    /** 회차를 건너뛰었다 (명세 §15 에서 이어진다). */
    STEP_SKIPPED(false),

    /** 회차 예산이 줄었다. */
    BUDGET_DECREASED(false),

    /** 목표 날짜가 바뀌었다. */
    TARGET_DATE_CHANGED(false),

    /** 목표 금액이 바뀌었다. */
    TARGET_AMOUNT_CHANGED(false),

    /** 보유 외화를 이 목표에 더 배정했다. */
    HOLDING_ADDED(false);

    private final boolean rateDriven;

    ScenarioCode(boolean rateDriven) {
        this.rateDriven = rateDriven;
    }

    /**
     * 환율 변동이 원인인 시나리오인지.
     *
     * <p>환율 시나리오는 <b>목표 조건을 바꾸지 않는다</b> — 조회 시점의 환율로 다시 계산할 뿐이다.
     * 사용자가 환율 숫자를 넘겨 "이 환율이면 어떻게 되나"를 묻는 형태는 명세에 없다. 가정한
     * 환율로 계산해 보여주면 그 값이 근거 있는 전망처럼 읽히기 때문이다 (§2 — 플래너는 조건부
     * 계획을 내놓지 환율을 예측하지 않는다).
     */
    public boolean isRateDriven() {
        return rateDriven;
    }

    /**
     * 요청 문자열을 코드로 옮긴다.
     *
     * @param code 대소문자 구분 없는 시나리오 코드
     * @return 해당 코드
     * @throws InvalidRequestException 닫힌 집합에 없는 값인 경우
     */
    public static ScenarioCode from(String code) {
        if (code == null || code.isBlank()) {
            throw new InvalidRequestException("scenario_code 는 필수입니다.", "scenario_code");
        }
        return Arrays.stream(values())
                .filter(value -> value.name().equalsIgnoreCase(code.trim()))
                .findFirst()
                .orElseThrow(() -> new InvalidRequestException(
                        "지원하지 않는 scenario_code 입니다: " + code, "scenario_code"));
    }
}
