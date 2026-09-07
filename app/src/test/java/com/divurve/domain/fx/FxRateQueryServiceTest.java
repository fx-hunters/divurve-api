package com.divurve.domain.fx;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.divurve.common.exception.InvalidRequestException;
import com.divurve.domain.fx.entity.FxRate;
import com.divurve.domain.master.CurrencyPairRepository;
import com.divurve.support.MasterFixtures;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * {@link FxRateQueryService} — 관리자 차트가 읽는 시계열.
 */
@DisplayName("FxRateQueryService")
class FxRateQueryServiceTest {

    private static final LocalDate FROM = LocalDate.of(2026, 9, 1);
    private static final LocalDate TO = LocalDate.of(2026, 9, 7);

    private CurrencyPairRepository currencyPairRepository;
    private FxRateRepository fxRateRepository;
    private FxRateQueryService service;

    @BeforeEach
    void setUp() {
        currencyPairRepository = mock(CurrencyPairRepository.class);
        fxRateRepository = mock(FxRateRepository.class);
        service = new FxRateQueryService(currencyPairRepository, fxRateRepository);
    }

    private static FxRate fxRate(String pairCode, LocalDate date, String rate) {
        FxRate entity = instantiate(FxRate.class);
        Object id = instantiate(com.divurve.domain.fx.entity.FxRateId.class);
        ReflectionTestUtils.setField(id, "pairCode", pairCode);
        ReflectionTestUtils.setField(id, "quoteDate", date);
        ReflectionTestUtils.setField(id, "rateType", "mid");
        ReflectionTestUtils.setField(entity, "id", id);
        ReflectionTestUtils.setField(entity, "rate", new BigDecimal(rate));
        ReflectionTestUtils.setField(entity, "dataSource", "ECOS");
        ReflectionTestUtils.setField(entity, "fetchedAt", Instant.parse("2026-09-07T00:30:00Z"));
        return entity;
    }

    private static <T> T instantiate(Class<T> type) {
        try {
            var constructor = type.getDeclaredConstructor();
            constructor.setAccessible(true);
            return constructor.newInstance();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private void givenStoredPair() {
        when(currencyPairRepository.findById("USDKRW"))
                .thenReturn(Optional.of(MasterFixtures.pair("USDKRW", "USD", "KRW", true, null)));
    }

    @Test
    @DisplayName("저장 쌍의 관측을 그대로 돌려준다 — 파생값을 만들지 않는다")
    void series_ReturnsStoredObservations() {
        givenStoredPair();
        when(fxRateRepository
                .findByIdPairCodeAndIdRateTypeAndIdQuoteDateBetweenOrderByIdQuoteDateAsc(
                        eq("USDKRW"), eq("mid"), eq(FROM), eq(TO)))
                .thenReturn(List.of(fxRate("USDKRW", LocalDate.of(2026, 9, 4), "1380.500000")));

        FxRateQueryService.RateSeries series = service.series("USDKRW", FROM, TO, "mid");

        assertThat(series.pairCode()).isEqualTo("USDKRW");
        assertThat(series.rateType()).isEqualTo("mid");
        assertThat(series.from()).isEqualTo(FROM);
        assertThat(series.to()).isEqualTo(TO);
        assertThat(series.points()).singleElement().satisfies(point -> {
            assertThat(point.quoteDate()).isEqualTo(LocalDate.of(2026, 9, 4));
            assertThat(point.rate()).isEqualByComparingTo("1380.5");
            assertThat(point.dataSource()).isEqualTo("ECOS");
            assertThat(point.fetchedAt()).isEqualTo(Instant.parse("2026-09-07T00:30:00Z"));
        });
    }

    @Test
    @DisplayName("언더스코어 표기도 받는다 — 어댑터 표기와 API 표기가 다르다")
    void series_AcceptsProviderNotation() {
        givenStoredPair();
        when(fxRateRepository
                .findByIdPairCodeAndIdRateTypeAndIdQuoteDateBetweenOrderByIdQuoteDateAsc(
                        any(), any(), any(), any()))
                .thenReturn(List.of());

        assertThat(service.series("USD_KRW", FROM, TO, "mid").pairCode()).isEqualTo("USDKRW");
    }

    @Test
    @DisplayName("관측이 없으면 빈 목록이다 — 없는 날짜를 채우지 않는다")
    void series_EmptyWhenNoObservations() {
        givenStoredPair();
        when(fxRateRepository
                .findByIdPairCodeAndIdRateTypeAndIdQuoteDateBetweenOrderByIdQuoteDateAsc(
                        any(), any(), any(), any()))
                .thenReturn(List.of());

        assertThat(service.series("USDKRW", FROM, TO, "mid").points()).isEmpty();
    }

    @Test
    @DisplayName("유도 쌍은 400 이다 — 저장값과 유도값이 한 응답에 섞이면 무엇이 관측인지 알 수 없다")
    void series_DerivedPair_Throws() {
        when(currencyPairRepository.findById("JPYKRW")).thenReturn(
                Optional.of(MasterFixtures.pair("JPYKRW", "JPY", "KRW", false, "USDJPY")));

        assertThatThrownBy(() -> service.series("JPYKRW", FROM, TO, "mid"))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("저장하지 않는");
    }

    @Test
    @DisplayName("마스터에 없는 통화쌍은 400 이다")
    void series_UnknownPair_Throws() {
        when(currencyPairRepository.findById("CADKRW")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.series("CADKRW", FROM, TO, "mid"))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("지원하지 않는 통화쌍");
    }

    @Test
    @DisplayName("알 수 없는 환율 종류는 400 이다")
    void series_UnknownRateType_Throws() {
        givenStoredPair();

        assertThatThrownBy(() -> service.series("USDKRW", FROM, TO, "spot"))
                .isInstanceOf(InvalidRequestException.class);
    }

    @Test
    @DisplayName("기간이 뒤집혔거나 상한을 넘으면 400 이다")
    void series_InvalidRange_Throws() {
        assertThatThrownBy(() -> service.series("USDKRW", TO, FROM, "mid"))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("시작일");
        assertThatThrownBy(() -> service.series(
                "USDKRW", FROM, FROM.plusDays(FxRateQueryService.MAX_DAYS + 1), "mid"))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("최대");
    }

    @Test
    @DisplayName("null 인자와 의존은 거부한다")
    void nullArguments_Throw() {
        assertThatThrownBy(() -> new FxRateQueryService(null, fxRateRepository))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new FxRateQueryService(currencyPairRepository, null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> service.series("USDKRW", null, TO, "mid"))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> service.series("USDKRW", FROM, null, "mid"))
                .isInstanceOf(NullPointerException.class);
    }
}
