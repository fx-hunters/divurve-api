package com.divurve.domain.fx;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.divurve.common.exception.InvalidRequestException;
import com.divurve.domain.master.CurrencyPairRepository;
import com.divurve.domain.master.entity.CurrencyPair;
import com.divurve.domain.port.FxRateHistoryProvider;
import com.divurve.support.MasterFixtures;
import com.divurve.engine.weight.QuoteUnitNormalizer;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link FxRateIngestionService} — ECOS 종가를 {@code fx_rates} 로 옮기는 경로.
 *
 * <p>고정하는 것은 셋이다: 저장 쌍만 순회하는가, JPY 100엔 고시를 1단위로 접는가,
 * 한 쌍의 실패가 나머지를 막지 않는가.
 */
@DisplayName("FxRateIngestionService")
class FxRateIngestionServiceTest {

    private static final LocalDate END_DATE = LocalDate.of(2026, 9, 7);
    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-09-07T00:30:00Z"), ZoneOffset.UTC);

    private CurrencyPairRepository currencyPairRepository;
    private FxRateHistoryProvider historyProvider;
    private FxRateRepository fxRateRepository;
    private FxRateIngestionService service;

    @BeforeEach
    void setUp() {
        currencyPairRepository = mock(CurrencyPairRepository.class);
        historyProvider = mock(FxRateHistoryProvider.class);
        fxRateRepository = mock(FxRateRepository.class);
        service = new FxRateIngestionService(
                currencyPairRepository, historyProvider, fxRateRepository,
                new QuoteUnitNormalizer(), CLOCK);
    }

    private static CurrencyPair storedPair(String pairCode, String base) {
        return MasterFixtures.pair(pairCode, base, "KRW", true, null);
    }

    private static FxRateHistoryProvider.HistoryRateSnapshot snapshot(String date, double rate) {
        return new FxRateHistoryProvider.HistoryRateSnapshot(LocalDate.parse(date), rate);
    }

    @Test
    @DisplayName("저장 쌍의 관측을 전부 upsert 하고 구간을 보고한다")
    void ingest_UpsertsStoredPairs() {
        when(currencyPairRepository.findByStoredTrueOrderByPairCodeAsc())
                .thenReturn(List.of(storedPair("USDKRW", "USD")));
        when(historyProvider.fetchHistorical(eq("USD_KRW"), eq(END_DATE), anyInt()))
                .thenReturn(List.of(snapshot("2026-09-04", 1380.5), snapshot("2026-09-05", 1382.0)));

        FxRateIngestionService.IngestionReport report = service.ingest(END_DATE, 14);

        assertThat(report.totalUpserted()).isEqualTo(2);
        assertThat(report.hasFailure()).isFalse();
        assertThat(report.ingestedAt()).isEqualTo(Instant.now(CLOCK));
        FxRateIngestionService.PairResult result = report.pairs().get(0);
        assertThat(result.pairCode()).isEqualTo("USDKRW");
        assertThat(result.firstDate()).isEqualTo(LocalDate.of(2026, 9, 4));
        assertThat(result.lastDate()).isEqualTo(LocalDate.of(2026, 9, 5));
        assertThat(result.failureReason()).isNull();

        verify(fxRateRepository).upsert(
                eq("USDKRW"), eq(LocalDate.of(2026, 9, 4)), eq("mid"),
                eq(new BigDecimal("1380.500000")), eq("ECOS"), any());
    }

    @Test
    @DisplayName("JPY 는 100엔 고시를 1단위로 접어 저장한다 — 통화별 분기를 읽는 쪽에 남기지 않는다")
    void ingest_NormalizesJpyQuoteUnit() {
        when(currencyPairRepository.findByStoredTrueOrderByPairCodeAsc())
                .thenReturn(List.of(storedPair("JPYKRW", "JPY")));
        when(historyProvider.fetchHistorical(eq("JPY_KRW"), eq(END_DATE), anyInt()))
                .thenReturn(List.of(snapshot("2026-09-05", 950.0)));

        service.ingest(END_DATE, 14);

        verify(fxRateRepository).upsert(
                eq("JPYKRW"), any(), eq("mid"), eq(new BigDecimal("9.500000")), eq("ECOS"), any());
    }

