package com.divurve.domain.home;

import com.divurve.common.architecture.UseCase;
import com.divurve.common.exception.InvalidRequestException;
import com.divurve.common.exception.NotFoundException;
import com.divurve.domain.forecast.ForecastService;
import com.divurve.domain.forecast.ForecastService.EconomicEventView;
import com.divurve.domain.forecast.ForecastService.ForecastView;
import com.divurve.domain.forecast.ForecastService.HistoryPoint;
import com.divurve.domain.goal.GoalService;
import com.divurve.domain.goal.entity.Goal;
import com.divurve.domain.market.MarketRegimeService;
import com.divurve.domain.market.MarketRegimeService.MarketRegimeView;
import com.divurve.domain.settings.RiskProfileService;
import com.divurve.domain.settings.RiskProfileView;
import com.divurve.domain.user.UserRepository;
import com.divurve.domain.xray.XrayService;
import com.divurve.domain.xray.XrayService.PortfolioSnapshot;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

/**
 * 홈 요약 유스케이스 (API 명세 v2 §5.11, 요구사항 v2 §4.4 FR-HM-01~08, 이슈 #54(7.5)).
 *
 * <p>화면 v2 §11 의 6블록을 조합한다 — <b>블록 순서는 고정</b>이며(FR-HM-07, NFR-UI-01) 사용자별로
 * 재정렬하지 않는다. 이 서비스는 새로운 계산을 하지 않고, 이미 검증된 다른 UseCase(
 * {@link XrayService}·{@link RiskProfileService}·{@link ForecastService}·{@link MarketRegimeService}·
 * {@link GoalService})의 공개 메서드만 조회해 평탄화한다(CLAUDE.md §1 "계산은 engine 만 한다").
 *
 * <p><b>빈 상태는 에러가 아니다</b>. 사용자가 아직 자산·목표·진단을 채우지 않았어도 200 과 함께
 * 빈 블록을 낸다 — 블록 자체를 생략하지 않는다(명세 §5.11 {@code state}).
 */
@UseCase
public class HomeSummaryService {

    /** 블록 키·순서 (명세 §5.11 {@code blocks[]}, 고정). */
    public static final String BLOCK_TODAY = "today";
    public static final String BLOCK_PROFILE_FIT = "profile_fit";
    public static final String BLOCK_FX_STATUS = "fx_status";
    public static final String BLOCK_GOALS_ROUTE = "goals_route";
    public static final String BLOCK_ATTENTION = "attention";
    public static final String BLOCK_FORECAST = "forecast";

    /** 블록 상태 어휘 (명세 §5.11). */
    public static final String STATE_FILLED = "filled";
    public static final String STATE_EMPTY = "empty";
    public static final String STATE_NOT_MEASURED = "not_measured";

    /** {@code forecast} 블록에 쓰는 대표 통화쌍 — 요구사항 v2 §4.5 "USD·JPY·EUR Mock 전환" 중 기본값. */
    private static final String DEFAULT_PAIR_CODE = "USDKRW";

    /** {@code attention.upcoming_events} 로 좁히는 임박 기준(일) — 화면 v2 §11 "임박 일정". */
    private static final int UPCOMING_EVENT_WINDOW_DAYS = 14;

    /**
     * {@code forecast.history} 로 잘라내는 스파크라인 관측 수(영업일) — 이슈 #94.
     * {@link ForecastService} 가 이미 최근 90영업일을 들고 있으므로(내부 {@code HISTORY_POINTS}),
     * 그중 홈 화면 4~5주 추세 스파크라인에 필요한 만큼만 꼬리에서 잘라 홈 응답 크기를 줄인다.
     * {@code /forecast} 전체 {@code history} 와는 별개이며 새 계산이 아니다.
     */
    private static final int FORECAST_HISTORY_SPARKLINE_POINTS = 30;

    private final UserRepository userRepository;
    private final XrayService xrayService;
    private final RiskProfileService riskProfileService;
    private final ForecastService forecastService;
    private final MarketRegimeService marketRegimeService;
    private final GoalService goalService;

    public HomeSummaryService(
            UserRepository userRepository,
            XrayService xrayService,
            RiskProfileService riskProfileService,
            ForecastService forecastService,
            MarketRegimeService marketRegimeService,
            GoalService goalService) {
        this.userRepository = Objects.requireNonNull(userRepository, "userRepository");
        this.xrayService = Objects.requireNonNull(xrayService, "xrayService");
        this.riskProfileService = Objects.requireNonNull(riskProfileService, "riskProfileService");
        this.forecastService = Objects.requireNonNull(forecastService, "forecastService");
        this.marketRegimeService = Objects.requireNonNull(marketRegimeService, "marketRegimeService");
        this.goalService = Objects.requireNonNull(goalService, "goalService");
    }

