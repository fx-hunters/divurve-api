package com.divurve.domain.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.divurve.common.exception.InvalidRequestException;
import com.divurve.domain.forecast.ForecastService;
import com.divurve.domain.fx.PerUnitFxRates;
import com.divurve.domain.master.MasterDataService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link PlanRateContextProvider} — 계획 계산의 환율 전제 (플래너 명세 §7·§8·§20).
 *
 * <p>세 갈래를 모두 확인한다 — 환율 없음(계산 중단) · Forecast 없음(범위 없이 계속) ·
 * 오래된 데이터(계산 중단). 명세 §20 은 셋을 각각 다르게 다루라고 규정한다.
 */
@DisplayName("PlanRateContextProvider")
class PlanRateContextProviderTest {

    private static final UUID USER_ID = UUID.randomUUID();
    private static final LocalDate BASE_DATE = LocalDate.of(2026, 9, 7);
    private static final Clock CLOCK =
            Clock.fixed(BASE_DATE.atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC);

    private PerUnitFxRates perUnitFxRates;
    private ForecastService forecastService;
    private MasterDataService masterDataService;
    private PlanRateContextProvider provider;

    @BeforeEach
    void setUp() {
        perUnitFxRates = mock(PerUnitFxRates.class);
        forecastService = mock(ForecastService.class);
        masterDataService = mock(MasterDataService.class);
        // 통화 마스터는 DB 표로 옮겨졌다(이슈 #111). 이 테스트가 검증하는 것은 표시 규칙이
        // 계산 전제로 그대로 실려 나가는가이지 마스터 조회 자체가 아니므로, 시드와 같은 값을 세운다.
        lenient().when(masterDataService.requireCurrency(anyString())).thenAnswer(invocation -> {
            String code = invocation.getArgument(0);
            return switch (code) {
                case "USD" -> currencyView("USD", 2, 1);
                case "JPY" -> currencyView("JPY", 0, 100);
                case "GBP" -> currencyView("GBP", 2, 1);
                default -> throw new InvalidRequestException(
                        "지원하지 않는 통화입니다: " + code, "currency_code");
            };
        });
        provider = new PlanRateContextProvider(
                perUnitFxRates, forecastService, masterDataService, CLOCK);
    }

    private static MasterDataService.CurrencyView currencyView(
            String code, int minorUnits, int quoteUnit) {
        return new MasterDataService.CurrencyView(
                code, code, code, (short) minorUnits, (short) quoteUnit,
                "self", false, true, null, "currency-" + code.toLowerCase(), (short) 1);
    }

    private ForecastService.ForecastView forecastView(double lo, double hi, LocalDate baseDate) {
        return new ForecastService.ForecastView(
                "USDKRW", 30, baseDate, 1350.0, 1350.0,
                List.of(), List.of(), List.of(),
                new ForecastService.IntervalView(lo, hi, 0.07),
                new ForecastService.VolatilityView(0.08, 0.4, "normal"),
                new ForecastService.UserImpactView(0L, 0L),
                new ForecastService.LabelsView("band", "path"),
                new ForecastService.ModelInfoView(List.of(0.8), "가정", "한계"),
                "불확실", "면책");
    }

    @Test
    @DisplayName("환율과 예측 구간이 있으면 범위를 그대로 쓴다")
    void resolve_WithForecast_UsesInterval() {
        when(perUnitFxRates.find("USD")).thenReturn(Optional.of(new BigDecimal("1350")));
        when(forecastService.getForecast(any(), anyString(), anyInt()))
                .thenReturn(forecastView(1300.0, 1400.0, BASE_DATE));

        PlanRateContext context = provider.resolve(USER_ID, "USD");

        assertThat(context.lowRate()).isEqualTo(1300.0);
        assertThat(context.baseRate()).isEqualTo(1350.0);
        assertThat(context.highRate()).isEqualTo(1400.0);
        assertThat(context.forecastAvailable()).isTrue();
        assertThat(context.forecastAsOf()).isNotNull();
    }

    @Test
    @DisplayName("기준은 Forecast 가 아니라 지금 조회한 환율이다 — 사용자가 마주할 값이어야 한다")
    void resolve_BaseRate_ComesFromCurrentRate() {
        when(perUnitFxRates.find("USD")).thenReturn(Optional.of(new BigDecimal("1360")));
        when(forecastService.getForecast(any(), anyString(), anyInt()))
                .thenReturn(forecastView(1300.0, 1400.0, BASE_DATE));

        assertThat(provider.resolve(USER_ID, "USD").baseRate()).isEqualTo(1360.0);
    }

