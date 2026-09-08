package com.divurve.api.dto.ai;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * 엔진 결과 서술 응답 (POST /ai/explain, API 명세 v2 §5.12).
 * 검증 실패 시에도 200 을 유지하며 {@code explanation.fallback=true} 와 고정 템플릿을 담는다
 * (FR-AI-06, NFR-AI-03). 왜 폴백했는지는 {@code verification.fallback_reason} 이 말한다(이슈 #122).
 */
public record ExplainResponse(Explanation explanation, Verification verification) {

    /**
     * 서술 결과.
     *
     * @param sentences     서술 문장. {@code surface=forecast_summary} 는 항상 4개(FR-AI-04)
     * @param sentenceCount {@code sentences} 길이
     * @param explainLevel  반영된 설명 선호 ({@code user_settings} 에서 읽음, 요청 값이 아니다)
     * @param explainDomain 반영된 익숙한 설명 분야
     * @param fallback      검증 실패로 고정 템플릿을 냈는지
     */
    public record Explanation(
            List<String> sentences,
            @Schema(example = "4") int sentenceCount,
            @Schema(example = "standard") String explainLevel,
            @Schema(example = "dev") String explainDomain,
            boolean fallback) {
    }

    /**
     * 검증 결과 (§5 3·4단계)와 폴백 사유 (이슈 #122).
     *
     * <p><b>측정하지 않은 값을 채워 넣지 않는다.</b> 이전에는 폴백일 때 {@code numeric_match} 에
     * 상수 {@code true} 가 실려 "LLM 출력이 검증을 통과했다" 로 읽혔다 — 통과한 출력은 애초에
     * 존재하지 않았다. 지금은 검증 단계에 도달하지 못한 경로에서 {@code null} 이 나간다.
     *
     * <p>전역 {@code non_null} 설정을 이 레코드에서만 뒤집는다({@code ALWAYS}). 필드가 통째로
     * 사라지면 화면이 "검증 안 됨" 과 "필드 없음" 을 구분할 수 없어, 고치려던 문제가 그대로 남는다.
     *
     * @param numericMatch    서술의 모든 숫자가 요청 {@code facts} 로 설명되는지.
     *                        검증 단계에 도달하지 못했으면 {@code null}
     * @param regimeDisclosed 급변 구간 불확실성 안내가 문장에 들어갔는지(§5.1).
     *                        평시이면 검사 없이 {@code true}, 도달하지 못했으면 {@code null}
     * @param blockedPhrases  발견된 금지 표현. 없으면 빈 목록
     * @param fallbackReason  폴백 사유 — {@code provider_error} · {@code blocked_phrases} ·
     *                        {@code budget_exhausted} · {@code verification_failed} ·
     *                        {@code quota_user} · {@code quota_ip} · {@code quota_global}
     *                        (이슈 #140). 폴백이 아니면 {@code null}
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Verification(
            Boolean numericMatch,
            Boolean regimeDisclosed,
            List<String> blockedPhrases,
            @Schema(description = "폴백 사유. 폴백이 아니면 null",
                    allowableValues = {"provider_error", "blocked_phrases",
                        "budget_exhausted", "verification_failed",
                        "quota_user", "quota_ip", "quota_global"},
                    example = "provider_error")
            String fallbackReason) {
    }
}
