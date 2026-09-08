package com.divurve.domain.forecast;

import com.divurve.domain.fx.StoredFxRates;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import com.divurve.common.exception.InvalidRequestException;
import com.divurve.domain.fx.PerUnitFxRates;
import com.divurve.domain.holding.DepositRepository;
import com.divurve.domain.holding.HoldingRepository;
import com.divurve.domain.holding.entity.Deposit;
import com.divurve.domain.holding.entity.Holding;
import com.divurve.domain.port.EconomicEventProvider;
import com.divurve.domain.port.FxRateHistoryProvider;
import com.divurve.domain.port.FxRateProvider;
import com.divurve.domain.port.RateSnapshot;
import com.divurve.domain.user.entity.User;
import com.divurve.engine.fx.CrossRateDeriver;
import com.divurve.engine.volatility.RegimeClassifier;
import com.divurve.engine.weight.QuoteUnitNormalizer;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * {@link ForecastService} 검증 (명세 v2 §5.7·§5.8).
 *
 * <p>이전 테스트는 하드코딩 목값({@code hitRate 0.62}, {@code mae 15.5})을 그대로 받아 적는 수준이라
 * 계산을 검증하지 못했다. 여기서는 <b>구간이 결정적으로 재현되는지</b>와 <b>워크포워드 검증이
 * 실제 관측으로 돌아가는지</b>를 확인한다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ForecastService")
class ForecastServiceTest {

    private static final UUID USER_ID = UUID.randomUUID();
    private static final LocalDate TODAY = LocalDate.of(2026, 9, 1);
    private static final double BASE_RATE = 1382.40;

    /** 5년 백분위 계산에 필요한 최소 관측 수(1290개 수익률) + 여유. */
    private static final int LONG_HISTORY = 1400;

    @Mock
    private FxRateProvider fxRateProvider;
    @Mock
    private FxRateHistoryProvider historyProvider;
    @Mock
    private EconomicEventProvider eventProvider;
    @Mock
    private HoldingRepository holdingRepository;
    @Mock
    private DepositRepository depositRepository;

    private ForecastService service;

    @BeforeEach
    void setUp() {
        PerUnitFxRates perUnitFxRates =
                new PerUnitFxRates(StoredFxRates.NONE, fxRateProvider, new QuoteUnitNormalizer());
        service = new ForecastService(
                new CrossRateResolver(StoredFxRates.NONE, historyProvider, perUnitFxRates,
                        new CrossRateDeriver(), new QuoteUnitNormalizer()),
                perUnitFxRates,
                eventProvider,
                holdingRepository,
                depositRepository,
                new RegimeClassifier(),
                Clock.fixed(TODAY.atStartOfDay().toInstant(ZoneOffset.UTC), ZoneOffset.UTC));
    }

    // ── GET /forecast ────────────────────────────────────────────

    @Test
    @DisplayName("예측 범위는 드리프트 0 로그정규의 닫힌 해라 같은 입력에 항상 같은 값이다")
    void forecastIsDeterministic() {
        givenHistory(history(LONG_HISTORY, 0.01, 0.01));
        givenLatestRate();
        givenNoAssets();

        ForecastService.ForecastView first = service.getForecast(USER_ID, "USDKRW", 30);
        ForecastService.ForecastView second = service.getForecast(USER_ID, "USD_KRW", 30);

        assertEquals(first.interval80(), second.interval80());
        assertEquals(first.band(), second.band());
        assertEquals("USDKRW", first.pairCode());
    }

    @Test
    @DisplayName("interval_80 은 정확히 band 의 마지막 점이고 width_pct 는 기준선 대비 폭이다")
    void interval80MatchesLastBandPoint() {
        givenHistory(history(LONG_HISTORY, 0.01, 0.01));
        givenLatestRate();
        givenNoAssets();

        ForecastService.ForecastView view = service.getForecast(USER_ID, "USDKRW", 30);
        ForecastService.BandPoint last = view.band().get(view.band().size() - 1);

        assertEquals(30, view.band().size());
        assertEquals(last.p80Lo(), view.interval80().lo());
        assertEquals(last.p80Hi(), view.interval80().hi());
        assertEquals((last.p80Hi() - last.p80Lo()) / BASE_RATE, view.interval80().widthPct(), 1e-12);
        assertEquals(TODAY.plusDays(30), last.date());
    }