    @Test
    @DisplayName("구간이 기준 환율을 감싸지 못하면 기준 쪽으로 넓힌다")
    void resolve_IntervalNotContainingBase_IsWidened() {
        when(perUnitFxRates.find("USD")).thenReturn(Optional.of(new BigDecimal("1500")));
        when(forecastService.getForecast(any(), anyString(), anyInt()))
                .thenReturn(forecastView(1300.0, 1400.0, BASE_DATE));

        PlanRateContext context = provider.resolve(USER_ID, "USD");

        assertThat(context.lowRate()).isEqualTo(1300.0);
        assertThat(context.baseRate()).isEqualTo(1500.0);
        assertThat(context.highRate()).isEqualTo(1500.0);
    }

    @Test
    @DisplayName("Forecast 가 없으면 범위 없이 계산한다 — 명세 §20")
    void resolve_WithoutForecast_UsesFlatRange() {
        when(perUnitFxRates.find("USD")).thenReturn(Optional.of(new BigDecimal("1350")));
        when(forecastService.getForecast(any(), anyString(), anyInt()))
                .thenThrow(new InvalidRequestException("관측이 부족합니다."));

        PlanRateContext context = provider.resolve(USER_ID, "USD");

        assertThat(context.lowRate()).isEqualTo(1350.0);
        assertThat(context.highRate()).isEqualTo(1350.0);
        assertThat(context.forecastAvailable()).isFalse();
        assertThat(context.forecastAsOf()).isNull();
    }

    @Test
    @DisplayName("환율이 없으면 계획을 계산하지 않는다 — 명세 §20")
    void resolve_WithoutRate_Throws() {
        when(perUnitFxRates.find("USD")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> provider.resolve(USER_ID, "USD"))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("환율을 조회할 수 없어")
                .hasFieldOrPropertyWithValue("field", "currency_code");
    }

    @Test
    @DisplayName("환율이 0 이하면 계산하지 않는다 — 나눗셈이 성립하지 않는다")
    void resolve_NonPositiveRate_Throws() {
        when(perUnitFxRates.find("USD")).thenReturn(Optional.of(BigDecimal.ZERO));

        assertThatThrownBy(() -> provider.resolve(USER_ID, "USD"))
                .isInstanceOf(InvalidRequestException.class);
    }

    @Test
    @DisplayName("지원하지 않는 통화는 거부한다")
    void resolve_UnsupportedCurrency_Throws() {
        when(perUnitFxRates.find("XYZ")).thenReturn(Optional.of(new BigDecimal("100")));

        assertThatThrownBy(() -> provider.resolve(USER_ID, "XYZ"))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("지원하지 않는 통화");
    }

    @Test
    @DisplayName("환율이 오래됐으면 계산하지 않는다 — 명세 §8")
    void resolve_StaleRate_Throws() {
        when(perUnitFxRates.find("USD")).thenReturn(Optional.of(new BigDecimal("1350")));
        when(forecastService.getForecast(any(), anyString(), anyInt()))
                .thenReturn(forecastView(1300.0, 1400.0, BASE_DATE.minusDays(10)));

        assertThatThrownBy(() -> provider.resolve(USER_ID, "USD"))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("갱신 후 다시 시도");
    }

    @Test
    @DisplayName("주말을 낀 종가는 오래된 것으로 보지 않는다 — 금요일 종가가 월요일까지 최신이다")
    void resolve_WeekendOldRate_IsAccepted() {
        when(perUnitFxRates.find("USD")).thenReturn(Optional.of(new BigDecimal("1350")));
        when(forecastService.getForecast(any(), anyString(), anyInt()))
                .thenReturn(forecastView(1300.0, 1400.0, BASE_DATE.minusDays(4)));

        assertThat(provider.resolve(USER_ID, "USD")).isNotNull();
    }

    @Test
    @DisplayName("JPY 는 소수 자릿수 0, 고시 단위 100 을 그대로 전달한다 — 명세 §7")
    void resolve_Jpy_CarriesQuoteUnit() {
        when(perUnitFxRates.find("JPY")).thenReturn(Optional.of(new BigDecimal("9.5")));
        when(forecastService.getForecast(any(), anyString(), anyInt()))
                .thenThrow(new IllegalStateException("no data"));

        PlanRateContext context = provider.resolve(USER_ID, "JPY");

        assertThat(context.minorUnits()).isZero();
        assertThat(context.quoteUnit()).isEqualTo(100);
        // 환율은 이미 1엔 기준으로 접혀 들어온다 (§21-6).
        assertThat(context.baseRate()).isEqualTo(9.5);
    }

