package com.divurve.api.dto.ai;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Map;

/**
 * 엔진 결과 서술 요청 (POST /ai/explain, API 명세 v2 §5.12).
 * {@code facts} 는 엔진이 계산한 검증된 수치만 담는다 — AI 는 이 값만 그라운딩으로 쓴다(FR-AI-02).
 * {@code regime} 을 {@code facts} 에 포함하면 급변 상태 안내가 강화된다(FR-SF-03).
 */
public record ExplainRequest(
        @Schema(description = "서술 대상 화면. 닫힌 어휘이며 화면마다 문장 수가 고정이다 — "
                + "forecast_summary(4문장) · home_market_summary · home_fx_status · home_goals · "
                + "home_calendar · xray_exposure · xray_fitness(각 3문장). 목록 밖 값은 400",
                example = "forecast_summary")
        String surface,
        @Schema(description = "엔진이 계산한 검증된 사실. AI 의 유일한 그라운딩 소스",
                example = "{\"pair_code\":\"USDKRW\",\"current_rate\":1382.40,"
                        + "\"interval_80\":{\"lo\":1346.0,\"hi\":1431.0},"
                        + "\"vol_percentile_5y\":0.72,\"per_1pct_krw\":157900,\"regime\":\"elevated\"}")
        Map<String, Object> facts) {
}
