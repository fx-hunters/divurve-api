package com.divurve.api.dto.fit;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;

/**
 * 성향과 현재 노출의 관계 응답 (API 명세 v2 §5.5 {@code GET /fit}).
 *
 * <p>⚠️ {@code relation} 은 <b>코드와 사실값만</b> 내려보낸다. "적합"·"부적합"은 물론 점수나 등급도
 * 내리지 않는다(FR-FT-04). 문장화는 {@code POST /ai/explain} 이 담당한다. v1 의 {@code suggestions}
 * ("분산 투자를 고려하세요")는 FR-FT-04·FR-FT-06 위반이라 삭제했다.
 */
@Schema(description = "성향·집중도·둘의 관계(사실값)")
public record FitResponse(
        @Schema(description = "위험성향 블록")
        RiskProfile riskProfile,

        @Schema(description = "주력 통화 집중도")
        Concentration concentration,

        @Schema(description = "성향과 집중도의 관계. 코드와 사실값만")
        Relation relation,

        @Schema(description = "기준선의 성격 고지",
                example = "참고 기준선은 MVP 가설값이며 통계적으로 검증된 배분 기준이 아닙니다.")
        String basisNote) {

    /** 위험성향 요약 (명세 §5.1 의 부분집합). */
    @Schema(description = "위험성향")
    public record RiskProfile(
            @Schema(description = "진단 상태", example = "simple_done",
                    allowableValues = {"not_measured", "simple_done", "detail_done"})
            String status,

            @Schema(description = "대표 유형 코드. 미측정이면 null", example = "balanced", nullable = true)
            String grade,

            @Schema(description = "대표 유형 한글 표기. 미측정이면 null",
                    example = "균형항로형", nullable = true)
            String gradeLabel,

            @Schema(description = "대표 유형 확정일. 미측정이면 null",
                    example = "2026-09-01", nullable = true)
            LocalDate diagnosedOn) {
    }

    /** 집중도 진단 (명세 §5.5). */
    @Schema(name = "FitConcentration",
            description = "성향-집중도 관계 판정에 쓰이는 집중도 (Relation.facts 와 짝을 이룬다). "
                    + "XrayResponse.Concentration·FitPreviewResponse.Concentration 과 필드 구성이 달라 "
                    + "이름을 분리했다(이슈 #88)")
    public record Concentration(
            @Schema(description = "주력 통화. 외화자산이 없으면 null", example = "USD", nullable = true)
            String topCurrencyCode,

            @Schema(description = "주력 통화 비중 (0~1). 외화자산이 없으면 null",
                    example = "0.6388", nullable = true)
            Double share,

            @Schema(description = "성향별 참고 기준선. 미측정이면 null", example = "0.6", nullable = true)
            Double threshold,

            @Schema(description = "판정 상태", example = "above_threshold",
                    allowableValues = {"above_threshold", "within_threshold", "unknown"})
            String status) {
    }

    /** 성향과 집중도의 관계. */
    @Schema(description = "관계 (코드 + 사실값)")
    public record Relation(
            @Schema(description = "관계 코드", example = "concentration_above_profile",
                    allowableValues = {"concentration_above_profile",
                            "concentration_within_profile", "risk_profile_not_measured"})
            String code,

            @Schema(description = "판정 근거가 된 사실값")
            Facts facts) {
    }

    /** 관계 판정의 사실값. 기준선이 없으면 모두 null 이다. */
    @Schema(description = "사실값")
    public record Facts(
            @Schema(description = "주력 통화 비중", example = "0.6388", nullable = true) Double share,
            @Schema(description = "성향별 기준선", example = "0.6", nullable = true) Double threshold,
            @Schema(description = "비중 − 기준선", example = "0.0388", nullable = true) Double gapPp) {
    }
}
