package com.divurve.domain.holding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.divurve.common.exception.InvalidRequestException;
import com.divurve.domain.holding.entity.PurchaseFxRate;
import com.divurve.domain.port.FxRateHistoryProvider;
import com.divurve.domain.port.FxRateHistoryProvider.HistoryRateSnapshot;
import com.divurve.engine.weight.QuoteUnitNormalizer;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * {@link PurchaseFxRateResolver} — 자동조회 성공/실패/폴백/KRW/JPY 정규화 케이스 커버리지 (FR-ON-04).
 *
 * <p>이슈 #98: 자동조회가 <b>매입일</b> 종가를 쓰는지 검증한다. 예전에는 {@code fetchLatest} 를 불러
 * 조회 시점의 최신 종가가 박혔고, 그건 아래 {@code 매입일_당일_종가를_쓴다} 가 잡는다.
 */
@ExtendWith(MockitoExtension.class)
class PurchaseFxRateResolverTest {

    @Mock
    private FxRateHistoryProvider historyProvider;

    private PurchaseFxRateResolver resolver() {
        return new PurchaseFxRateResolver(historyProvider, new QuoteUnitNormalizer());
    }

    private final LocalDate purchasedAt = LocalDate.of(2025, 3, 10);

    @Test
    void 매입일_없으면_컨텍스트를_만들지_않는다() {
        assertThat(resolver().resolve("USD", null, null)).isNull();
        verify(historyProvider, never()).fetchHistorical(anyString(), any(), anyInt());
    }

    @Test
    void KRW_는_환율_조회를_건너뛴다() {
        assertThat(resolver().resolve("KRW", purchasedAt, new BigDecimal("1"))).isNull();
        verify(historyProvider, never()).fetchHistorical(anyString(), any(), anyInt());
    }

    @Test
    void 폴백_값이_있으면_수동_출처로_기록한다() {
        PurchaseFxRate rate = resolver().resolve("USD", purchasedAt, new BigDecimal("1345.5"));

        assertThat(rate.rateKrw()).isEqualByComparingTo("1345.5");
        assertThat(rate.source()).isEqualTo(PurchaseFxRateResolver.SOURCE_MANUAL);
        assertThat(rate.asOf()).isEqualTo(purchasedAt);
        verify(historyProvider, never()).fetchHistorical(anyString(), any(), anyInt());
    }

    @Test
    void 매입일_당일_종가를_쓴다() {
        when(historyProvider.fetchHistorical(anyString(), any(), anyInt()))
                .thenReturn(List.of(
                        new HistoryRateSnapshot(purchasedAt.minusDays(1), 1348.10),
                        new HistoryRateSnapshot(purchasedAt, 1350.42)));

        PurchaseFxRate rate = resolver().resolve("USD", purchasedAt, null);

        assertThat(rate.rateKrw()).isEqualByComparingTo("1350.42");
        assertThat(rate.source()).isEqualTo(PurchaseFxRateResolver.SOURCE_ECOS);
        assertThat(rate.asOf()).isEqualTo(purchasedAt);
    }

    @Test
    void 매입일을_조회_끝날짜로_넘긴다() {
        when(historyProvider.fetchHistorical(anyString(), any(), anyInt()))
                .thenReturn(List.of(new HistoryRateSnapshot(purchasedAt, 1350.42)));

        resolver().resolve("USD", purchasedAt, null);

        ArgumentCaptor<LocalDate> endDate = ArgumentCaptor.forClass(LocalDate.class);
        verify(historyProvider).fetchHistorical(
                org.mockito.ArgumentMatchers.eq("USD_KRW"),
                endDate.capture(),
                org.mockito.ArgumentMatchers.eq(PurchaseFxRateResolver.LOOKBACK_CALENDAR_DAYS));
        assertThat(endDate.getValue()).isEqualTo(purchasedAt);
    }

    @Test
    void 휴장일_매입은_직전_영업일_종가를_쓴다() {
        LocalDate lastBusinessDay = purchasedAt.minusDays(2);
        when(historyProvider.fetchHistorical(anyString(), any(), anyInt()))
                .thenReturn(List.of(
                        new HistoryRateSnapshot(purchasedAt.minusDays(3), 1340.00),
                        new HistoryRateSnapshot(lastBusinessDay, 1345.60)));

        PurchaseFxRate rate = resolver().resolve("USD", purchasedAt, null);

        assertThat(rate.rateKrw()).isEqualByComparingTo("1345.6");
        // 없는 날을 보간해 만들지 않는다 (FR-CM-10) — 실제 고시된 관측의 날짜를 그대로 싣는다.
        assertThat(rate.asOf()).isEqualTo(lastBusinessDay);
    }