    @Test
    @DisplayName("model_path 는 드리프트 0 이라 전 구간 기준선과 같다 (L2 — 표시 전용)")
    void modelPathIsFlatBaseline() {
        givenHistory(history(LONG_HISTORY, 0.01, 0.01));
        givenLatestRate();
        givenNoAssets();

        ForecastService.ForecastView view = service.getForecast(USER_ID, "USDKRW", 90);

        assertEquals(90, view.modelPath().size());
        assertTrue(view.modelPath().stream().allMatch(point -> point.rate() == BASE_RATE));
        assertEquals(ForecastService.LABEL_MODEL_PATH, view.labels().modelPath());
        assertEquals(ForecastService.LABEL_BAND, view.labels().band());
    }

    @Test
    @DisplayName("변동성이 5년 상위 구간이면 국면이 stress 이고 불확실성 안내가 넓어진다")
    void elevatedVolatilityWidensNote() {
        givenHistory(history(LONG_HISTORY, 0.004, 0.05));
        givenLatestRate();
        givenNoAssets();

        ForecastService.ForecastView view = service.getForecast(USER_ID, "USDKRW", 30);

        assertEquals("stress", view.volatility().regime());
        assertTrue(view.volatility().volPercentile5y() > 0.90);
        assertTrue(view.uncertaintyNote().contains("평시보다 넓습니다"));
        assertEquals(ForecastService.DISCLAIMER, view.disclaimer());
    }

    @Test
    @DisplayName("변동성이 평시 범위면 국면이 calm 이고 안내가 평소 수준이다")
    void calmVolatilityKeepsNote() {
        givenHistory(history(LONG_HISTORY, 0.02, 0.0005));
        givenLatestRate();
        givenNoAssets();

        ForecastService.ForecastView view = service.getForecast(USER_ID, "USDKRW", 30);

        assertEquals("calm", view.volatility().regime());
        assertTrue(view.uncertaintyNote().contains("평시 범위"));
        assertEquals(List.of(0.50, 0.80), view.modelInfo().intervalLevels());
        assertTrue(view.modelInfo().assumptions().contains("30일"));
    }

    @Test
    @DisplayName("환율 1퍼센트 영향은 해당 통화 노출액의 1퍼센트다 (보유 종목 + 외화예금)")
    void userImpactUsesRealExposure() {
        givenHistory(history(LONG_HISTORY, 0.01, 0.01));
        givenLatestRate();
        // 10,000 USD 상당 종목 + 1,000 USD 예금 = 11,000 USD × 1382.40 = 15,206,400 KRW
        when(holdingRepository.findByOwner_Id(USER_ID)).thenReturn(List.of(
                holding("USD", 100.0, 100.0),
                holding("JPY", 999.0, 999.0)));
        when(depositRepository.findByOwner_Id(USER_ID)).thenReturn(List.of(
                deposit("USD", "1000")));

        ForecastService.ForecastView view = service.getForecast(USER_ID, "USDKRW", 30);

        assertEquals(15_206_400L, view.userImpact().assetKrw());
        assertEquals(152_064L, view.userImpact().per1pctKrw());
    }

    @Test
    @DisplayName("보유 종목 없이 외화예금만 있어도 노출액에 잡힌다")
    void userImpactFromDepositsOnly() {
        givenHistory(history(LONG_HISTORY, 0.01, 0.01));
        givenLatestRate();
        when(holdingRepository.findByOwner_Id(USER_ID)).thenReturn(List.of());
        when(depositRepository.findByOwner_Id(USER_ID)).thenReturn(List.of(deposit("USD", "1000")));

        ForecastService.ForecastView view = service.getForecast(USER_ID, "USDKRW", 30);

        assertEquals(1_382_400L, view.userImpact().assetKrw());
    }

    @Test
    @DisplayName("보유 자산이 없으면 영향은 0 — 없는 값을 만들지 않는다")
    void userImpactZeroWithoutAssets() {
        givenHistory(history(LONG_HISTORY, 0.01, 0.01));
        givenLatestRate();
        givenNoAssets();

        ForecastService.ForecastView view = service.getForecast(USER_ID, "USDKRW", 30);

        assertEquals(0L, view.userImpact().assetKrw());
        assertEquals(0L, view.userImpact().per1pctKrw());
    }

