package com.divurve.api.dto.xray;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.Map;

/**
 * 외화 비중·통화 노출·집중도·민감도 응답 (API 명세 v2 §5.3 {@code GET /xray}).
 *
 * <p>{@code krw_asset_krw} 가 v2 에서 추가됐다 — 외화 비중의 <b>분모</b>이며,
 * v1 에는 원화 자산 입력 경로가 없어 {@code fx_ratio} 가 항상 1.0 이었다.
 * v1 의 {@code upcoming_outflows}(항상 빈 배열)는 명세에 근거가 없어 삭제했다.
 *
 * <p>{@code sensitivity_1pct} 는 전역 SNAKE_CASE 전략이 숫자 앞에 밑줄을 넣지 않으므로
 * {@link JsonProperty} 로 명세의 키를 그대로 고정한다(이슈 #60).
 */
@Schema(description = "외화 비중·통화 노출·집중도·1퍼센트 민감도")
public record XrayResponse(
        @Schema(description = "총자산 = 원화 자산 + 외화 자산 (원)", example = "68400000")
        long totalAssetKrw,

        @Schema(description = "원화 자산 합계 (원). `/krw-assets` 입력의 합", example = "43680000")
        long krwAssetKrw,

        @Schema(description = "외화 자산 원화 환산 합계 (원)", example = "24720000")
        long fxAssetKrw,

        @Schema(description = "외화 비중 = 외화자산 ÷ 총자산 (0~1)", example = "0.3614")
        double fxRatio,

        @Schema(description = "통화별 노출. 원화 평가액 내림차순. 자산이 없으면 빈 배열(FR-CM-09)")
        List<Exposure> exposure,

        @Schema(description = "주력 통화 집중도 진단")
        Concentration concentration,

        @Schema(description = "환율 1퍼센트 변동 시 원화 평가금액 변화")
        @JsonProperty("sensitivity_1pct") Sensitivity sensitivity1pct,

        @Schema(description = "전일 대비 변화(원). 스냅샷이 없으면 null", example = "84000", nullable = true)
        Long dayChangeKrw,

        @Schema(description = "이 자산이 시드된 샘플인지. 화면의 '체험용 데이터' 배지 판정 근거다. "
                + "계정이 둘러보기인지(meta.is_demo)와는 다른 사실이며, 실연동이 없는 동안에는 "
                + "일반 가입 계정도 true 다(이슈 #108·#112)", example = "true")
        boolean isSampleData) {

    /** 통화별 노출 금액과 비중. */
    @Schema(description = "통화별 노출")
    public record Exposure(
            @Schema(description = "ISO 4217 통화코드", example = "USD") String currencyCode,
            @Schema(description = "원화 평가액", example = "15790000") long krw,
            @Schema(description = "외화자산 대비 비중 (0~1)", example = "0.6388") double share) {
    }

    /** 집중도 진단 (명세 §5.3). 성향 미측정이면 기준선이 없고 상태는 {@code unknown} 이다. */
    @Schema(description = "주력 통화 집중도")
    public record Concentration(
            @Schema(description = "주력 통화. 외화자산이 없으면 null", example = "USD", nullable = true)
            String topCurrencyCode,

            @Schema(description = "주력 통화 비중 (0~1). 외화자산이 없으면 null",
                    example = "0.6388", nullable = true)
            Double share,

            @Schema(description = "성향별 참고 기준선. 미측정이면 null", example = "0.6", nullable = true)
            Double threshold,

            @Schema(description = "기준선 출처. 미측정이면 null",
                    example = "risk_profile.balanced", nullable = true)
            String thresholdSource,

            @Schema(description = "판정 상태", example = "above_threshold",
                    allowableValues = {"above_threshold", "within_threshold", "unknown"})
            String status) {
    }

    /** 환율 1퍼센트 변동 민감도 (FR-XR-05). */
    @Schema(description = "환율 1퍼센트 민감도")
    public record Sensitivity(
            @Schema(description = "전 통화 합계 (원)", example = "247200") long totalKrw,
            @Schema(description = "통화코드 → 변화액(원)") Map<String, Long> byCurrency) {
    }
}
