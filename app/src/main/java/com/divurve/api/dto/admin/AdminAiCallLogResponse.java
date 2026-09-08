package com.divurve.api.dto.admin;

import com.divurve.domain.ai.AiCallLogQueryService;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 관리자 AI 호출 로그 목록 응답 (이슈 #143).
 *
 * <p>Spring Data 의 {@code Page} 를 그대로 직렬화하지 않는다 — {@code AdminUserListResponse} 와
 * 같은 이유다(그 형태는 프레임워크 내부 구조라 API 계약이 버전에 묶인다).
 */
public record AdminAiCallLogResponse(
        List<AdminAiCall> items,
        @Schema(example = "0") int page,
        @Schema(example = "50") int size,
        long totalElements,
        int totalPages) {

    /** 서비스 결과를 응답 형태로 옮긴다. */
    public static AdminAiCallLogResponse from(AiCallLogQueryService.CallLogPage page) {
        return new AdminAiCallLogResponse(
                page.items().stream().map(AdminAiCall::from).toList(),
                page.page(),
                page.size(),
                page.totalElements(),
                page.totalPages());
    }

    /**
     * 호출 한 건.
     *
     * <p>중첩 record 이름을 {@code Call} 이 아니라 {@code AdminAiCall} 로 두는 이유 —
     * {@code DtoSchemaNameUniquenessTest}(이슈 #88)가 {@code api/dto} 전역에서 중첩 record 의
     * 단순명 충돌을 잡는다. 흔한 이름은 언젠가 다른 DTO 와 겹치고, 그때 이 파일이 아니라 상대
     * 파일에서 실패가 난다.
     *
     * @param userId                   요청한 사용자. 사용자가 삭제됐거나 배치 호출이면 {@code null}
     * @param model                    호출한 모델. LLM 을 부르지 않은 요청이면 {@code null}
     * @param cacheReadInputTokens     프롬프트 캐시에서 읽은 토큰. 캐싱을 쓰지 않으면 {@code null}
     * @param cacheCreationInputTokens 프롬프트 캐시에 쓴 토큰. 캐싱을 쓰지 않으면 {@code null}
     */
    public record AdminAiCall(
            UUID id,
            Instant requestedAt,
            UUID userId,
            boolean isDemo,
            @Schema(example = "narrate", allowableValues = {"narrate", "extract"})
            String purpose,
            @Schema(example = "forecast_summary") String surface,
            @Schema(example = "claude-opus-5",
                    description = "호출한 모델 ID. 설정(ANTHROPIC_MODEL)으로 바뀌는 동적 값이며, "
                            + "LLM 을 부르지 않은 요청(템플릿 응답·캐시 히트·쿼터 차단)에서는 null 이다.")
            String model,
            long inputTokens,
            long outputTokens,
            Long cacheReadInputTokens,
            Long cacheCreationInputTokens,
            @Schema(example = "success",
                    allowableValues = {"success", "fallback", "cache_hit", "quota_blocked", "error"})
            String outcome,
            @Schema(example = "provider_error",
                    allowableValues = {"provider_error", "blocked_phrases", "budget_exhausted",
                            "verification_failed", "quota_user", "quota_ip", "quota_global"})
            String fallbackReason,
            Integer latencyMs,
            String errorSummary) {

        static AdminAiCall from(AiCallLogQueryService.CallLogView view) {
            return new AdminAiCall(
                    view.id(),
                    view.requestedAt(),
                    view.userId(),
                    view.demo(),
                    view.purpose(),
                    view.surface(),
                    view.model(),
                    view.inputTokens(),
                    view.outputTokens(),
                    view.cacheReadInputTokens(),
                    view.cacheCreationInputTokens(),
                    view.outcome(),
                    view.fallbackReason(),
                    view.latencyMs(),
                    view.errorSummary());
        }
    }
}
