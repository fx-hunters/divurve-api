package com.divurve.api.dto.admin;

import com.divurve.domain.fx.FxRateIngestionService;
import com.divurve.domain.fx.FxRateRefreshService;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * 환율 수동 갱신 결과 (이슈 #111).
 *
 * <p>성공 여부만 알리는 것이 아니라 <b>무엇이 몇 건 반영됐는지</b>를 담는다. 이 화면의 존재
 * 이유가 "ECOS 연동이 정말 살아 있는가" 를 보는 것이므로, 조용히 0건이 되는 것이 가장 나쁘다.
 */
public record AdminRefreshResponse(
        @Schema(description = "실제로 비운 캐시 이름") List<String> evictedCaches,
        int totalUpserted,
        boolean hasFailure,
        Instant refreshedAt,
        long elapsedMs,
        List<PairResult> pairs) {

    public static AdminRefreshResponse from(FxRateRefreshService.RefreshReport report) {
        return new AdminRefreshResponse(
                report.evictedCaches(),
                report.totalUpserted(),
                report.hasFailure(),
                report.refreshedAt(),
                report.elapsedMs(),
                report.pairs().stream().map(PairResult::from).toList());
    }

    /**
     * 통화쌍 하나의 갱신 결과.
     *
     * @param pairCode      통화쌍 6자리
     * @param upserted      반영된 행 수
     * @param firstDate     반영 구간 시작일. 관측이 없으면 null
     * @param lastDate      반영 구간 끝일. 관측이 없으면 null
     * @param failureReason 실패 사유. 성공이면 null
     */
    public record PairResult(
            String pairCode, int upserted, LocalDate firstDate, LocalDate lastDate,
            String failureReason) {

        static PairResult from(FxRateIngestionService.PairResult r) {
            return new PairResult(
                    r.pairCode(), r.upserted(), r.firstDate(), r.lastDate(), r.failureReason());
        }
    }
}
