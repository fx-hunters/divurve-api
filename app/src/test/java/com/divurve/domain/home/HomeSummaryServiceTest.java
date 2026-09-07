package com.divurve.domain.home;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.divurve.common.exception.InvalidRequestException;
import com.divurve.common.exception.NotFoundException;
import com.divurve.domain.forecast.ForecastService.ForecastView;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
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
        return new ForecastView(
                "USDKRW", 30, TODAY, 1382.40, 1382.40,
                List.of(), List.of(), List.of(),
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
}