    @Test
    void 매입일_이후_관측은_고르지_않는다() {
        when(historyProvider.fetchHistorical(anyString(), any(), anyInt()))
                .thenReturn(List.of(
                        new HistoryRateSnapshot(purchasedAt.minusDays(1), 1348.10),
                        new HistoryRateSnapshot(purchasedAt.plusDays(1), 1399.90)));

        PurchaseFxRate rate = resolver().resolve("USD", purchasedAt, null);

        assertThat(rate.rateKrw()).isEqualByComparingTo("1348.1");
        assertThat(rate.asOf()).isEqualTo(purchasedAt.minusDays(1));
    }

    @Test
    void 응답_순서가_뒤집혀도_가장_최근_관측을_고른다() {
        when(historyProvider.fetchHistorical(anyString(), any(), anyInt()))
                .thenReturn(List.of(
                        new HistoryRateSnapshot(purchasedAt, 1350.42),
                        new HistoryRateSnapshot(purchasedAt.minusDays(1), 1348.10)));

        PurchaseFxRate rate = resolver().resolve("USD", purchasedAt, null);

        assertThat(rate.asOf()).isEqualTo(purchasedAt);
        assertThat(rate.rateKrw()).isEqualByComparingTo("1350.42");
    }

    @Test
    void JPY_는_원_100엔_응답을_원_1엔으로_정규화한다() {
        when(historyProvider.fetchHistorical(anyString(), any(), anyInt()))
                .thenReturn(List.of(new HistoryRateSnapshot(purchasedAt, 930.5)));

        PurchaseFxRate rate = resolver().resolve("JPY", purchasedAt, null);

        assertThat(rate.rateKrw()).isEqualByComparingTo("9.305");
    }

    @Test
    void 자동조회_실패시_폴백_없으면_400_에러코드로_표면화한다() {
        when(historyProvider.fetchHistorical(anyString(), any(), anyInt()))
                .thenThrow(new IllegalStateException("boom"));

        assertThatThrownBy(() -> resolver().resolve("USD", purchasedAt, null))
                .isInstanceOfSatisfying(InvalidRequestException.class, ex -> {
                    assertThat(ex.getCode()).isEqualTo("VALIDATION_FAILED");
                    assertThat(ex.getField()).isEqualTo(PurchaseFxRateResolver.FIELD_PURCHASE_FX_RATE_KRW);
                    // 외부 원문("boom")은 응답 메시지에 싣지 않는다.
                    assertThat(ex.getMessage()).doesNotContain("boom");
                });
    }

    @Test
    void 조회_구간에_관측이_없으면_수동입력을_요구한다() {
        when(historyProvider.fetchHistorical(anyString(), any(), anyInt())).thenReturn(List.of());

        assertThatThrownBy(() -> resolver().resolve("USD", purchasedAt, null))
                .isInstanceOfSatisfying(InvalidRequestException.class, ex ->
                        assertThat(ex.getField())
                                .isEqualTo(PurchaseFxRateResolver.FIELD_PURCHASE_FX_RATE_KRW));
    }

    @Test
    void 어댑터가_null_을_돌려줘도_수동입력을_요구한다() {
        when(historyProvider.fetchHistorical(anyString(), any(), anyInt())).thenReturn(null);

        assertThatThrownBy(() -> resolver().resolve("USD", purchasedAt, null))
                .isInstanceOf(InvalidRequestException.class);
    }

    @Test
    void 값이_비어있는_관측은_건너뛴다() {
        when(historyProvider.fetchHistorical(anyString(), any(), anyInt()))
                .thenReturn(java.util.Arrays.asList(
                        new HistoryRateSnapshot(purchasedAt, null),
                        new HistoryRateSnapshot(null, 1399.90),
                        new HistoryRateSnapshot(purchasedAt.minusDays(1), 1348.10),
                        null));

        PurchaseFxRate rate = resolver().resolve("USD", purchasedAt, null);

        assertThat(rate.asOf()).isEqualTo(purchasedAt.minusDays(1));
        assertThat(rate.rateKrw()).isEqualByComparingTo("1348.1");
    }
}