    @Test
    @DisplayName("관측이 없으면 0건이고 구간은 비어 있다")
    void ingest_NoObservations() {
        when(currencyPairRepository.findByStoredTrueOrderByPairCodeAsc())
                .thenReturn(List.of(storedPair("USDKRW", "USD")));
        when(historyProvider.fetchHistorical(anyString(), any(), anyInt())).thenReturn(List.of());

        FxRateIngestionService.PairResult result = service.ingest(END_DATE, 14).pairs().get(0);

        assertThat(result.upserted()).isZero();
        assertThat(result.firstDate()).isNull();
        assertThat(result.lastDate()).isNull();
    }

    @Test
    @DisplayName("한 쌍이 실패해도 나머지는 계속 적재한다 — 사유는 값으로 남는다")
    void ingest_PartialFailure_ContinuesOthers() {
        when(currencyPairRepository.findByStoredTrueOrderByPairCodeAsc())
                .thenReturn(List.of(storedPair("EURKRW", "EUR"), storedPair("USDKRW", "USD")));
        when(historyProvider.fetchHistorical(eq("EUR_KRW"), any(), anyInt()))
                .thenThrow(new IllegalStateException("ECOS 응답 없음"));
        when(historyProvider.fetchHistorical(eq("USD_KRW"), any(), anyInt()))
                .thenReturn(List.of(snapshot("2026-09-05", 1382.0)));

        FxRateIngestionService.IngestionReport report = service.ingest(END_DATE, 14);

        assertThat(report.hasFailure()).isTrue();
        assertThat(report.totalUpserted()).isEqualTo(1);
        assertThat(report.pairs()).extracting(FxRateIngestionService.PairResult::failureReason)
                .containsExactly("ECOS 응답 없음", null);
    }

    @Test
    @DisplayName("ingestOne 은 통화쌍 표기를 정규화해 받는다")
    void ingestOne_NormalizesPairCode() {
        when(currencyPairRepository.findById("USDKRW"))
                .thenReturn(Optional.of(storedPair("USDKRW", "USD")));
        when(historyProvider.fetchHistorical(eq("USD_KRW"), any(), anyInt()))
                .thenReturn(List.of(snapshot("2026-09-05", 1382.0)));

        assertThat(service.ingestOne("USD_KRW", END_DATE, 14).upserted()).isEqualTo(1);
    }

    @Test
    @DisplayName("마스터에 없는 통화쌍은 400 이다")
    void ingestOne_UnknownPair_Throws() {
        when(currencyPairRepository.findById("CADKRW")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.ingestOne("CADKRW", END_DATE, 14))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("지원하지 않는 통화쌍");
        verify(fxRateRepository, never()).upsert(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("유도 쌍은 적재 대상이 아니다 — 저장하지 않는 쌍에 행을 만들면 is_stored 가 거짓말이 된다")
    void ingestOne_DerivedPair_Throws() {
        when(currencyPairRepository.findById("JPYKRW")).thenReturn(
                Optional.of(MasterFixtures.pair("JPYKRW", "JPY", "KRW", false, "USDJPY")));

        assertThatThrownBy(() -> service.ingestOne("JPYKRW", END_DATE, 14))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("저장 대상이 아닌");
    }

    @Test
    @DisplayName("조회 기간이 0 이하면 400 이다")
    void ingest_NonPositiveLookback_Throws() {
        assertThatThrownBy(() -> service.ingest(END_DATE, 0))
                .isInstanceOf(InvalidRequestException.class);
    }

    @Test
    @DisplayName("null 인자와 의존은 거부한다")
    void nullArguments_Throw() {
        QuoteUnitNormalizer normalizer = new QuoteUnitNormalizer();
        assertThatThrownBy(() -> new FxRateIngestionService(
                null, historyProvider, fxRateRepository, normalizer, CLOCK))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new FxRateIngestionService(
                currencyPairRepository, null, fxRateRepository, normalizer, CLOCK))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new FxRateIngestionService(
                currencyPairRepository, historyProvider, null, normalizer, CLOCK))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new FxRateIngestionService(
                currencyPairRepository, historyProvider, fxRateRepository, null, CLOCK))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new FxRateIngestionService(
                currencyPairRepository, historyProvider, fxRateRepository, normalizer, null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> service.ingest(null, 14))
                .isInstanceOf(NullPointerException.class);
    }
}