    /**
     * 홈 요약 6블록을 조회한다.
     *
     * @param userId 사용자 ID
     * @return 홈 요약 (블록 순서 고정)
     * @throws NotFoundException 사용자를 찾을 수 없는 경우
     */
    @Transactional(readOnly = true)
    public HomeSummaryView getSummary(UUID userId) {
        userRepository.findById(userId)
                .orElseThrow(() -> new NotFoundException("사용자를 찾을 수 없습니다."));

        MarketRegimeView regime = marketRegimeService.getRegime();
        PortfolioSnapshot portfolio = xrayService.getPortfolio(userId);
        RiskProfileView riskProfile = riskProfileService.getRiskProfile(userId);
        ForecastBlockResult forecastResult = resolveForecast(userId);

        TodayView today = resolveToday(regime, portfolio);
        ProfileFitView profileFit = resolveProfileFit(riskProfile, portfolio);
        FxStatusView fxStatus = resolveFxStatus(portfolio);
        GoalsRouteView goalsRoute = resolveGoalsRoute(userId);
        AttentionView attention = resolveAttention(regime);

        List<BlockView> blocks = List.of(
                new BlockView(1, BLOCK_TODAY, STATE_FILLED),
                new BlockView(2, BLOCK_PROFILE_FIT,
                        riskProfile.riskType() == null ? STATE_NOT_MEASURED : STATE_FILLED),
                new BlockView(3, BLOCK_FX_STATUS, portfolio.fxAssetKrw() > 0 ? STATE_FILLED : STATE_EMPTY),
                new BlockView(4, BLOCK_GOALS_ROUTE, goalsRoute.state()),
                new BlockView(5, BLOCK_ATTENTION, STATE_FILLED),
                new BlockView(6, BLOCK_FORECAST, forecastResult.state()));

        return new HomeSummaryView(
                blocks, today, profileFit, fxStatus, goalsRoute, attention, forecastResult.view(),
                regime.regime(), Instant.now());
    }

    private TodayView resolveToday(MarketRegimeView regime, PortfolioSnapshot portfolio) {
        String topCurrency = portfolio.concentration().topCurrencyCode();
        String headlineCode = topCurrency != null
                ? "vol_%s_%s".formatted(regime.regime(), topCurrency.toLowerCase(Locale.ROOT))
                : "regime_%s".formatted(regime.regime());
        return new TodayView(headlineCode, regime.badge());
    }

    private ProfileFitView resolveProfileFit(RiskProfileView riskProfile, PortfolioSnapshot portfolio) {
        return new ProfileFitView(riskProfile.riskType(), portfolio.concentration().status());
    }

    private FxStatusView resolveFxStatus(PortfolioSnapshot portfolio) {
        return new FxStatusView(
                portfolio.fxRatio(),
                portfolio.concentration().topCurrencyCode(),
                portfolio.sensitivity1pct().totalKrw(),
                portfolio.dayChangeKrw(),
                portfolio.currencyToAssetKrw(),
                portfolio.exposure());
    }

    /**
     * 목표 블록. Route 기능 플래그를 제거하면서(이슈 #84) {@code route_pending} 분기도 함께
     * 없앴다 — 계산 로직이 확정돼 "아직 준비 중"이라는 상태가 더는 발생하지 않는다.
     * 목표가 없으면 {@code empty} 이며, 프론트는 새 목표 만들기를 안내한다(플래너 명세 §20).
     */
    private GoalsRouteView resolveGoalsRoute(UUID userId) {
        List<Goal> goals = goalService.listByOwner(userId);
        List<ActiveGoalView> activeGoals = goals.stream()
                .map(goal -> new ActiveGoalView(
                        goal.getId().toString(),
                        goal.getName(),
                        goal.getCurrencyCode(),
                        goal.getTargetAmount(),
                        goal.getTargetDate(),
                        goal.getStatus()))
                .toList();
        return new GoalsRouteView(activeGoals, activeGoals.isEmpty() ? STATE_EMPTY : STATE_FILLED);
    }

    private AttentionView resolveAttention(MarketRegimeView regime) {
        // 조회 창을 쿼리로 내린다(이슈 #162) — 예전에는 90일치를 받아 여기서 다시 걸렀다.
        return new AttentionView(
                regime.badge(), forecastService.getEvents(UPCOMING_EVENT_WINDOW_DAYS));
    }

