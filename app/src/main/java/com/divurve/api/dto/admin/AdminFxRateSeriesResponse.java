package com.divurve.api.dto.admin;

import com.divurve.domain.fx.FxRateQueryService;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * 관리자 환율 차트 응답 (이슈 #111).
 *
 * <p>관측은 <b>영업일에만</b> 존재하므로 요청 구간보다 개수가 적다. 빠진 날짜를 채우지 않는다 —
 * 없는 관측을 만들면 그것이 곧 지어낸 수치다(NFR-DT-01).
 */
public record AdminFxRateSeriesResponse(
        @Schema(example = "USDKRW") String pairCode,
        @Schema(example = "mid") String rateType,
        LocalDate from,
        LocalDate to,
        int count,
        List<Point> points) {

    public static AdminFxRateSeriesResponse from(FxRateQueryService.RateSeries series) {
        List<Point> points = series.points().stream().map(Point::from).toList();
        return new AdminFxRateSeriesResponse(
                series.pairCode(), series.rateType(), series.from(), series.to(),
                points.size(), points);
    }

    /**
     * 관측 하나.
     *
     * @param quoteDate  고시일
     * @param rate       1 외화당 원화
     * @param dataSource 출처
     * @param fetchedAt  우리가 가져온 시각 (고시일과 다르다)
     */
    public record Point(
            LocalDate quoteDate, BigDecimal rate, String dataSource, Instant fetchedAt) {

        static Point from(FxRateQueryService.Point p) {
            return new Point(p.quoteDate(), p.rate(), p.dataSource(), p.fetchedAt());
        }
    }
}
