package com.divurve.domain.home;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.divurve.api.controller.HomeController;
import com.divurve.api.controller.XrayController;
import com.divurve.api.dto.home.HomeSummaryResponse;
import com.divurve.api.dto.xray.XrayResponse;
import com.divurve.common.exception.InvalidRequestException;
import com.divurve.common.exception.NotFoundException;
import com.divurve.common.response.ApiResponse;
import com.divurve.domain.forecast.ForecastService.ForecastView;
import com.divurve.domain.forecast.ForecastService.HistoryPoint;
import com.divurve.domain.forecast.ForecastService.IntervalView;
import com.divurve.domain.forecast.ForecastService.LabelsView;
import com.divurve.domain.forecast.ForecastService.ModelInfoView;
import com.divurve.domain.forecast.ForecastService.UserImpactView;
import com.divurve.domain.forecast.ForecastService.VolatilityView;
import com.divurve.domain.forecast.ForecastService;
import com.divurve.domain.goal.GoalService;
import com.divurve.domain.goal.entity.Goal;
import com.divurve.domain.market.MarketRegimeService.AnomalyView;
import com.divurve.domain.market.MarketRegimeService.GuidanceView;
import com.divurve.domain.market.MarketRegimeService.MarketRegimeView;
import com.divurve.domain.market.MarketRegimeService;
import com.divurve.domain.settings.RiskProfileService;
import com.divurve.domain.settings.RiskProfileView;
import com.divurve.domain.user.UserRepository;
import com.divurve.domain.user.entity.User;
import com.divurve.domain.xray.XrayService.ConcentrationView;
import com.divurve.domain.xray.XrayService.PortfolioSnapshot;
import com.divurve.domain.xray.XrayService.SensitivityView;
import com.divurve.domain.xray.XrayService;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * {@link HomeSummaryService} 6블록 조합 테스트 (API 명세 v2 §5.11, 이슈 #54(7.5)).
 * 다른 UseCase 의 공개 메서드만 조회해 평탄화하는지, 빈 상태가 에러가 아니라 state 로 표현되는지 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class HomeSummaryServiceTest {

    @Mock
    private UserRepository userRepository;
    @Mock
    private XrayService xrayService;
    @Mock
    private RiskProfileService riskProfileService;
    @Mock
    private ForecastService forecastService;
    @Mock
    private MarketRegimeService marketRegimeService;
    @Mock
    private GoalService goalService;

    private static final LocalDate TODAY = LocalDate.of(2026, 9, 7);
    private static final Clock CLOCK =
            Clock.fixed(TODAY.atStartOfDay(ZoneId.of("Asia/Seoul")).toInstant(), ZoneId.of("Asia/Seoul"));

    private final UUID userId = UUID.randomUUID();
    private HomeSummaryService service;

    @BeforeEach
    void setUp() {
        service = new HomeSummaryService(
                userRepository, xrayService, riskProfileService, forecastService,
                marketRegimeService, goalService, CLOCK);
    }

    private void stubUserExists() {
        User user = User.create("test@example.com", "테스트사용자", null);
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
    }

    private MarketRegimeView regimeView(String regime, String badge) {
        return new MarketRegimeView(
                badge, badge, regime, Map.of(), List.of(),
                new GuidanceView(true, false, false),
                new AnomalyView(false, "note"));
    }

    private PortfolioSnapshot portfolioWithFx() {
        return new PortfolioSnapshot(
                68_400_000L, 43_680_000L, 24_720_000L, 0.361,
                Map.of("USD", 24_720_000L), Map.of("USD", 1.0),
                new ConcentrationView("USD", 0.639, 0.60, "risk_profile.balanced", "above_threshold", 0.039),
                new SensitivityView(247_200L, Map.of("USD", 247_200L)),
                84_000L, false);
    }

    private PortfolioSnapshot portfolioWithoutFx() {
        return new PortfolioSnapshot(
                0L, 0L, 0L, 0.0, Map.of(), Map.of(),
                new ConcentrationView(null, null, null, null, "unknown", null),
                new SensitivityView(0L, Map.of()),
                null, false);
    }

    private RiskProfileView riskProfileDiagnosed() {
        return new RiskProfileView(
                RiskProfileService.STATUS_SIMPLE_DONE, "balanced", "균형형", 4, TODAY, 0.60,
                new RiskProfileView.Simple(Map.of(), List.of(), null),
                new RiskProfileView.Detail(false, Map.of(), "q5", null),
                RiskProfileService.LIMITATION_NOTE);
    }

    private RiskProfileView riskProfileNotMeasured() {
        return new RiskProfileView(
                RiskProfileService.STATUS_NOT_MEASURED, null, null, null, null, null,
                new RiskProfileView.Simple(Map.of(), List.of(), null),
                new RiskProfileView.Detail(false, Map.of(), "q1", null),
                RiskProfileService.LIMITATION_NOTE);
    }

    private ForecastView forecastView() {
        return forecastView(List.of());
    }

    private ForecastView forecastView(List<HistoryPoint> history) {
        return new ForecastView(
                "USDKRW", 30, TODAY, 1382.40, 1382.40,
                history, List.of(), List.of(),
                new IntervalView(1346.0, 1431.0, 0.06),
                new VolatilityView(0.061, 0.72, "elevated"),
                new UserImpactView(157_900L, 15_790_000L),
                new LabelsView("band", "path"),
                new ModelInfoView(List.of(0.5, 0.8), "assumption", "limitation"),
                "note", "disclaimer");
    }

    @Test
    void getSummary_사용자가_없으면_NotFoundException() {
        when(userRepository.findById(userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getSummary(userId)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void getSummary_블록_순서와_키가_고정이다() {
        stubUserExists();
        when(marketRegimeService.getRegime()).thenReturn(regimeView("elevated", "caution"));
        when(xrayService.getPortfolio(userId)).thenReturn(portfolioWithFx());
        when(riskProfileService.getRiskProfile(userId)).thenReturn(riskProfileDiagnosed());
        when(forecastService.getForecast(userId, "USDKRW", ForecastService.DEFAULT_HORIZON_DAYS))
                .thenReturn(forecastView());
        when(forecastService.getEvents()).thenReturn(List.of());

        HomeSummaryService.HomeSummaryView view = service.getSummary(userId);

        assertThat(view.blocks()).extracting(HomeSummaryService.BlockView::key).containsExactly(
                "today", "profile_fit", "fx_status", "goals_route", "attention", "forecast");
        assertThat(view.blocks()).extracting(HomeSummaryService.BlockView::order)
                .containsExactly(1, 2, 3, 4, 5, 6);
    }

    @Test
    void getSummary_외화자산이_있으면_fx_status가_filled이고_수치를_담는다() {
        stubUserExists();
        when(marketRegimeService.getRegime()).thenReturn(regimeView("normal", "normal"));
        when(xrayService.getPortfolio(userId)).thenReturn(portfolioWithFx());
        when(riskProfileService.getRiskProfile(userId)).thenReturn(riskProfileDiagnosed());
        when(forecastService.getForecast(userId, "USDKRW", ForecastService.DEFAULT_HORIZON_DAYS))
                .thenReturn(forecastView());
        when(forecastService.getEvents()).thenReturn(List.of());

        HomeSummaryService.HomeSummaryView view = service.getSummary(userId);

        assertThat(view.blocks().get(2).state()).isEqualTo("filled");
        assertThat(view.fxStatus().fxRatio()).isEqualTo(0.361);
        assertThat(view.fxStatus().topCurrencyCode()).isEqualTo("USD");
        assertThat(view.fxStatus().sensitivity1pctKrw()).isEqualTo(247_200L);
        assertThat(view.fxStatus().dayChangeKrw()).isEqualTo(84_000L);
        // 이슈 #94 — 통화별 노출은 XrayService.PortfolioSnapshot 이 이미 들고 있는 값을 그대로 옮긴다.
        assertThat(view.fxStatus().currencyToAssetKrw()).isEqualTo(Map.of("USD", 24_720_000L));
        assertThat(view.fxStatus().exposureShare()).isEqualTo(Map.of("USD", 1.0));
        assertThat(view.profileFit().grade()).isEqualTo("balanced");
        assertThat(view.profileFit().concentrationStatus()).isEqualTo("above_threshold");
    }

    @Test
    void getSummary_외화자산이_없으면_fx_status가_empty다() {
        stubUserExists();
        when(marketRegimeService.getRegime()).thenReturn(regimeView("normal", "normal"));
        when(xrayService.getPortfolio(userId)).thenReturn(portfolioWithoutFx());
        when(riskProfileService.getRiskProfile(userId)).thenReturn(riskProfileNotMeasured());
        when(forecastService.getForecast(userId, "USDKRW", ForecastService.DEFAULT_HORIZON_DAYS))
                .thenReturn(forecastView());
        when(forecastService.getEvents()).thenReturn(List.of());

        HomeSummaryService.HomeSummaryView view = service.getSummary(userId);

        assertThat(view.blocks().get(2).state()).isEqualTo("empty");
    }

    @Test
    void getSummary_미진단이면_profile_fit이_not_measured다() {
        stubUserExists();
        when(marketRegimeService.getRegime()).thenReturn(regimeView("normal", "normal"));
        when(xrayService.getPortfolio(userId)).thenReturn(portfolioWithoutFx());
        when(riskProfileService.getRiskProfile(userId)).thenReturn(riskProfileNotMeasured());
        when(forecastService.getForecast(userId, "USDKRW", ForecastService.DEFAULT_HORIZON_DAYS))
                .thenReturn(forecastView());
        when(forecastService.getEvents()).thenReturn(List.of());

        HomeSummaryService.HomeSummaryView view = service.getSummary(userId);

        assertThat(view.blocks().get(1).state()).isEqualTo("not_measured");
        assertThat(view.profileFit().grade()).isNull();
    }

    @Test
    void getSummary_forecast_계산불가시_empty_블록으로_처리하고_에러를_내지_않는다() {
        stubUserExists();
        when(marketRegimeService.getRegime()).thenReturn(regimeView("normal", "normal"));
        when(xrayService.getPortfolio(userId)).thenReturn(portfolioWithoutFx());
        when(riskProfileService.getRiskProfile(userId)).thenReturn(riskProfileNotMeasured());
        when(forecastService.getForecast(userId, "USDKRW", ForecastService.DEFAULT_HORIZON_DAYS))
                .thenThrow(new InvalidRequestException("변동성을 계산할 과거 관측이 부족합니다."));
        when(forecastService.getEvents()).thenReturn(List.of());

        HomeSummaryService.HomeSummaryView view = service.getSummary(userId);

        assertThat(view.blocks().get(5).state()).isEqualTo("empty");
        assertThat(view.forecast()).isNull();
    }

    @Test
    void getSummary_goals_route는_목표_목록을_조회한다() {
        stubUserExists();
        when(marketRegimeService.getRegime()).thenReturn(regimeView("normal", "normal"));
        when(xrayService.getPortfolio(userId)).thenReturn(portfolioWithoutFx());
        when(riskProfileService.getRiskProfile(userId)).thenReturn(riskProfileNotMeasured());
        when(forecastService.getForecast(userId, "USDKRW", ForecastService.DEFAULT_HORIZON_DAYS))
                .thenReturn(forecastView());
        when(forecastService.getEvents()).thenReturn(List.of());
        Goal goal = Goal.builder(User.create("a@b.com", "u", null), "여행자금", "wealth", "travel", "USD")
                .targetAmount(1000.0)
                .build();
        goal.setIdForTest(UUID.randomUUID());
        when(goalService.listByOwner(userId)).thenReturn(List.of(goal));

        HomeSummaryService.HomeSummaryView view = service.getSummary(userId);

        assertThat(view.blocks().get(3).state()).isEqualTo("filled");
        assertThat(view.goalsRoute().activeGoals()).hasSize(1);
        assertThat(view.goalsRoute().activeGoals().get(0).name()).isEqualTo("여행자금");
    }

    @Test
    void getSummary_목표가_없으면_goals_route는_empty다() {
        stubUserExists();
        when(marketRegimeService.getRegime()).thenReturn(regimeView("normal", "normal"));
        when(xrayService.getPortfolio(userId)).thenReturn(portfolioWithoutFx());
        when(riskProfileService.getRiskProfile(userId)).thenReturn(riskProfileNotMeasured());
        when(forecastService.getForecast(userId, "USDKRW", ForecastService.DEFAULT_HORIZON_DAYS))
                .thenReturn(forecastView());
        when(forecastService.getEvents()).thenReturn(List.of());
        when(goalService.listByOwner(userId)).thenReturn(List.of());

        HomeSummaryService.HomeSummaryView view = service.getSummary(userId);

        assertThat(view.blocks().get(3).state()).isEqualTo("empty");
        assertThat(view.goalsRoute().activeGoals()).isEmpty();
    }

    @Test
    void getSummary_attention은_시장_배지와_임박_일정을_담는다() {
        stubUserExists();
        when(marketRegimeService.getRegime()).thenReturn(regimeView("stress", "turbulent"));
        when(xrayService.getPortfolio(userId)).thenReturn(portfolioWithoutFx());
        when(riskProfileService.getRiskProfile(userId)).thenReturn(riskProfileNotMeasured());
        when(forecastService.getForecast(userId, "USDKRW", ForecastService.DEFAULT_HORIZON_DAYS))
                .thenReturn(forecastView());
        when(forecastService.getEvents()).thenReturn(List.of(
                new ForecastService.EconomicEventView(TODAY.plusDays(3), "FOMC", "USD", "high"),
                new ForecastService.EconomicEventView(TODAY.plusDays(60), "먼미래", "USD", "low")));

        HomeSummaryService.HomeSummaryView view = service.getSummary(userId);

        assertThat(view.attention().regimeBadge()).isEqualTo("turbulent");
        assertThat(view.attention().upcomingEvents()).extracting(
                ForecastService.EconomicEventView::title).containsExactly("FOMC");
        assertThat(view.regime()).isEqualTo("stress");
    }

    /**
     * 이슈 #94 필수 테스트 1 — {@code fx_status.exposure} 는 {@code GET /xray} 의 {@code exposure} 와
     * 완전히 같은 값이어야 한다. 두 컨트롤러에 <b>같은</b> {@link PortfolioSnapshot} 을 흘려보내
     * 실제로 같은 값이 나오는지 검증한다(매핑 로직을 베껴 적어 통과시키는 것을 막는다).
     */
    @Test
    void getSummary_fx_status_exposure는_GET_xray의_exposure와_같다() {
        stubUserExists();
        when(marketRegimeService.getRegime()).thenReturn(regimeView("normal", "normal"));
        Map<String, Long> assets = new LinkedHashMap<>();
        assets.put("USD", 15_790_000L);
        assets.put("JPY", 5_470_000L);
        Map<String, Double> exposureShare = new LinkedHashMap<>();
        exposureShare.put("USD", 0.6388);
        // JPY 는 비중이 빠져 있다 — 컨트롤러가 0.0 으로 채우는지도 함께 검증된다.
        PortfolioSnapshot snapshot = new PortfolioSnapshot(
                68_400_000L, 43_680_000L, 24_720_000L, 0.3614, assets, exposureShare,
                new ConcentrationView("USD", 0.6388, 0.60, "risk_profile.balanced", "above_threshold", 0.0388),
                new SensitivityView(247_200L, Map.of("USD", 157_900L, "JPY", 54_700L)),
                null, true);
        when(xrayService.getPortfolio(userId)).thenReturn(snapshot);
        when(riskProfileService.getRiskProfile(userId)).thenReturn(riskProfileNotMeasured());
        when(forecastService.getForecast(userId, "USDKRW", ForecastService.DEFAULT_HORIZON_DAYS))
                .thenReturn(forecastView());
        when(forecastService.getEvents()).thenReturn(List.of());
        when(goalService.listByOwner(userId)).thenReturn(List.of());

        ApiResponse<HomeSummaryResponse> homeResponse = new HomeController(service).getSummary(userId);
        ApiResponse<XrayResponse> xrayResponse =
                new XrayController(xrayService, marketRegimeService).getXray(userId);

        assertThat(homeResponse.data().fxStatus().exposure())
                .containsExactlyElementsOf(xrayResponse.data().exposure());
    }

    /** 이슈 #94 필수 테스트 2 — 외화 자산이 없으면 빈 배열이다(FR-CM-09). */
    @Test
    void getSummary_외화자산이_없으면_fx_status_exposure는_빈_배열이다() {
        stubUserExists();
        when(marketRegimeService.getRegime()).thenReturn(regimeView("normal", "normal"));
        when(xrayService.getPortfolio(userId)).thenReturn(portfolioWithoutFx());
        when(riskProfileService.getRiskProfile(userId)).thenReturn(riskProfileNotMeasured());
        when(forecastService.getForecast(userId, "USDKRW", ForecastService.DEFAULT_HORIZON_DAYS))
                .thenReturn(forecastView());
        when(forecastService.getEvents()).thenReturn(List.of());

        ApiResponse<HomeSummaryResponse> response = new HomeController(service).getSummary(userId);

        assertThat(response.data().fxStatus().exposure()).isEmpty();
    }

    /** 이슈 #94 — forecast.history 는 전체 관측 중 최근 30영업일만 꼬리에서 잘라 담는다. */
    @Test
    void getSummary_forecast_history는_최근_30영업일만_담는다() {
        stubUserExists();
        when(marketRegimeService.getRegime()).thenReturn(regimeView("normal", "normal"));
        when(xrayService.getPortfolio(userId)).thenReturn(portfolioWithoutFx());
        when(riskProfileService.getRiskProfile(userId)).thenReturn(riskProfileNotMeasured());
        List<HistoryPoint> fullHistory = IntStream.range(0, 40)
                .mapToObj(i -> new HistoryPoint(TODAY.minusDays(40 - i), 1300.0 + i))
                .toList();
        when(forecastService.getForecast(userId, "USDKRW", ForecastService.DEFAULT_HORIZON_DAYS))
                .thenReturn(forecastView(fullHistory));
        when(forecastService.getEvents()).thenReturn(List.of());

        HomeSummaryService.HomeSummaryView view = service.getSummary(userId);

        assertThat(view.forecast().history()).hasSize(30)
                .isEqualTo(fullHistory.subList(10, 40));
    }

    /** 관측이 30개보다 적으면 있는 만큼만 담고 잘라내지 않는다. */
    @Test
    void getSummary_forecast_history가_30개_미만이면_전부_담는다() {
        stubUserExists();
        when(marketRegimeService.getRegime()).thenReturn(regimeView("normal", "normal"));
        when(xrayService.getPortfolio(userId)).thenReturn(portfolioWithoutFx());
        when(riskProfileService.getRiskProfile(userId)).thenReturn(riskProfileNotMeasured());
        List<HistoryPoint> shortHistory = List.of(
                new HistoryPoint(TODAY.minusDays(2), 1380.0),
                new HistoryPoint(TODAY.minusDays(1), 1381.0));
        when(forecastService.getForecast(userId, "USDKRW", ForecastService.DEFAULT_HORIZON_DAYS))
                .thenReturn(forecastView(shortHistory));
        when(forecastService.getEvents()).thenReturn(List.of());

        HomeSummaryService.HomeSummaryView view = service.getSummary(userId);

        assertThat(view.forecast().history()).isEqualTo(shortHistory);
    }
}
