package com.divurve.api.dto.admin;

import com.divurve.domain.ai.AiCallLogQueryService;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.util.List;

/**
 * 관리자 AI 사용량 집계 응답 (이슈 #143).
 *
 * <p><b>비용 금액은 담지 않는다.</b> 토큰 수까지만 낸다 — 모델별 단가는 개정되고 어떤 단가를
 * 적용할지는 팀이 정할 문제다. 금액을 서버가 만들면 그 결정이 코드에 묻힌다.
 */
public record AdminAiUsageSummaryResponse(List<AdminAiUsageBucket> buckets) {

    /** 서비스 결과를 응답 형태로 옮긴다. */
    public static AdminAiUsageSummaryResponse from(
            List<AiCallLogQueryService.UsageBucket> buckets) {
        return new AdminAiUsageSummaryResponse(
                buckets.stream().map(AdminAiUsageBucket::from).toList());
    }

    /**
     * 하루·용도·모델별 집계 한 칸.
     *
     * @param day   호출 일자 (<b>UTC 기준</b>). 표시 시간대는 화면의 선택이다 — 서버가 로컬
     *              시간대를 가정하면 서버·DB·브라우저가 각자 다른 하루 경계를 쓴다
     * @param model LLM 을 부르지 않은 요청만 있었던 칸은 {@code null} 이다
     */
    public record AdminAiUsageBucket(
            LocalDate day,
            @Schema(example = "narrate", allowableValues = {"narrate", "extract"})
            String purpose,
            @Schema(example = "claude-opus-5",
                    description = "호출한 모델 ID. 설정으로 바뀌는 동적 값이며, LLM 을 부르지 "
                            + "않은 요청만 있었던 칸에서는 null 이다.")
            String model,
            long calls,
            long inputTokens,
            long outputTokens) {

        static AdminAiUsageBucket from(AiCallLogQueryService.UsageBucket bucket) {
            return new AdminAiUsageBucket(
                    bucket.day(),
                    bucket.purpose(),
                    bucket.model(),
                    bucket.calls(),
                    bucket.inputTokens(),
                    bucket.outputTokens());
        }
    }
}