    /**
     * {@code forecast} 블록 — 과거 관측이 부족해 계산이 불가능하면(신규 통화쌍 등) 에러가 아니라
     * 빈 블록으로 처리한다(명세 §5.11 "데이터가 없으면 빈 상태 전용 처리").
     */
    private ForecastBlockResult resolveForecast(UUID userId) {
        try {
            ForecastView forecast = forecastService.getForecast(
                    userId, DEFAULT_PAIR_CODE, ForecastService.DEFAULT_HORIZON_DAYS);
            ForecastSummaryView view = new ForecastSummaryView(
                    forecast.pairCode(),
                    forecast.currentRate(),
                    new IntervalView(forecast.interval80().lo(), forecast.interval80().hi()),
                    sparklineHistory(forecast.history()));
            return new ForecastBlockResult(view, STATE_FILLED);
        } catch (InvalidRequestException e) {
            // 관측 부족 등 계산 불가 사유 — 빈 블록으로 처리하고 화면 이용을 막지 않는다.
            return new ForecastBlockResult(null, STATE_EMPTY);
        }
    }

    private record ForecastBlockResult(ForecastSummaryView view, String state) {
    }

    /**
     * {@link ForecastService}가 이미 들고 있는 관측에서 홈 스파크라인용 최근
     * {@link #FORECAST_HISTORY_SPARKLINE_POINTS}영업일만 꼬리에서 자른다 — 새 조회·계산이 아니다.
     */
    private List<HistoryPoint> sparklineHistory(List<HistoryPoint> history) {
        int from = Math.max(0, history.size() - FORECAST_HISTORY_SPARKLINE_POINTS);
        return history.subList(from, history.size());
    }

    /**
     * 홈 요약 (명세 §5.11 응답 전체 — {@code meta.regime} 은 대표 국면을 함께 싣는다).
     *
     * @param blocks       블록 순서·상태 (고정 순서, FR-HM-07)
     * @param regime       대표 시장 국면 — 컨트롤러가 {@code meta.regime} 에 옮긴다
     * @param referenceTime 조회 기준 시각
     */
    public record HomeSummaryView(
            List<BlockView> blocks,
            TodayView today,
            ProfileFitView profileFit,
            FxStatusView fxStatus,
            GoalsRouteView goalsRoute,
            AttentionView attention,
            ForecastSummaryView forecast,
            String regime,
            Instant referenceTime) {
    }

    /** 블록 메타 — 순서·키·상태. */
    public record BlockView(int order, String key, String state) {
    }

    /** 오늘의 핵심 — 헤드라인 코드와 배지. */
    public record TodayView(String headlineCode, String badge) {
    }

    /** 위험성향·Fit 관계 — 대표 유형과 집중도 상태. */
    public record ProfileFitView(String grade, String concentrationStatus) {
    }

    /**
     * 외화 현황 — 비중·주력 통화·민감도·전일 대비·통화별 노출.
     *
     * @param currencyToAssetKrw 통화코드 → 원화 평가액 (평가액 내림차순). {@code GET /xray} 의
     *                           {@code exposure[].krw} 와 같은 산출물(이슈 #94) — 새로 계산하지 않는다
     * @param exposureShare      통화코드 → 외화자산 대비 비중 (0~1). {@code GET /xray} 의
     *                           {@code exposure[].share} 와 같은 산출물
     */
    public record FxStatusView(
            double fxRatio, String topCurrencyCode, long sensitivity1pctKrw, Long dayChangeKrw,
            Map<String, Long> currencyToAssetKrw, Map<String, Double> exposureShare) {
    }

    /** 목표 영역. {@code routeEnabled} 는 이슈 #84 에서 제거했다 — 항상 켜져 있다. */
    public record GoalsRouteView(List<ActiveGoalView> activeGoals, String state) {
    }

    /** 활성 목표 요약. */
    public record ActiveGoalView(
            String id, String name, String currencyCode, double targetAmount,
            LocalDate targetDate, String status) {
    }

    /** 주의 필요 — 시장 배지와 임박 일정. */
    public record AttentionView(String regimeBadge, List<EconomicEventView> upcomingEvents) {
    }

    /**
     * Forecast 요약 — 계산 불가 시 {@code null}.
     *
     * @param history 스파크라인용 최근 {@value #FORECAST_HISTORY_SPARKLINE_POINTS}영업일
     *                (시간순, {@code /forecast} 전체 {@code history} 의 부분집합)
     */
    public record ForecastSummaryView(
            String pairCode, double currentRate, IntervalView interval80, List<HistoryPoint> history) {
    }

    /** 80퍼센트 예측 구간. */
    public record IntervalView(double lo, double hi) {
    }
}
