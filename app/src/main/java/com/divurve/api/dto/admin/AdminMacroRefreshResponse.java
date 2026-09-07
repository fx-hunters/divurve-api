package com.divurve.api.dto.admin;

import com.divurve.domain.macro.MacroRefreshService;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * FRED 거시지표 수동 갱신 결과 (이슈 #111).
 *
 * <p>저장하지 않는다 — 거시지표를 담을 표가 아직 없고, 이 호출의 목적은 적재가 아니라 연동 점검이다.
 */
public record AdminMacroRefreshResponse(
        List<String> evictedCaches,
        Instant refreshedAt,
        long elapsedMs,
        List<SeriesResult> series) {

    public static AdminMacroRefreshResponse from(MacroRefreshService.MacroRefreshReport report) {
        return new AdminMacroRefreshResponse(
                report.evictedCaches(),
                report.refreshedAt(),
                report.elapsedMs(),
                report.series().stream().map(SeriesResult::from).toList());
    }

    /**
     * 시리즈 하나의 조회 결과.
     *
     * @param seriesId      FRED 시리즈 id
     * @param value         관측값. 실패면 null
     * @param asOf          관측 기준일. 실패면 null
     * @param source        출처. 실패면 null
     * @param fetchedAt     조회 시각. 실패면 null
     * @param failureReason 실패 사유. 성공이면 null
     */
    public record SeriesResult(
            @Schema(example = "DGS10") String seriesId,
            BigDecimal value, LocalDate asOf, String source, Instant fetchedAt,
            String failureReason) {

        static SeriesResult from(MacroRefreshService.SeriesResult r) {
            var snapshot = r.snapshot();
            return new SeriesResult(
                    r.seriesId(),
                    snapshot == null ? null : snapshot.value(),
                    snapshot == null ? null : snapshot.asOf(),
                    snapshot == null ? null : snapshot.source(),
                    snapshot == null ? null : snapshot.fetchedAt(),
                    r.failureReason());
        }
    }
}