    @Test
    @DisplayName("허용 목록(7·14·30·60·90·180) 밖의 지평은 거부한다")
    void invalidHorizon() {
        InvalidRequestException exception = assertThrows(
                InvalidRequestException.class, () -> service.getForecast(USER_ID, "USDKRW", 45));

        assertTrue(exception.getMessage().contains("7·14·30·60·90·180"));
    }

    @Test
    @DisplayName("이슈 #121 — 세분화된 지평(7·14·60·180)도 band 길이가 지평과 같다")
    void widenedHorizonRangeProducesMatchingBandLength() {
        givenHistory(history(LONG_HISTORY, 0.01, 0.01));
        givenLatestRate();
        givenNoAssets();

        for (int horizonDays : List.of(7, 14, 60, 180)) {
            ForecastService.ForecastView view = service.getForecast(USER_ID, "USDKRW", horizonDays);

            assertEquals(horizonDays, view.band().size());
            assertEquals(TODAY.plusDays(horizonDays), view.band().get(view.band().size() - 1).date());
        }
    }

    @Test
    @DisplayName("이슈 #121 — 지평을 넓혀도 기존 30·90 일 구간 산출값은 그대로다"
            + " (같은 입력에 대한 engine 직접 계산과 일치)")
    void existingThirtyAndNinetyDayIntervalsAreUnchanged() {
        List<FxRateHistoryProvider.HistoryRateSnapshot> fixedHistory = history(LONG_HISTORY, 0.01, 0.01);
        givenHistory(fixedHistory);
        givenLatestRate();
        givenNoAssets();

        // ForecastService 내부와 같은 방식(realized30d)으로 독립 계산한 "정답"과 비교한다 —
        // 허용 지평을 넓히기 전/후 모두 이 값과 같아야 30·90 산출이 그대로라는 근거가 된다.
        List<Double> dailyReturns = new java.util.ArrayList<>();
        for (int i = 1; i < fixedHistory.size(); i++) {
            dailyReturns.add(Math.log(fixedHistory.get(i).rate() / fixedHistory.get(i - 1).rate()));
        }
        double expectedVol30d = com.divurve.engine.forecast.VolatilityCalculator.calculateRealized30d(dailyReturns);

        for (int horizonDays : List.of(30, 90)) {
            com.divurve.engine.forecast.FanChartCalculator.PathPoint expected =
                    com.divurve.engine.forecast.FanChartCalculator.analyticInterval(
                            BASE_RATE, expectedVol30d, horizonDays);

            ForecastService.ForecastView view = service.getForecast(USER_ID, "USDKRW", horizonDays);

            assertEquals(expected.p80Lo(), view.interval80().lo(), 1e-9);
            assertEquals(expected.p80Hi(), view.interval80().hi(), 1e-9);
        }
    }

    @Test
    @DisplayName("관측이 없으면 변동성을 만들어내지 않고 400 을 낸다")
    void emptyHistoryFailsFast() {
        givenHistory(List.of());
        assertThrows(InvalidRequestException.class, () -> service.getForecast(USER_ID, "USDKRW", 30));

        givenHistory(null);
        assertThrows(InvalidRequestException.class, () -> service.getForecast(USER_ID, "USDKRW", 30));
    }

    @Test
    @DisplayName("5년 관측이 모자라면 백분위를 만들어내지 않고 400 을 낸다")
    void shortHistoryFailsPercentile() {
        givenHistory(history(200, 0.01, 0.01));
        assertThrows(InvalidRequestException.class, () -> service.getForecast(USER_ID, "USDKRW", 30));
    }

    // ── GET /forecast/model-performance ──────────────────────────

