package com.divurve.domain.fx;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.divurve.domain.port.ExternalDataCache;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

/**
 * {@link FxRateRefreshService} — 관리자 "갱신" 버튼이 부르는 경로.
 *
 * <p>가장 중요한 것은 <b>순서</b>다. 캐시를 비우기 전에 조회하면 6시간 TTL 안의 옛 값을 다시 읽어
 * 저장하고는 "갱신했다" 고 보고하게 된다 — 조용히 아무 일도 하지 않는 버튼이 된다.
 */
@DisplayName("FxRateRefreshService")
class FxRateRefreshServiceTest {

    private static final LocalDate END_DATE = LocalDate.of(2026, 9, 7);
    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-09-07T00:30:00Z"), ZoneOffset.UTC);

    private ExternalDataCache externalDataCache;
    private FxRateIngestionService ingestionService;
    private FxRateGapService gapService;
    private FxRateRefreshService service;

    @BeforeEach
    void setUp() {
        externalDataCache = mock(ExternalDataCache.class);
        ingestionService = mock(FxRateIngestionService.class);
        gapService = mock(FxRateGapService.class);
        // 구멍 탐지 자체는 FxRateGapServiceTest 가 고정한다. 여기서 고정하는 것은 갱신의 순서와
        // 리포트 조립이므로, 백필·커버리지는 "아무 일도 없었다" 로 둔다 (이슈 #116).
        when(gapService.backfillStoredPairs(any(), any())).thenReturn(
                new FxRateGapService.BackfillReport(List.of(), Instant.now(CLOCK)));
        when(gapService.coverageOfStoredPairs(any(), any())).thenReturn(List.of());
        service = new FxRateRefreshService(externalDataCache, ingestionService, gapService, CLOCK);
    }

    private static FxRateIngestionService.IngestionReport report(
            FxRateIngestionService.PairResult... pairs) {
        return new FxRateIngestionService.IngestionReport(
                List.of(pairs), Instant.parse("2026-09-07T00:30:00Z"));
    }

    @Test
    @DisplayName("캐시를 먼저 비우고 그다음 조회한다")
    void refresh_EvictsBeforeIngest() {
        when(externalDataCache.evict(FxRateRefreshService.FX_CACHE_NAMES))
                .thenReturn(List.of("fx-latest", "fx-history"));
        when(ingestionService.ingest(any(), anyInt())).thenReturn(report(
                new FxRateIngestionService.PairResult("USDKRW", 2, END_DATE, END_DATE, null)));

        FxRateRefreshService.RefreshReport result = service.refresh(END_DATE, 14);

        InOrder order = inOrder(externalDataCache, ingestionService);
        order.verify(externalDataCache).evict(FxRateRefreshService.FX_CACHE_NAMES);
        order.verify(ingestionService).ingest(END_DATE, 14);

        assertThat(result.evictedCaches()).containsExactly("fx-latest", "fx-history");
        assertThat(result.totalUpserted()).isEqualTo(2);
        assertThat(result.hasFailure()).isFalse();
        assertThat(result.refreshedAt()).isEqualTo(Instant.now(CLOCK));
        assertThat(result.elapsedMs()).isZero();
        assertThat(result.pairs()).hasSize(1);
    }

    @Test
    @DisplayName("실패한 쌍이 있으면 그 사실이 응답에 드러난다 — 조용히 0건이 되지 않는다")
    void refresh_ReportsFailure() {
        when(externalDataCache.evict(any())).thenReturn(List.of("fx-latest"));
        when(ingestionService.ingest(any(), anyInt())).thenReturn(report(
                new FxRateIngestionService.PairResult("EURKRW", 0, null, null, "ECOS 응답 없음")));

        FxRateRefreshService.RefreshReport result = service.refresh(END_DATE, 14);

        assertThat(result.hasFailure()).isTrue();
        assertThat(result.totalUpserted()).isZero();
        assertThat(result.pairs().get(0).failureReason()).isEqualTo("ECOS 응답 없음");
    }

    @Test
    @DisplayName("비운 캐시 이름은 어댑터가 실제로 비운 것만 담는다")
    void refresh_ReportsOnlyActuallyEvicted() {
        when(externalDataCache.evict(any())).thenReturn(List.of("fx-latest"));
        when(ingestionService.ingest(any(), anyInt())).thenReturn(report());

        assertThat(service.refresh(END_DATE, 14).evictedCaches()).containsExactly("fx-latest");
    }

    // ── 백필·커버리지 (이슈 #116) ────────────────────────────────────

    @Test
    @DisplayName("적재 다음에 백필을 돌리고 갱신 구간의 커버리지를 함께 싣는다")
    void refresh_BackfillsAndReportsCoverage() {
        when(externalDataCache.evict(any())).thenReturn(List.of());
        when(ingestionService.ingest(any(), anyInt())).thenReturn(report());
        when(gapService.backfillStoredPairs(END_DATE.minusDays(14), END_DATE)).thenReturn(
                new FxRateGapService.BackfillReport(
                        List.of(new FxRateGapService.PairBackfill(
                                "USDKRW", 2, 1, 3, 0, true, List.of(), null)),
                        Instant.now(CLOCK)));
        when(gapService.coverageOfStoredPairs(END_DATE.minusDays(14), END_DATE)).thenReturn(
                List.of(new FxRateGapService.PairCoverage(
                        "USDKRW", "mid", END_DATE.minusDays(14), END_DATE, 10, 10, 0, 1.0,
                        true, List.of())));

        FxRateRefreshService.RefreshReport result = service.refresh(END_DATE, 14);

        InOrder order = inOrder(ingestionService, gapService);
        order.verify(ingestionService).ingest(END_DATE, 14);
        order.verify(gapService).backfillStoredPairs(END_DATE.minusDays(14), END_DATE);

        assertThat(result.backfilledDays()).isEqualTo(2);
        assertThat(result.confirmedAbsentDays()).isEqualTo(1);
        assertThat(result.complete()).isTrue();
        assertThat(result.coverage()).singleElement()
                .extracting(FxRateGapService.PairCoverage::pairCode).isEqualTo("USDKRW");
    }

    @Test
    @DisplayName("백필이 실패하면 적재가 성공해도 실패로 알린다 — 구멍이 남은 것을 숨기지 않는다")
    void refresh_BackfillFailureSurfaces() {
        when(externalDataCache.evict(any())).thenReturn(List.of());
        when(ingestionService.ingest(any(), anyInt())).thenReturn(report());
        when(gapService.backfillStoredPairs(any(), any())).thenReturn(
                new FxRateGapService.BackfillReport(
                        List.of(new FxRateGapService.PairBackfill(
                                "USDKRW", 0, 0, 3, 3, false, List.of(), "ECOS 응답 없음")),
                        Instant.now(CLOCK)));

        FxRateRefreshService.RefreshReport result = service.refresh(END_DATE, 14);

        assertThat(result.hasFailure()).isTrue();
    }

    @Test
    @DisplayName("null 의존은 거부한다")
    void nullDependencies_Throw() {
        assertThatThrownBy(() -> new FxRateRefreshService(null, ingestionService, gapService, CLOCK))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new FxRateRefreshService(externalDataCache, null, gapService, CLOCK))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new FxRateRefreshService(externalDataCache, ingestionService, gapService, null))
                .isInstanceOf(NullPointerException.class);
    }
}
