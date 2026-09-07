package com.divurve.api.dto.admin;

import com.divurve.domain.event.EconEventExtractPreviewService;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;

/**
 * 비정형 원문 → 구조화 추출 미리보기 결과 (이슈 #111). <b>저장되지 않는다.</b>
 *
 * <p>거부된 후보도 전부 담는다 — 이 화면의 주된 산출물은 통과한 이벤트가 아니라 "왜 걸렀는가" 다.
 */
public record AdminExtractPreviewResponse(
        @Schema(description = "응답한 추출기 구현. NoOpEconEventExtractor 면 추출기가 꺼져 있다는 뜻",
                example = "ClaudeEconEventExtractor")
        String extractor,
        int count,
        Instant previewedAt,
        List<Candidate> candidates) {

    public static AdminExtractPreviewResponse from(
            EconEventExtractPreviewService.PreviewResult result) {
        List<Candidate> candidates = result.candidates().stream().map(Candidate::from).toList();
        return new AdminExtractPreviewResponse(
                result.extractor(), candidates.size(), result.previewedAt(), candidates);
    }

    /**
     * 후보 하나와 검증 결과.
     *
     * <p>{@code eventDate}·{@code region}·{@code impact} 는 <b>검증 전 원시값</b>이다 —
     * 형식이 어긋난 문자열이나 null 도 그대로 실린다. 그것을 보는 것이 이 화면의 목적이다.
     */
    public record Candidate(
            String eventDate, String region, String title, Integer impact,
            boolean valid,
            @Schema(description = "거부 사유. 통과했으면 null") String rejectReason) {

        static Candidate from(EconEventExtractPreviewService.CandidateResult c) {
            return new Candidate(
                    c.eventDate(), c.region(), c.title(), c.impact(), c.valid(), c.rejectReason());
        }
    }
}