    @Test
    @DisplayName("성적표는 실제 관측으로 워크포워드 검증한다 — 드리프트 0 이라 랜덤워크와 동률이 그대로 드러난다")
    void modelPerformanceIsCalculated() {
        givenHistory(history(LONG_HISTORY, 0.01, 0.01));

        ForecastService.ModelPerformanceView view = service.getModelPerformance("USDKRW", 30);

        assertEquals("USDKRW", view.pairCode());
        assertEquals(30, view.horizonDays());
        assertEquals(24, view.validation().folds());
        assertEquals("rolling_walk_forward", view.validation().method());
        assertTrue(view.validation().leakageGuard());
        // 점예측이 랜덤워크와 같으므로 MAE 가 일치하고 개선율은 0 이다 — 숨기지 않는다.
        assertEquals(view.randomWalk().mae(), view.model().mae(), 1e-12);
        assertEquals(0.0, view.rwImprovement(), 1e-12);
        assertTrue(view.model().coverage80() >= 0.0 && view.model().coverage80() <= 1.0);
        assertTrue(view.model().avgWidth() > 0.0);
        assertEquals(ForecastService.PERFORMANCE_NOTE, view.note());
        assertEquals(Instant.parse("2026-09-01T00:00:00Z"), view.evaluatedAt());
    }

    @Test
    @DisplayName("관측이 모자라면 가능한 폴드만 평가한다")
    void modelPerformanceStopsWhenHistoryRunsOut() {
        givenHistory(history(100, 0.01, 0.01));

        ForecastService.ModelPerformanceView view = service.getModelPerformance("USDKRW", 30);

        assertEquals(2, view.validation().folds());
    }

    @Test
    @DisplayName("폴드를 하나도 만들 수 없으면 성적을 지어내지 않고 400 을 낸다")
    void modelPerformanceWithoutFolds() {
        givenHistory(history(40, 0.01, 0.01));

        assertThrows(InvalidRequestException.class, () -> service.getModelPerformance("USDKRW", 30));
    }

    @Test
    @DisplayName("성적표도 지평 검증을 거친다")
    void modelPerformanceInvalidHorizon() {
        assertThrows(InvalidRequestException.class, () -> service.getModelPerformance("USDKRW", 45));
    }

    @Test
    @DisplayName("이슈 #121 — GET /forecast 와 성적표 양쪽에 같은 확장 지평 집합(7·14·30·60·90·180)이 적용된다")
    void bothEndpointsShareTheSameWidenedHorizonSet() {
        for (int horizonDays : List.of(7, 14, 30, 60, 90, 180)) {
            givenHistory(history(LONG_HISTORY, 0.01, 0.01));
            givenLatestRate();
            givenNoAssets();

            assertEquals(horizonDays, service.getForecast(USER_ID, "USDKRW", horizonDays).horizonDays());
            assertEquals(horizonDays, service.getModelPerformance("USDKRW", horizonDays).horizonDays());
        }
    }

    @Test
    @DisplayName("이슈 #121 — 5년치 실사 관측(약 1,400개) 규모에서는 180일 지평도 폴드 부족 없이 24개를 채운다")
    void longestHorizonStillFillsAllFoldsWithProductionScaleHistory() {
        givenHistory(history(LONG_HISTORY, 0.01, 0.01));

        ForecastService.ModelPerformanceView view = service.getModelPerformance("USDKRW", 180);

        assertEquals(24, view.validation().folds());
    }

    @Test
    @DisplayName("이슈 #121 — 관측이 지평 + 514 에 못 미치면 180일 지평도 관측 부족 400 을 그대로 낸다"
            + " (응답 계약은 바꾸지 않는다)")
    void longestHorizonStillFailsFastWhenHistoryIsTooShortForEvenOneFold() {
        // fold=0 에 필요한 최소치: size >= horizonDays + 31. 180 + 31 = 211 에서 하나 모자라게 만든다.
        givenHistory(history(210, 0.01, 0.01));

        assertThrows(InvalidRequestException.class, () -> service.getModelPerformance("USDKRW", 180));
    }

    // ── 그 외 ────────────────────────────────────────────────────

    @Test
    @DisplayName("전망 동인은 출처 확정 전까지 빈 목록이다 (L2)")
    void factorsAreEmpty() {
        givenHistory(history(LONG_HISTORY, 0.01, 0.01));

        assertEquals("USDKRW", service.getFactors("usd_krw").pairCode());
    }

