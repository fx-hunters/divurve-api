package com.divurve.domain.macro;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.divurve.common.exception.InvalidRequestException;
import com.divurve.domain.port.ExternalDataCache;
import com.divurve.domain.port.MacroIndicatorProvider;
import com.divurve.domain.port.MacroSnapshot;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

/**
 * {@link MacroRefreshService} — FRED 연동 점검.
 *
 * <p>이 서비스가 {@code FredMacroProvider} 의 첫 프로덕션 호출처다. 저장하지 않고 값만 돌려준다.
 */
@DisplayName("MacroRefreshService")
class MacroRefreshServiceTest {

    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-09-07T00:30:00Z"), ZoneOffset.UTC);

    private ExternalDataCache externalDataCache;
    private MacroIndicatorProvider macroIndicatorProvider;
    private MacroRefreshService service;

    @BeforeEach
    void setUp() {
        externalDataCache = mock(ExternalDataCache.class);
        macroIndicatorProvider = mock(MacroIndicatorProvider.class);
        service = new MacroRefreshService(externalDataCache, macroIndicatorProvider, CLOCK);
    }

    private static MacroSnapshot snapshot(String seriesId) {
        return new MacroSnapshot(
                seriesId, new BigDecimal("4.21"), LocalDate.of(2026, 9, 5), "FRED",
                Instant.parse("2026-09-07T00:30:00Z"));
    }

    @Test
    @DisplayName("캐시를 먼저 비우고 그다음 조회한다")
    void refresh_EvictsBeforeFetch() {
        when(externalDataCache.evict(MacroRefreshService.MACRO_CACHE_NAMES))
                .thenReturn(List.of("macro-latest"));
        when(macroIndicatorProvider.fetchLatest("DGS10")).thenReturn(snapshot("DGS10"));

        MacroRefreshService.MacroRefreshReport report = service.refresh(List.of("DGS10"));

        InOrder order = inOrder(externalDataCache, macroIndicatorProvider);
        order.verify(externalDataCache).evict(MacroRefreshService.MACRO_CACHE_NAMES);
        order.verify(macroIndicatorProvider).fetchLatest("DGS10");

        assertThat(report.evictedCaches()).containsExactly("macro-latest");
        assertThat(report.refreshedAt()).isEqualTo(Instant.now(CLOCK));
        assertThat(report.elapsedMs()).isZero();
        assertThat(report.series()).singleElement().satisfies(result -> {
            assertThat(result.seriesId()).isEqualTo("DGS10");
            assertThat(result.snapshot().value()).isEqualByComparingTo("4.21");
            assertThat(result.failureReason()).isNull();
        });
    }

    @Test
    @DisplayName("한 시리즈가 실패해도 나머지는 조회한다 — 사유는 값으로 남는다")
    void refresh_PartialFailure_ContinuesOthers() {
        when(externalDataCache.evict(any())).thenReturn(List.of("macro-latest"));
        when(macroIndicatorProvider.fetchLatest("BAD"))
                .thenThrow(new IllegalStateException("FRED 응답 없음"));
        when(macroIndicatorProvider.fetchLatest("DGS10")).thenReturn(snapshot("DGS10"));

        MacroRefreshService.MacroRefreshReport report = service.refresh(List.of("BAD", "DGS10"));

        assertThat(report.series())
                .extracting(MacroRefreshService.SeriesResult::failureReason)
                .containsExactly("FRED 응답 없음", null);
        assertThat(report.series().get(0).snapshot()).isNull();
    }

    @Test
    @DisplayName("시리즈가 비었거나 상한을 넘으면 400 이다")
    void refresh_InvalidSeries_Throws() {
        assertThatThrownBy(() -> service.refresh(List.of()))
                .isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> service.refresh(
                IntStream.rangeClosed(0, MacroRefreshService.MAX_SERIES)
                        .mapToObj(i -> "S" + i).toList()))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("한 번에");
    }

    @Test
    @DisplayName("null 인자와 의존은 거부한다")
    void nullArguments_Throw() {
        assertThatThrownBy(() -> new MacroRefreshService(null, macroIndicatorProvider, CLOCK))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new MacroRefreshService(externalDataCache, null, CLOCK))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new MacroRefreshService(
                externalDataCache, macroIndicatorProvider, null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> service.refresh(null))
                .isInstanceOf(NullPointerException.class);
    }
}