    @Test
    @DisplayName("스프레드·수수료는 은행 환전 조건 마스터에서 온다 — 명세 §7")
    void resolve_CostAssumptions_ComeFromMaster() {
        when(perUnitFxRates.find("USD")).thenReturn(Optional.of(new BigDecimal("1350")));
        when(forecastService.getForecast(any(), anyString(), anyInt()))
                .thenReturn(forecastView(1300.0, 1400.0, BASE_DATE));

        PlanRateContext context = provider.resolve(USER_ID, "USD");

        assertThat(context.spreadRatio()).isPositive();
        assertThat(context.feeKrw()).isPositive();
    }

    @Test
    @DisplayName("환전 조건 마스터에 없는 통화는 기본 스프레드와 무수수료로 둔다")
    void resolve_CurrencyWithoutTerms_FallsBackToDefaults() {
        // GBP 는 지원 통화이지만 은행 환전 조건 마스터에는 없다 — 값을 지어내지 않고
        // 문서화된 기본값으로 계산한다.
        when(perUnitFxRates.find("GBP")).thenReturn(Optional.of(new BigDecimal("1700")));
        when(forecastService.getForecast(any(), anyString(), anyInt()))
                .thenThrow(new IllegalStateException("no data"));

        PlanRateContext context = provider.resolve(USER_ID, "GBP");

        assertThat(context.spreadRatio())
                .isEqualTo(com.divurve.domain.settings.BankSpreadTable.DEFAULT_BASE_SPREAD_RATIO);
        assertThat(context.feeKrw()).isZero();
    }

    @Test
    @DisplayName("모델 경로는 읽지 않는다 — FR-FC-12")
    void resolve_DoesNotReadModelPath() {
        when(perUnitFxRates.find("USD")).thenReturn(Optional.of(new BigDecimal("1350")));
        ForecastService.ForecastView view = forecastView(1300.0, 1400.0, BASE_DATE);
        when(forecastService.getForecast(any(), anyString(), anyInt())).thenReturn(view);

        provider.resolve(USER_ID, "USD");

        // getForecast 는 부르되 modelPath 는 응답에서 꺼내지 않는다 — 계약 수준의 금지다.
        verify(forecastService).getForecast(USER_ID, "USDKRW", ForecastService.DEFAULT_HORIZON_DAYS);
        assertThat(view.modelPath()).isEmpty();
    }

    @Test
    @DisplayName("null 인자와 의존은 거부한다")
    void nullArguments_Throw() {
        assertThatThrownBy(() -> new PlanRateContextProvider(
                null, forecastService, masterDataService, CLOCK))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new PlanRateContextProvider(
                perUnitFxRates, null, masterDataService, CLOCK))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new PlanRateContextProvider(
                perUnitFxRates, forecastService, null, CLOCK))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new PlanRateContextProvider(
                perUnitFxRates, forecastService, masterDataService, null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> provider.resolve(null, "USD"))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> provider.resolve(USER_ID, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("계산 기준 시각은 Forecast 기준일을 따른다")
    void resolve_RateAsOf_FollowsForecastBaseDate() {
        when(perUnitFxRates.find("USD")).thenReturn(Optional.of(new BigDecimal("1350")));
        when(forecastService.getForecast(any(), anyString(), anyInt()))
                .thenReturn(forecastView(1300.0, 1400.0, BASE_DATE.minusDays(1)));

        PlanRateContext context = provider.resolve(USER_ID, "USD");

        assertThat(context.rateAsOf())
                .isEqualTo(BASE_DATE.minusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant());
    }

    @Test
    @DisplayName("Forecast 가 없으면 기준 시각은 지금이다 — 환율은 방금 조회했다")
    void resolve_WithoutForecast_RateAsOfIsNow() {
        when(perUnitFxRates.find("USD")).thenReturn(Optional.of(new BigDecimal("1350")));
        when(forecastService.getForecast(any(), anyString(), anyInt()))
                .thenThrow(new IllegalStateException("no data"));

        assertThat(provider.resolve(USER_ID, "USD").rateAsOf()).isEqualTo(Instant.now(CLOCK));
    }
}