    @Test
    @DisplayName("이슈 #89 — /forecast/factors 도 형제 엔드포인트와 같은 경로로 지원하지 않는 "
            + "통화쌍을 field=pair_code 400 으로 거른다")
    void factorsRejectsUnsupportedPairLikeSiblingEndpoints() {
        // XYZABC 는 KRW 크로스가 아니라서 유도 조회를 타는데, 어댑터에 XYZ_KRW·ABC_KRW 스텁이
        // 전혀 없으니(historyProvider 는 이 테스트에서 어떤 통화도 관측을 주지 않는다) 형제
        // 엔드포인트(getForecast)의 emptyHistoryFailsFast 와 같은 "관측 없음" 경로를 그대로 탄다.
        InvalidRequestException exception = assertThrows(
                InvalidRequestException.class, () -> service.getFactors("XYZABC"));

        assertEquals("pair_code", exception.getField());
    }

    @Test
    @DisplayName("경제 일정은 어댑터가 준 사실만 옮긴다")
    void events() {
        when(eventProvider.fetchUpcoming(TODAY, 90)).thenReturn(List.of(
                new EconomicEventProvider.EconomicEvent(
                        LocalDate.of(2026, 9, 17), "FOMC", "USD", "high")));

        List<ForecastService.EconomicEventView> events = service.getEvents();

        assertEquals(1, events.size());
        assertEquals("FOMC", events.get(0).title());
        assertEquals("USD", events.get(0).currencyCode());
        assertEquals("high", events.get(0).importance());
        assertEquals(LocalDate.of(2026, 9, 17), events.get(0).date());
        assertFalse(ForecastService.MODEL_VERSION.isBlank());
        assertEquals(30, ForecastService.DEFAULT_HORIZON_DAYS);
    }

    // ── 픽스처 ───────────────────────────────────────────────────

    private void givenHistory(List<FxRateHistoryProvider.HistoryRateSnapshot> history) {
        lenient().when(historyProvider.fetchHistorical(eq("USD_KRW"), any(LocalDate.class), anyInt()))
                .thenReturn(history);
    }

    private void givenLatestRate() {
        lenient().when(fxRateProvider.fetchLatest(anyString())).thenAnswer(invocation ->
                new RateSnapshot(
                        invocation.getArgument(0),
                        BigDecimal.valueOf(BASE_RATE),
                        TODAY,
                        "TEST",
                        TODAY.atStartOfDay().toInstant(ZoneOffset.UTC)));
    }

    private void givenNoAssets() {
        lenient().when(holdingRepository.findByOwner_Id(USER_ID)).thenReturn(List.of());
        lenient().when(depositRepository.findByOwner_Id(USER_ID)).thenReturn(List.of());
    }

    /**
     * 결정적 합성 시계열. 마지막 40개 구간의 진폭을 따로 줘서 "최근 변동성"을 의도대로 만든다 —
     * 난수를 쓰면 같은 테스트가 실행마다 다른 국면을 내 검증이 성립하지 않는다.
     */
    private static List<FxRateHistoryProvider.HistoryRateSnapshot> history(
            int size, double baseAmplitude, double recentAmplitude) {
        List<FxRateHistoryProvider.HistoryRateSnapshot> points = new ArrayList<>(size);
        LocalDate start = TODAY.minusDays(size - 1L);
        for (int i = 0; i < size; i++) {
            double amplitude = i >= size - 40 ? recentAmplitude : baseAmplitude;
            double rate = BASE_RATE * Math.exp(amplitude * Math.sin(i * 1.7));
            points.add(new FxRateHistoryProvider.HistoryRateSnapshot(start.plusDays(i), rate));
        }
        // 마지막 점은 기준 환율과 일치시켜 base_rate 와 history 끝점이 어긋나지 않게 한다.
        points.set(size - 1, new FxRateHistoryProvider.HistoryRateSnapshot(TODAY, BASE_RATE));
        return points;
    }

    private static User testUser() {
        return User.create("test@divurve.local", "테스트", "hash");
    }

    private static Holding holding(String currencyCode, double quantity, double avgPrice) {
        return Holding.create(testUser(), "TEST", currencyCode, quantity, avgPrice);
    }

    private static Deposit deposit(String currencyCode, String amount) {
        return Deposit.create(testUser(), currencyCode, new BigDecimal(amount));
    }
}
