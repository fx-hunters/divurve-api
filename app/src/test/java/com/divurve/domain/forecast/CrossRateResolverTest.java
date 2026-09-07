package com.divurve.domain.forecast;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.divurve.common.exception.InvalidRequestException;
import com.divurve.domain.fx.PerUnitFxRates;
import com.divurve.domain.fx.StoredFxRates;
import com.divurve.domain.port.FxRateHistoryProvider;
import com.divurve.domain.port.FxRateHistoryProvider.HistoryRateSnapshot;
import com.divurve.domain.port.FxRateProvider;
import com.divurve.domain.port.RateSnapshot;
import com.divurve.engine.fx.CrossRateDeriver;
import com.divurve.engine.weight.QuoteUnitNormalizer;
import java.time.LocalDate;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * {@link CrossRateResolver} — 고시가 없는 통화쌍의 유도 (이슈 #57).
 *
 * <p>가장 중요한 회귀: ECOS 의 <b>원/100엔</b> 고시를 접지 않고 나누면 {@code USDJPY} 가 100배가 된다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CrossRateResolver")
class CrossRateResolverTest {

    private static final LocalDate END = LocalDate.of(2026, 9, 1);
    private static final int LOOKBACK = 2056;

    @Mock
    private FxRateHistoryProvider historyProvider;

    @Mock
    private FxRateProvider fxRateProvider;

    private CrossRateResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new CrossRateResolver(
                StoredFxRates.NONE,
                historyProvider,
                new PerUnitFxRates(StoredFxRates.NONE, fxRateProvider, new QuoteUnitNormalizer()),
                new CrossRateDeriver(),
                new QuoteUnitNormalizer());
    }

    @Test
    @DisplayName("표시통화가 원화면 유도 없이 어댑터에 그대로 묻는다")
    void 원화_크로스는_그대로_조회한다() {
        List<HistoryRateSnapshot> expected = List.of(new HistoryRateSnapshot(END, 1382.40));
        when(historyProvider.fetchHistorical(eq("USD_KRW"), any(LocalDate.class), anyInt()))
                .thenReturn(expected);

        assertThat(resolver.fetch(PairCode.parse("USDKRW"), END, LOOKBACK)).isEqualTo(expected);
        verify(historyProvider, never()).fetchHistorical(eq("KRW_KRW"), any(), anyInt());
    }

    @Test
    @DisplayName("USDJPY 는 USD/KRW ÷ JPY/KRW 로 유도하고, JPY 는 100엔 고시를 접는다")
    void JPY는_100엔_고시를_접고_유도한다() {
        when(historyProvider.fetchHistorical(eq("USD_KRW"), any(LocalDate.class), anyInt()))
                .thenReturn(List.of(new HistoryRateSnapshot(END, 1382.40)));
        // ECOS 는 원/100엔으로 고시한다 — 접지 않으면 결과가 100배로 나온다.
        when(historyProvider.fetchHistorical(eq("JPY_KRW"), any(LocalDate.class), anyInt()))
                .thenReturn(List.of(new HistoryRateSnapshot(END, 921.60)));

        List<HistoryRateSnapshot> usdJpy = resolver.fetch(PairCode.parse("USDJPY"), END, LOOKBACK);

        assertThat(usdJpy).hasSize(1);
        // 1382.40 ÷ 9.216 = 150.0 — 접지 않았다면 1.5 가 나왔을 것이다.
        assertThat(usdJpy.get(0).rate()).isCloseTo(150.0, within(1e-9));
        assertThat(usdJpy.get(0).date()).isEqualTo(END);
    }

    @Test
    @DisplayName("EURUSD 는 EUR/KRW ÷ USD/KRW 로 유도한다 (100엔 접기 없음)")
    void EURUSD를_유도한다() {
        when(historyProvider.fetchHistorical(eq("EUR_KRW"), any(LocalDate.class), anyInt()))
                .thenReturn(List.of(new HistoryRateSnapshot(END, 1520.64)));
        when(historyProvider.fetchHistorical(eq("USD_KRW"), any(LocalDate.class), anyInt()))
                .thenReturn(List.of(new HistoryRateSnapshot(END, 1382.40)));

        List<HistoryRateSnapshot> eurUsd = resolver.fetch(PairCode.parse("EURUSD"), END, LOOKBACK);

        assertThat(eurUsd).hasSize(1);
        assertThat(eurUsd.get(0).rate()).isCloseTo(1520.64 / 1382.40, within(1e-12));
    }

    @Test
    @DisplayName("한쪽 계열이 비면 유도 결과도 빈다 — 없는 값을 만들지 않는다")
    void 한쪽이_비면_빈_결과다() {
        when(historyProvider.fetchHistorical(eq("USD_KRW"), any(LocalDate.class), anyInt()))
                .thenReturn(List.of(new HistoryRateSnapshot(END, 1382.40)));
        when(historyProvider.fetchHistorical(eq("JPY_KRW"), any(LocalDate.class), anyInt()))
                .thenReturn(List.of());

        assertThat(resolver.fetch(PairCode.parse("USDJPY"), END, LOOKBACK)).isEmpty();
    }

    @Test
    @DisplayName("어댑터가 지원하지 않는 통화는 예외가 그대로 올라간다 — 호출자가 통화쌍 단위로 처리한다")
    void 지원하지_않는_통화는_예외를_전파한다() {
        when(historyProvider.fetchHistorical(eq("GBP_KRW"), any(LocalDate.class), anyInt()))
                .thenThrow(new IllegalArgumentException("Unsupported pairCode for ECOS: GBP_KRW"));

        assertThatThrownBy(() -> resolver.fetch(PairCode.parse("GBPJPY"), END, LOOKBACK))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ── latestRate — /forecast 의 기준선 (이슈 #57) ───────────────────────────

    private void givenLatest(String pairCode, String rate) {
        when(fxRateProvider.fetchLatest(pairCode)).thenReturn(new RateSnapshot(
                pairCode, new java.math.BigDecimal(rate), END, "ECOS",
                java.time.Instant.parse("2026-09-01T15:30:00Z")));
    }

    @Test
    @DisplayName("표시통화가 원화면 고시 환율을 1단위 기준으로 그대로 쓴다")
    void 원화_크로스의_최근_환율() {
        givenLatest("USD_KRW", "1382.40");

        assertThat(resolver.latestRate(PairCode.parse("USDKRW"))).isCloseTo(1382.40, within(1e-9));
    }

    @Test
    @DisplayName("USDJPY 최근 환율은 두 원화 크로스의 비다 — 100엔 고시를 접고 나눈다")
    void 유도_쌍의_최근_환율() {
        givenLatest("USD_KRW", "1382.40");
        givenLatest("JPY_KRW", "921.60");

        // 1382.40 ÷ 9.216 = 150.0 — 접지 않았다면 1.5 였을 것이다.
        assertThat(resolver.latestRate(PairCode.parse("USDJPY"))).isCloseTo(150.0, within(1e-9));
    }

    @Test
    @DisplayName("분모 통화 환율이 0 이면 유도할 수 없으므로 400 을 낸다 — 0 으로 나누지 않는다")
    void 분모가_0이면_400이다() {
        givenLatest("USD_KRW", "1382.40");
        givenLatest("JPY_KRW", "0");

        assertThatThrownBy(() -> resolver.latestRate(PairCode.parse("USDJPY")))
                .isInstanceOf(InvalidRequestException.class);
    }

    @Test
    @DisplayName("최근 환율 조회에도 통화쌍은 필수다")
    void 최근_환율에도_통화쌍은_필수다() {
        assertThatThrownBy(() -> resolver.latestRate(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("pair");
    }

    @Test
    @DisplayName("어댑터가 null 을 주면 관측 없음으로 다룬다 — 여기서 NPE 가 나면 400 이 500 이 된다")
    void null_시계열은_빈_결과다() {
        when(historyProvider.fetchHistorical(eq("USD_KRW"), any(LocalDate.class), anyInt()))
                .thenReturn(null);

        assertThat(resolver.fetch(PairCode.parse("USDKRW"), END, LOOKBACK)).isEmpty();
    }

    // ── 저장분 우선 (이슈 #116) ────────────────────────────────────

    private CrossRateResolver dbFirst(StoredFxRates stored) {
        return new CrossRateResolver(
                stored,
                historyProvider,
                new PerUnitFxRates(StoredFxRates.NONE, fxRateProvider, new QuoteUnitNormalizer()),
                new CrossRateDeriver(),
                new QuoteUnitNormalizer());
    }

    @Test
    @DisplayName("저장분이 완전하면 그것을 쓰고 ECOS 를 부르지 않는다")
    void 저장분이_완전하면_그것을_쓴다() {
        StoredFxRates stored = mock(StoredFxRates.class);
        when(stored.perUnitSeries("USD", END, LOOKBACK)).thenReturn(Optional.of(List.of(
                new StoredFxRates.Point(END, new BigDecimal("1382.400000")))));

        List<HistoryRateSnapshot> series =
                dbFirst(stored).fetch(PairCode.parse("USDKRW"), END, LOOKBACK);

        assertThat(series).containsExactly(new HistoryRateSnapshot(END, 1382.40));
        verify(historyProvider, never()).fetchHistorical(any(), any(), anyInt());
    }

    @Test
    @DisplayName("저장분은 이미 1단위라 다시 접지 않는다 — 접으면 USDJPY 가 100배가 된다")
    void 저장분은_다시_접지_않는다() {
        StoredFxRates stored = mock(StoredFxRates.class);
        when(stored.perUnitSeries("USD", END, LOOKBACK)).thenReturn(Optional.of(List.of(
                new StoredFxRates.Point(END, new BigDecimal("1382.400000")))));
        // fx_rates 는 1엔 기준으로 적재된다 (921.60 / 100).
        when(stored.perUnitSeries("JPY", END, LOOKBACK)).thenReturn(Optional.of(List.of(
                new StoredFxRates.Point(END, new BigDecimal("9.216000")))));

        List<HistoryRateSnapshot> usdJpy =
                dbFirst(stored).fetch(PairCode.parse("USDJPY"), END, LOOKBACK);

        assertThat(usdJpy).hasSize(1);
        assertThat(usdJpy.get(0).rate()).isCloseTo(150.0, within(1e-9));
    }

    @Test
    @DisplayName("한 통화만 구멍이면 그 통화만 실시간으로 간다 — 조달 단위는 통화다")
    void 통화별로_조달_경로가_갈린다() {
        StoredFxRates stored = mock(StoredFxRates.class);
        when(stored.perUnitSeries("USD", END, LOOKBACK)).thenReturn(Optional.of(List.of(
                new StoredFxRates.Point(END, new BigDecimal("1382.400000")))));
        when(stored.perUnitSeries("JPY", END, LOOKBACK)).thenReturn(Optional.empty());
        when(historyProvider.fetchHistorical(eq("JPY_KRW"), any(LocalDate.class), anyInt()))
                .thenReturn(List.of(new HistoryRateSnapshot(END, 921.60)));

        List<HistoryRateSnapshot> usdJpy =
                dbFirst(stored).fetch(PairCode.parse("USDJPY"), END, LOOKBACK);

        verify(historyProvider, never()).fetchHistorical(eq("USD_KRW"), any(), anyInt());
        assertThat(usdJpy).hasSize(1);
        assertThat(usdJpy.get(0).rate()).isCloseTo(150.0, within(1e-9));
    }

    @Test
    @DisplayName("생성자는 null 협력자를 거부한다")
    void null_협력자를_거부한다() {
        CrossRateDeriver deriver = new CrossRateDeriver();
        QuoteUnitNormalizer normalizer = new QuoteUnitNormalizer();
        PerUnitFxRates rates = new PerUnitFxRates(StoredFxRates.NONE, fxRateProvider, normalizer);

        assertThatThrownBy(() ->
                new CrossRateResolver(null, historyProvider, rates, deriver, normalizer))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() ->
                new CrossRateResolver(StoredFxRates.NONE, null, rates, deriver, normalizer))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() ->
                new CrossRateResolver(StoredFxRates.NONE, historyProvider, null, deriver, normalizer))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() ->
                new CrossRateResolver(StoredFxRates.NONE, historyProvider, rates, null, normalizer))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() ->
                new CrossRateResolver(StoredFxRates.NONE, historyProvider, rates, deriver, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("통화쌍은 필수다")
    void 통화쌍은_필수다() {
        assertThatThrownBy(() -> resolver.fetch(null, END, LOOKBACK))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("pair");
    }
}
