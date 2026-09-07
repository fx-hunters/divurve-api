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
    private FxRateRefreshService service;

    @BeforeEach
    void setUp() {
        externalDataCache = mock(ExternalDataCache.class);
        ingestionService = mock(FxRateIngestionService.class);
        service = new FxRateRefreshService(externalDataCache, ingestionService, CLOCK);
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

    @Test
    @DisplayName("null 의존은 거부한다")
    void nullDependencies_Throw() {
        assertThatThrownBy(() -> new FxRateRefreshService(null, ingestionService, CLOCK))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new FxRateRefreshService(externalDataCache, null, CLOCK))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new FxRateRefreshService(externalDataCache, ingestionService, null))
                .isInstanceOf(NullPointerException.class);
    }
}
