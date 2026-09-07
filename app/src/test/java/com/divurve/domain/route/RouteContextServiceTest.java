package com.divurve.domain.route;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.divurve.domain.market.MarketRegimeService;
import com.divurve.domain.plan.PlanRateContext;
import com.divurve.domain.plan.PlanRateContextProvider;
import com.divurve.domain.settings.RiskProfileService;
import com.divurve.domain.settings.RiskProfileView;
import com.divurve.domain.stress.StressRunService;
import com.divurve.domain.xray.XrayService;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link RouteContextService} · {@link RouteContext} — 전달 계약을 검증한다.
 *
 * <p>두 가지가 핵심이다.
 * <ul>
 *   <li><b>계산하지 않고 모아 전달만 한다</b> — 값이 원본과 달라지면 그 자체가 회귀다.</li>
 *   <li><b>블록 하나가 비어도 나머지는 낸다</b> — 스트레스 이력이 없다고 자산 요약까지 막히면
 *       안 된다 (명세 §20).</li>
 * </ul>
 */
@DisplayName("RouteContextService")
class RouteContextServiceTest {

    private static final Instant FIXED = Instant.parse("2026-09-06T00:00:00Z");
    private static final UUID USER_ID = UUID.randomUUID();

    private XrayService xrayService;
    private RiskProfileService riskProfileService;
    private PlanRateContextProvider planRateContextProvider;
    private StressRunService stressRunService;
    private MarketRegimeService marketRegimeService;
    private RouteContextService service;

    @BeforeEach
    void setUp() {
        xrayService = mock(XrayService.class);
        riskProfileService = mock(RiskProfileService.class);
        planRateContextProvider = mock(PlanRateContextProvider.class);
        stressRunService = mock(StressRunService.class);
        marketRegimeService = mock(MarketRegimeService.class);
        service = new RouteContextService(
                Clock.fixed(FIXED, ZoneOffset.UTC),
                xrayService, riskProfileService, planRateContextProvider,
                stressRunService, marketRegimeService);
    }

    private void stubAll() {
        when(riskProfileService.getRiskProfile(USER_ID)).thenReturn(new RiskProfileView(
                "detail_done", "balanced", "균형형", 55, LocalDate.of(2026, 9, 1), 0.6,
                null, null, "한계"));
        when(xrayService.getPortfolio(USER_ID)).thenReturn(new XrayService.PortfolioSnapshot(
                10_000_000L, 6_000_000L, 4_000_000L, 0.4,
                Map.of("USD", 4_000_000L), Map.of("USD", 1.0),
                null, null, null, false));
        when(planRateContextProvider.resolve(any(), anyString())).thenReturn(new PlanRateContext(
                "USD", 1300.0, 1350.0, 1400.0, 0.0175, 3000L, 1, 2,
                Instant.parse("2026-09-04T00:00:00Z"), Instant.parse("2026-09-04T00:00:00Z"), true));
        when(stressRunService.listRuns(USER_ID)).thenReturn(List.of());
        when(marketRegimeService.getRegime()).thenReturn(new MarketRegimeService.MarketRegimeView(
                "normal", "정상", "normal", Map.of(), List.of(), null, null));
    }

    @Test
    @DisplayName("각 블록을 원본 값 그대로 전달한다")
    void carriesValuesUnchanged() {
        stubAll();

        RouteContext context = service.getContext(USER_ID);

        assertThat(context.asOf()).isEqualTo(FIXED);
        assertThat(context.diagnosis().status()).isEqualTo("detail_done");
        assertThat(context.diagnosis().grade()).isEqualTo("balanced");
        assertThat(context.diagnosis().score()).isEqualTo(55);
        assertThat(context.diagnosis().concentrationThreshold()).isEqualTo(0.6);
        assertThat(context.portfolio().totalAssetKrw()).isEqualTo(10_000_000L);
        assertThat(context.portfolio().fxAssetKrw()).isEqualTo(4_000_000L);
        assertThat(context.portfolio().fxRatio()).isEqualTo(0.4);
        assertThat(context.portfolio().exposure()).containsEntry("USD", 1.0);
        assertThat(context.regime()).isEqualTo("normal");
    }

    @Test
    @DisplayName("기준 환율은 계획 계산이 쓰는 것과 같은 전제다 — 불변조건 §21-13")
    void forecastMatchesPlanCalculationInput() {
        stubAll();

        RouteContext.Forecast forecast = service.getContext(USER_ID).forecast();

        assertThat(forecast.pairCode()).isEqualTo("USDKRW");
        assertThat(forecast.baseRate()).isEqualTo(1350.0);
        assertThat(forecast.interval80().lo()).isEqualTo(1300.0);
        assertThat(forecast.interval80().hi()).isEqualTo(1400.0);
        assertThat(forecast.baseDate()).isEqualTo(LocalDate.of(2026, 9, 4));
    }

    @Test
    @DisplayName("스트레스 이력이 있으면 가장 최근 실행을 담는다")
    void latestStressRun() {
        stubAll();
        UUID runId = UUID.randomUUID();
        when(stressRunService.listRuns(USER_ID)).thenReturn(List.of(
                new StressRunService.RunHistoryView(
                        runId, null, LocalDate.of(2026, 9, 4), -0.2, 0.1,
                        -100_000L, -20_000L, -120_000L, FIXED)));

        RouteContext.Stress stress = service.getContext(USER_ID).stress();

        assertThat(stress.lastRunId()).isEqualTo(runId.toString());
        assertThat(stress.totalEffectKrw()).isEqualTo(-120_000L);
    }

