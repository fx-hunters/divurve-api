package com.divurve.api.dto.fit;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Map;

/**
 * 통화 비중 가정 전후의 변화 응답 (API 명세 v2 §5.6 {@code POST /fit/preview}).
 *
 * <p>🚫 v1 의 {@code suggested_goal}(서버가 특정 통화의 매수 목표를 제안)은 FR-FT-06 에 정면으로
 * 어긋나 삭제했다. 대신 {@code assumption} 문자열과 <b>변화값만</b> 내려보내고, 클라이언트가
 * "이 선택을 추가하면 USD 집중도가 63.9%에서 55.7%로 낮아지는 가정입니다" 형태로만 표현한다.
 * 포트폴리오 변동성({@code portfolio_vol})도 임의 상수(σ 0.12/0.14/0.10, ρ 0.5)에서 나오던 값이라 뺐다.
 *
 * <p>{@code sensitivity_1pct} 는 전역 SNAKE_CASE 전략이 숫자 앞에 밑줄을 넣지 않으므로
 * {@link JsonProperty} 로 명세의 키를 그대로 고정한다(이슈 #60).
 */
@Schema(description = "비중 가정 전후의 집중도·민감도 변화")
public record FitPreviewResponse(
        @Schema(description = "적용한 가정의 문장 표현",
                example = "외화자산 총액 24,720,000원을 고정한 채 JPY 비중만 10%p 높인 가정입니다.")
        String assumption,

        @Schema(description = "통화별 비중 (0~1)")
        Exposure exposure,

        @Schema(description = "집중도 가정 전후")
        Concentration concentration,

        @Schema(description = "환율 1퍼센트 민감도 가정 전후")
        @JsonProperty("sensitivity_1pct") Sensitivity sensitivity1pct) {

    /** 가정 전후의 통화별 비중. */
    @Schema(name = "ExposureComparison",
            description = "통화별 비중 전후 비교(before/after 맵). 단일 시점을 표현하는 "
                    + "XrayResponse.Exposure 와 구조가 달라 이름을 분리했다(이슈 #88)")
    public record Exposure(
            @Schema(description = "통화코드 → 비중") Map<String, Double> before,
            @Schema(description = "통화코드 → 비중") Map<String, Double> after) {
    }

    /** 가정 전후의 집중도. 기준선은 가정으로 바뀌지 않으므로 하나만 싣는다. */
    @Schema(name = "ConcentrationComparison",
            description = "집중도 전후 비교(before/after Snapshot + 공통 threshold). "
                    + "단일 시점을 표현하는 XrayResponse.Concentration·FitResponse.Concentration 과 "
                    + "구조가 달라 이름을 분리했다(이슈 #88)")
    public record Concentration(
            @Schema(description = "가정 전") Snapshot before,
            @Schema(description = "가정 후") Snapshot after,
            @Schema(description = "성향별 참고 기준선. 미측정이면 null", example = "0.6", nullable = true)
            Double threshold) {
    }

    /** 한 시점의 집중도. */
    @Schema(description = "집중도 시점")
    public record Snapshot(
            @Schema(description = "주력 통화", example = "USD", nullable = true) String topCurrencyCode,
            @Schema(description = "주력 통화 비중", example = "0.6388", nullable = true) Double share,
            @Schema(description = "판정 상태", example = "above_threshold",
                    allowableValues = {"above_threshold", "within_threshold", "unknown"})
            String status) {
    }

    /**
     * 가정 전후의 민감도.
     *
     * <p>각 맵은 명세 §5.6 예시대로 <b>통화코드 키와 {@code total_krw} 키를 한 객체에</b> 담는다
     * ({@code {"USD": 157900, ..., "total_krw": 247200}}). 재배분은 외화자산 총액을 보존하므로
     * {@code total_krw} 는 가정 전후가 같다.
     */
    @Schema(name = "SensitivityComparison",
            description = "민감도 전후 비교(before/after 맵, 통화코드 키 + total_krw). "
                    + "단일 시점을 표현하는 XrayResponse.Sensitivity 와 구조가 달라 "
                    + "이름을 분리했다(이슈 #88)")
    public record Sensitivity(
            @Schema(description = "가정 전 통화별 민감도와 total_krw") Map<String, Long> before,
            @Schema(description = "가정 후 통화별 민감도와 total_krw") Map<String, Long> after) {

        /** 민감도 맵의 합계 키. 통화코드와 섞이지 않도록 소문자 스네이크로 고정한다. */
        public static final String TOTAL_KEY = "total_krw";
    }
}
