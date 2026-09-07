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
 *
 * <p>갱신 구간의 커버리지도 함께 담는다 (이슈 #116) — 반영 건수는 갱신이 돌았다는 사실일 뿐,
 * 그 구간이 완전해졌는지는 말하지 않는다. 읽기 경로가 저장분을 신뢰하는 조건이 완전성이다.
 */
public record AdminRefreshResponse(
        @Schema(description = "실제로 비운 캐시 이름") List<String> evictedCaches,
        int totalUpserted,
        boolean hasFailure,
        @Schema(description = "구멍을 다시 받아 채운 날 수") int backfilledDays,
        @Schema(description = "고시가 없다고 확정한 날 수 — 공휴일이 여기로 들어간다")
        int confirmedAbsentDays,
        @Schema(description = "갱신 구간이 모든 쌍에서 완전해졌는가") boolean complete,
        Instant refreshedAt,
        long elapsedMs,
        List<PairResult> pairs,
        @Schema(description = "갱신 구간의 쌍별 커버리지") List<AdminFxRateCoverageResponse.PairCoverage> coverage) {

    public static AdminRefreshResponse from(FxRateRefreshService.RefreshReport report) {
        return new AdminRefreshResponse(
                report.evictedCaches(),
                report.totalUpserted(),
                report.hasFailure(),
                report.backfilledDays(),
                report.confirmedAbsentDays(),
                report.complete(),
                report.refreshedAt(),
                report.elapsedMs(),
                report.pairs().stream().map(PairResult::from).toList(),
                AdminFxRateCoverageResponse.from(report.coverage()).pairs());
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