    @Test
    @DisplayName("스트레스 이력이 없으면 그 블록만 비운다")
    void noStressRun_LeavesBlockEmpty() {
        stubAll();

        RouteContext context = service.getContext(USER_ID);

        assertThat(context.stress().lastRunId()).isNull();
        assertThat(context.portfolio().totalAssetKrw()).isNotNull();
    }

    @Test
    @DisplayName("블록 하나가 실패해도 나머지는 그대로 낸다 — 명세 §20")
    void blockFailure_DoesNotFailWhole() {
        stubAll();
        when(xrayService.getPortfolio(USER_ID)).thenThrow(new IllegalStateException("자산 조회 실패"));
        when(planRateContextProvider.resolve(any(), anyString()))
                .thenThrow(new IllegalStateException("환율 없음"));

        RouteContext context = service.getContext(USER_ID);

        // 실패한 블록은 값을 지어내지 않고 비운다
        assertThat(context.portfolio().totalAssetKrw()).isNull();
        assertThat(context.portfolio().exposure()).isEmpty();
        assertThat(context.forecast().baseRate()).isNull();
        // 나머지는 살아 있다
        assertThat(context.diagnosis().status()).isEqualTo("detail_done");
        assertThat(context.regime()).isEqualTo("normal");
        assertThat(context.asOf()).isEqualTo(FIXED);
    }

    @Test
    @DisplayName("모든 블록이 실패하면 기준 시각만 남는다")
    void allBlocksFail_OnlyAsOfRemains() {
        when(riskProfileService.getRiskProfile(any())).thenThrow(new IllegalStateException("x"));
        when(xrayService.getPortfolio(any())).thenThrow(new IllegalStateException("x"));
        when(planRateContextProvider.resolve(any(), anyString()))
                .thenThrow(new IllegalStateException("x"));
        when(stressRunService.listRuns(any())).thenThrow(new IllegalStateException("x"));
        when(marketRegimeService.getRegime()).thenThrow(new IllegalStateException("x"));

        RouteContext context = service.getContext(USER_ID);

        assertThat(context.asOf()).isEqualTo(FIXED);
        assertThat(context.regime()).isNull();
        assertThat(context.diagnosis().status()).isNull();
        assertThat(context.portfolio().fxRatio()).isNull();
        assertThat(context.forecast().pairCode()).isNull();
        assertThat(context.stress().totalEffectKrw()).isNull();
    }

    @Test
    @DisplayName("노출 맵은 null 이면 빈 맵으로, 값이 있으면 방어적 복사본으로 담긴다")
    void exposureIsNullSafeAndDefensivelyCopied() {
        assertThat(new RouteContext.Portfolio(null, null, null, null).exposure()).isEmpty();

        RouteContext.Portfolio portfolio =
                new RouteContext.Portfolio(1_000L, 400L, 0.4, Map.of("USD", 0.4));

        assertThat(portfolio.exposure()).containsExactly(Map.entry("USD", 0.4));
        assertThat(portfolio.totalAssetKrw()).isEqualTo(1_000L);
        assertThat(portfolio.fxAssetKrw()).isEqualTo(400L);
        assertThat(portfolio.fxRatio()).isEqualTo(0.4);
    }

    /**
     * FR-FC-12 회귀 방지 — 방향 전망(모델 경로·요인 분해)은 Route 계산 입력이 될 수 없다.
     * 계약에 필드가 존재하지 않는다는 것을 리플렉션으로 못박는다.
     */
    @Test
    @DisplayName("model_path·forecast_factors 는 계약에 존재하지 않는다 (FR-FC-12)")
    void forecastContractExcludesDirectionalOutlook() {
        assertThat(RouteContext.Forecast.class.getRecordComponents())
                .extracting(java.lang.reflect.RecordComponent::getName)
                .containsExactly("pairCode", "baseRate", "interval80", "vol30d", "baseDate")
                .doesNotContain("modelPath", "forecastFactors");
    }

    @Test
    @DisplayName("빈 계약의 기본값")
    void emptyContract() {
        RouteContext empty = RouteContext.empty(FIXED);

        assertThat(empty.asOf()).isEqualTo(FIXED);
        assertThat(empty.regime()).isNull();
        assertThat(empty.diagnosis().score()).isNull();
        assertThat(empty.forecast().interval80().lo()).isNull();
        assertThat(empty.forecast().interval80().hi()).isNull();
        assertThat(empty.stress().lastRunId()).isNull();
    }

    @Test
    @DisplayName("null 인자와 의존은 거부한다")
    void nullArguments_Throw() {
        Clock clock = Clock.fixed(FIXED, ZoneOffset.UTC);
        assertThatThrownBy(() -> new RouteContextService(
                null, xrayService, riskProfileService, planRateContextProvider,
                stressRunService, marketRegimeService)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new RouteContextService(
                clock, null, riskProfileService, planRateContextProvider,
                stressRunService, marketRegimeService)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new RouteContextService(
                clock, xrayService, null, planRateContextProvider,
                stressRunService, marketRegimeService)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new RouteContextService(
                clock, xrayService, riskProfileService, null,
                stressRunService, marketRegimeService)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new RouteContextService(
                clock, xrayService, riskProfileService, planRateContextProvider,
                null, marketRegimeService)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new RouteContextService(
                clock, xrayService, riskProfileService, planRateContextProvider,
                stressRunService, null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> service.getContext(null)).isInstanceOf(NullPointerException.class);
    }
}
