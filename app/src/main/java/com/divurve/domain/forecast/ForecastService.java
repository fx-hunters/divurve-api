package com.divurve.domain.forecast;

import com.divurve.common.architecture.UseCase;
import com.divurve.common.exception.InvalidRequestException;
import com.divurve.domain.event.EconEventRepository;
import com.divurve.domain.event.EconEventVocabulary;
import com.divurve.domain.fx.PerUnitFxRates;
import com.divurve.domain.holding.DepositRepository;
import com.divurve.domain.holding.HoldingRepository;
import com.divurve.domain.holding.entity.Deposit;
import com.divurve.domain.holding.entity.Holding;
import com.divurve.domain.port.FxRateHistoryProvider;
import com.divurve.engine.forecast.FanChartCalculator;
import com.divurve.engine.forecast.ModelPerformanceCalculator;
import com.divurve.engine.forecast.VolatilityCalculator;
import com.divurve.engine.volatility.Regime;
import com.divurve.engine.volatility.RegimeClassifier;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.transaction.annotation.Transactional;

/**
 * 환율 예측 범위 UseCase (명세 v2 §5.7·§5.8, 요구사항 §4.5 FR-FC-01~12).
 *
 * <h2>🔒 L1 / L2 경계 — FR-FC-12</h2>
 * <ul>
 *   <li><b>L1 (계산 입력 가능)</b>: {@code baseRate} · {@code band} · {@code volatility}.
 *       이 값들만 다른 계산(Route·계획)의 입력이 될 수 있다.</li>
 *   <li><b>L2 (표시 전용)</b>: {@code modelPath} · {@code /forecast/factors}.
 *       <b>이 서비스는 두 값을 어떤 계산에도 넘기지 않는다.</b> {@code RouteContext} 계약에서도
 *       의도적으로 빠져 있다. 방향 전망이 계획 계산에 새어 들어가는 경로를 만들지 않기 위해서다.</li>
 * </ul>
 * 방향 확률 필드는 두지 않는다 — 요구사항 §2.2 가 "지금 사라·팔라 형태의 지시"를 금지한다.
 *
 * <h2>구간은 시뮬레이션이 아니라 해석적 계산이다 (calc)</h2>
 * v1 은 {@code GbmSimulator} 로 1,000 경로를 뽑아 분위수를 셌다. 같은 입력에도 시드·경로 수에 따라
 * 구간이 흔들려 {@code /forecast} 와 {@code /forecast/model-performance} 의 수치가 서로 맞지 않았다.
 * 이제 드리프트 0 로그정규의 <b>닫힌 해</b>({@link FanChartCalculator#analyticInterval})를 쓴다 —
 * 같은 입력이면 항상 같은 구간이고, {@code interval_80} 은 정확히 {@code band} 의 마지막 점이다.
 */
@UseCase
public class ForecastService {

    /** 예측 모델 버전 ({@code meta.model_version}, FR-FC-11). 드리프트 0 로그정규 구간 모델. */
    public static final String MODEL_VERSION = "fc-2026.09.1-drift0-lognormal";

    /** 기본 지평 (FR-FC-02). */
    public static final int DEFAULT_HORIZON_DAYS = 30;

    /**
     * 허용 지평 (FR-FC-02, 이슈 #121). 팬 차트 세분화 요청으로 30·90 두 값에서 확장했다.
     *
     * <p>지평은 {@link FanChartCalculator#analyticInterval} 의 {@code sqrt(horizonDays / 252)} 스케일링에만
     * 쓰이므로 값을 늘려도 30·90 의 산출값은 그대로다({@code ForecastServiceTest} 의 기존 30·90 단정문으로
     * 회귀 확인). {@code /forecast/model-performance} 는 워크포워드 폴드 24개를 채우려면
     * {@code 관측 수 >= horizonDays + 514} 가 필요한데(레포 히스토리 조회가 5년치, 약 1,290개 이상의
     * 관측을 확보하므로) 180일 지평도 폴드 부족 없이 24개를 그대로 채운다 — 별도 예외 처리를 두지 않는다.
     */
    private static final List<Integer> ALLOWED_HORIZON_DAYS = List.of(7, 14, 30, 60, 90, 180);

    /** {@link #validateHorizon} 오류 메시지에 쓸, 사람이 읽기 좋은 허용 지평 표기 ({@code "7·14·30·60·90·180"}). */
    private static final String ALLOWED_HORIZON_DAYS_DISPLAY = ALLOWED_HORIZON_DAYS.stream()
            .map(String::valueOf)
            .collect(Collectors.joining("·"));

    /** 응답 {@code history} 로 내려보낼 최근 관측 수. */
    private static final int HISTORY_POINTS = 90;

    /** {@code GET /events} 조회 창(일). 명세 §5.9 "향후 90일". */
    private static final int EVENTS_WINDOW_DAYS = 90;

    /** 5년 백분위 계산에 필요한 관측 수(영업일) + 30일 롤링 윈도. */
    private static final int REQUIRED_OBSERVATIONS = 5 * HistoryWindow.BUSINESS_DAYS_PER_YEAR + 30;

    /** 위 관측 수를 확보하기 위해 어댑터에 요청할 조회 구간(달력일). 단위 혼동은 이슈 #57 참고. */
    private static final int HISTORY_LOOKBACK_CALENDAR_DAYS =
            HistoryWindow.calendarDaysFor(REQUIRED_OBSERVATIONS);

    /** 워크포워드 검증 폴드 수 (명세 §5.8 예시와 같은 24개월). */
    private static final int VALIDATION_FOLDS = 24;

    /** 폴드 간격 — 영업일 기준 약 1개월. */
    private static final int FOLD_STEP_DAYS = 21;

    /** 검증 방법 코드 (명세 §5.8). */
    private static final String VALIDATION_METHOD = "rolling_walk_forward";

    /** 명세 §5.7 {@code labels.band}. 음영을 "변동성"이라 부르지 않는다(FR-FC-04·05). */
    public static final String LABEL_BAND = "예측 범위 / 불확실성 구간";

    /** 명세 §5.7 {@code labels.model_path}. */
    public static final String LABEL_MODEL_PATH = "모델의 참고 중심 경로";

    /** 명세 §5.7 {@code disclaimer}. */
    public static final String DISCLAIMER = "예상 구간은 보장 범위가 아니며 투자 권유가 아닙니다.";

    /** 명세 §5.8 {@code note}. 포함률은 폭과 함께 봐야 한다(FR-FC-11). */
    public static final String PERFORMANCE_NOTE =
            "구간 포함률은 구간을 넓히면 쉽게 오르므로 평균 구간 폭과 함께 봐야 합니다.";

    private final CrossRateResolver crossRateResolver;
    private final PerUnitFxRates perUnitFxRates;
    private final EconEventRepository econEventRepository;
    private final HoldingRepository holdingRepository;
    private final DepositRepository depositRepository;
    private final RegimeClassifier regimeClassifier;
    private final Clock clock;

    public ForecastService(
            CrossRateResolver crossRateResolver,
            PerUnitFxRates perUnitFxRates,
            EconEventRepository econEventRepository,
            HoldingRepository holdingRepository,
            DepositRepository depositRepository,
            RegimeClassifier regimeClassifier,
            Clock clock) {
        this.crossRateResolver = Objects.requireNonNull(crossRateResolver, "crossRateResolver");
        this.perUnitFxRates = Objects.requireNonNull(perUnitFxRates, "perUnitFxRates");
        this.econEventRepository = Objects.requireNonNull(econEventRepository, "econEventRepository");
        this.holdingRepository = Objects.requireNonNull(holdingRepository, "holdingRepository");
        this.depositRepository = Objects.requireNonNull(depositRepository, "depositRepository");
        this.regimeClassifier = Objects.requireNonNull(regimeClassifier, "regimeClassifier");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * 예측 범위·변동성·내 자산 영향 조회 ({@code GET /forecast}).
     *
     * @param userId      조회 사용자 (자산 영향 계산용)
     * @param rawPairCode {@code pair_code} 원본 값 ({@code USDKRW} 또는 {@code USD_KRW})
     * @param horizonDays 지평 (7·14·30·60·90·180 중 하나)
     * @return 예측 범위 데이터
     * @throws InvalidRequestException 통화쌍 표기나 지평이 허용 범위를 벗어난 경우
     */
    @Transactional(readOnly = true)
    public ForecastView getForecast(UUID userId, String rawPairCode, int horizonDays) {
        Objects.requireNonNull(userId, "userId");
        PairCode pair = PairCode.parse(rawPairCode);
        validateHorizon(horizonDays);

        SupportedPairHistory support = resolveSupportedPairHistory(pair);
        List<FxRateHistoryProvider.HistoryRateSnapshot> history = support.history();
        double vol30d = support.vol30d();
        double volPercentile5y = support.volPercentile5y();
        Regime regime = regimeClassifier.classify(volPercentile5y);

        double currentRate = crossRateResolver.latestRate(pair);
        // 드리프트 0 이므로 기준선(계산에 쓰이는 유일한 중앙값)은 현재 환율과 같다 — L1.
        double baseRate = currentRate;
        // 관측이 비어 있으면 위 변동성 계산에서 이미 400 이 나갔으므로 여기서는 마지막 관측이 반드시 있다.
        LocalDate baseDate = history.get(history.size() - 1).date();

        List<BandPoint> band = new ArrayList<>(horizonDays);
        for (int day = 1; day <= horizonDays; day++) {
            FanChartCalculator.PathPoint point = FanChartCalculator.analyticInterval(baseRate, vol30d, day);
            band.add(new BandPoint(
                    baseDate.plusDays(day),
                    point.p50Lo(), point.p50Hi(), point.p80Lo(), point.p80Hi()));
        }
        BandPoint last = band.get(band.size() - 1);

        // 🔒 L2 — 표시 전용. 드리프트가 0 이므로 중심 경로는 기준선과 같다. 이 값은 아래 어떤 계산에도
        //    입력되지 않으며 RouteContext 계약에도 포함되지 않는다(FR-FC-12).
        List<ModelPathPoint> modelPath = band.stream()
                .map(point -> new ModelPathPoint(point.date(), baseRate))
                .toList();

        long assetKrw = exposureKrw(userId, pair.base());
        long per1pctKrw = Math.round(assetKrw * 0.01);

        return new ForecastView(
                pair.canonical(),
                horizonDays,
                baseDate,
                currentRate,
                baseRate,
                tailHistory(history),
                band,
                modelPath,
                new IntervalView(last.p80Lo(), last.p80Hi(), (last.p80Hi() - last.p80Lo()) / baseRate),
                new VolatilityView(vol30d, volPercentile5y, regime.code()),
                new UserImpactView(per1pctKrw, assetKrw),
                new LabelsView(LABEL_BAND, LABEL_MODEL_PATH),
                new ModelInfoView(
                        List.of(0.50, 0.80),
                        "드리프트 0 기준선에 %d일 변동성을 적용한 구간입니다.".formatted(horizonDays),
                        "실제 환율은 구간을 벗어날 수 있으며 급변 시 오차가 확대됩니다."),
                uncertaintyNote(pair.canonical(), volPercentile5y, regime),
                DISCLAIMER);
    }

    /**
     * 모델 성적표 조회 ({@code GET /forecast/model-performance}, FR-FC-11).
     *
     * <p>실제 과거 관측으로 <b>롤링 워크포워드</b> 검증을 돌린다. 각 폴드의 기준값은 그 시점까지의
     * 실측값이라 미래 누출이 없다({@code leakage_guard}). 하드코딩된 목값을 쓰지 않는다.
     *
     * <p>이 서비스의 기준 모델은 드리프트 0 이라 점예측이 랜덤워크와 <b>같다</b>. 그래서
     * {@code rw_improvement} 는 0 이다 — 숨기지 않고 그대로 보여준다(명세 §5.8). 응답에는
     * 방향 적중률({@code hit_rate})을 두지 않는다 — 드리프트 0 모델은 방향을 제시하지 않아
     * 이 지표가 항상 0 으로 나와 성립하지 않는다(이슈 #90). {@code coverage_80} 이 대신 성적을 답한다.
     *
     * @param rawPairCode {@code pair_code}
     * @param horizonDays 지평 (7·14·30·60·90·180 중 하나)
     * @return 성적표
     * @throws InvalidRequestException 표기·지평 오류, 또는 검증할 관측이 모자란 경우
     */
    @Transactional(readOnly = true)
    public ModelPerformanceView getModelPerformance(String rawPairCode, int horizonDays) {
        PairCode pair = PairCode.parse(rawPairCode);
        validateHorizon(horizonDays);

        List<FxRateHistoryProvider.HistoryRateSnapshot> history =
                crossRateResolver.fetch(pair, LocalDate.now(clock), HISTORY_LOOKBACK_CALENDAR_DAYS);
        List<Double> dailyReturns = toDailyReturns(history);

        List<Double> baseRates = new ArrayList<>();
        List<Double> forecastRates = new ArrayList<>();
        List<Double> actualRates = new ArrayList<>();
        List<Double> lowerBounds = new ArrayList<>();
        List<Double> upperBounds = new ArrayList<>();

        // 최신 폴드부터 과거로 한 달씩 물러나며 평가한다. 각 폴드는 t 시점의 정보만 쓴다.
        for (int fold = 0; fold < VALIDATION_FOLDS; fold++) {
            int actualIndex = history.size() - 1 - fold * FOLD_STEP_DAYS;
            int baseIndex = actualIndex - horizonDays;
            if (baseIndex < VolatilityCalculator.REALIZED_30D_WINDOW) {
                break;
            }
            double base = history.get(baseIndex).rate();
            double actual = history.get(actualIndex).rate();
            // t 시점까지의 수익률만 쓴다 — 미래 누출 방지(NFR-DT-01).
            double volAtBase = VolatilityCalculator.calculateRealized30d(
                    dailyReturns.subList(0, baseIndex));
            FanChartCalculator.PathPoint interval =
                    FanChartCalculator.analyticInterval(base, volAtBase, horizonDays);

            baseRates.add(base);
            // 드리프트 0 기준선의 점예측은 기준값 그 자체다.
            forecastRates.add(base);
            actualRates.add(actual);
            lowerBounds.add(interval.p80Lo());
            upperBounds.add(interval.p80Hi());
        }

        if (baseRates.isEmpty()) {
            throw new InvalidRequestException(
                    "성적표를 계산할 과거 관측이 부족합니다 (필요 지평 %d일).".formatted(horizonDays), "horizon_days");
        }

        double modelMae = ModelPerformanceCalculator.calculateMaeRatio(forecastRates, actualRates);
        double coverage80 = ModelPerformanceCalculator.calculateCoverage80(lowerBounds, upperBounds, actualRates);
        double avgWidth = ModelPerformanceCalculator.calculateAvgWidthRatio(lowerBounds, upperBounds, baseRates);
        ModelPerformanceCalculator.RandomWalkMetrics randomWalk =
                ModelPerformanceCalculator.calculateRandomWalkBenchmark(baseRates, actualRates);
        double improvement = ModelPerformanceCalculator.calculateImprovement(modelMae, randomWalk.mae());

        LocalDate evaluatedAt = history.get(history.size() - 1).date();

        return new ModelPerformanceView(
                pair.canonical(),
                horizonDays,
                new ModelMetricsView(modelMae, coverage80, avgWidth),
                new RandomWalkMetricsView(randomWalk.mae()),
                improvement,
                new ValidationView(VALIDATION_METHOD, baseRates.size(), true),
                PERFORMANCE_NOTE,
                evaluatedAt.atStartOfDay().toInstant(ZoneOffset.UTC));
    }

    /**
     * 전망 동인 조회 ({@code GET /forecast/factors}).
     *
     * <p>🔒 <b>L2 — 표시 전용이다.</b> 반환값은 어떤 계산에도 입력되지 않으며 RouteContext 에도
     * 실리지 않는다(FR-FC-12). 값의 출처가 확정될 때까지 빈 목록이다 — 없는 근거를 만들지 않는다(FR-CM-10).
     *
     * <p>{@code pair_code} 검증은 형제 엔드포인트({@link #getForecast})와 같은 경로
     * ({@link #resolveSupportedPairHistory})를 그대로 쓴다 — 지원하지 않는 통화쌍은 과거 관측이
     * 없어 여기서도 똑같이 {@code field=pair_code} 400 을 낸다(이슈 #89). 동인 값 자체는 계산에
     * 쓰이지 않지만(FR-FC-12), "이 통화쌍을 다룰 수 있는가"라는 검증까지 생략할 이유는 아니다.
     *
     * @param rawPairCode {@code pair_code}
     * @return 동인 목록 (현재는 비어 있다)
     * @throws InvalidRequestException 통화쌍 표기 오류이거나 지원하지 않는 통화쌍인 경우
     */
    @Transactional(readOnly = true)
    public FactorsView getFactors(String rawPairCode) {
        PairCode pair = PairCode.parse(rawPairCode);
        resolveSupportedPairHistory(pair);
        return new FactorsView(pair.canonical());
    }

    /**
     * 경제 일정 조회 ({@code GET /events}).
     *
     * @return 향후 {@value #EVENTS_WINDOW_DAYS}일 이벤트 (날짜 오름차순)
     */
    @Transactional(readOnly = true)
    public List<EconomicEventView> getEvents() {
        return getEvents(EVENTS_WINDOW_DAYS);
    }

    /**
     * 조회 창을 좁힌 경제 일정 조회 (이슈 #162).
     *
     * <p>홈의 <b>주의 필요</b> 블록은 임박한 일정만 쓰므로 90일을 다 읽을 이유가 없다. 예전에는
     * 90일치를 받아 호출부가 다시 걸렀다 — 이제 구간을 쿼리로 내린다.
     *
     * <p>{@code withinDays} 는 이 클래스와 {@code HomeSummaryService} 의 상수로만 들어와
     * 별도 검증을 두지 않는다. 음수가 오면 {@code from > to} 라 빈 목록이 나온다.
     *
     * @param withinDays 오늘부터 며칠 뒤까지 (포함)
     * @return 이벤트 목록 (날짜 오름차순)
     */
    @Transactional(readOnly = true)
    public List<EconomicEventView> getEvents(int withinDays) {
        LocalDate today = LocalDate.now(clock);
        return econEventRepository
                .findByEventDateBetweenOrderByEventDateAsc(today, today.plusDays(withinDays))
                .stream()
                .map(event -> new EconomicEventView(
                        event.getEventDate(),
                        event.getTitle(),
                        EconEventVocabulary.toCurrencyCode(event.getRegion()),
                        EconEventVocabulary.toImportance(event.getImpact())))
                .toList();
    }

    // ── 내부 계산 ────────────────────────────────────────────────

    private void validateHorizon(int horizonDays) {
        if (!ALLOWED_HORIZON_DAYS.contains(horizonDays)) {
            throw new InvalidRequestException(
                    "horizon_days 는 %s 중 하나여야 합니다 (입력 %d)."
                            .formatted(ALLOWED_HORIZON_DAYS_DISPLAY, horizonDays),
                    "horizon_days");
        }
    }

    /**
     * 통화쌍의 과거 시계열을 가져오고, 같은 자리에서 변동성 계산 가능 여부로 지원 여부를 검증한다
     * (이슈 #89) — {@code /forecast}·{@code /forecast/factors} 가 이 메서드 하나를 공유해
     * 지원하지 않는 통화쌍(관측 없음)을 같은 {@code field=pair_code} 400 으로 거른다.
     * 새 검증 로직을 따로 두지 않는다 — "지원 통화쌍" 목록을 이중으로 관리하면 둘이 어긋난다.
     *
     * @throws InvalidRequestException 30일 실현변동성 또는 5년 백분위를 계산할 관측이 없는 경우
     */
    private SupportedPairHistory resolveSupportedPairHistory(PairCode pair) {
        List<FxRateHistoryProvider.HistoryRateSnapshot> history =
                crossRateResolver.fetch(pair, LocalDate.now(clock), HISTORY_LOOKBACK_CALENDAR_DAYS);
        List<Double> dailyReturns = toDailyReturns(history);

        double vol30d = realized30d(dailyReturns);
        double volPercentile5y = percentile5y(dailyReturns);
        return new SupportedPairHistory(history, vol30d, volPercentile5y);
    }

    private static double realized30d(List<Double> dailyReturns) {
        try {
            return VolatilityCalculator.calculateRealized30d(dailyReturns);
        } catch (IllegalArgumentException e) {
            throw new InvalidRequestException("변동성을 계산할 과거 관측이 부족합니다.", "pair_code");
        }
    }

    private static double percentile5y(List<Double> dailyReturns) {
        try {
            return VolatilityCalculator.calculatePercentile5y(dailyReturns);
        } catch (IllegalArgumentException e) {
            throw new InvalidRequestException("5년 변동성 백분위를 계산할 과거 관측이 부족합니다.", "pair_code");
        }
    }

    private static String uncertaintyNote(String pairCode, double volPercentile5y, Regime regime) {
        if (regime == Regime.CALM || regime == Regime.NORMAL) {
            return "%s 변동성은 5년 분포의 평시 범위여서 예측 범위가 평소 수준입니다."
                    .formatted(pairCode);
        }
        return "%s 현재 변동성이 5년 상위 %.0f%% 구간이어서 예측 범위가 평시보다 넓습니다."
                .formatted(pairCode, (1.0 - volPercentile5y) * 100);
    }

    /** {@link CrossRateResolver} 가 null 을 돌려주지 않으므로 여기서는 길이만 본다. */
    private static List<Double> toDailyReturns(List<FxRateHistoryProvider.HistoryRateSnapshot> history) {
        if (history.size() < 2) {
            return List.of();
        }
        List<Double> returns = new ArrayList<>(history.size() - 1);
        for (int i = 1; i < history.size(); i++) {
            returns.add(Math.log(history.get(i).rate() / history.get(i - 1).rate()));
        }
        return returns;
    }

    private static List<HistoryPoint> tailHistory(List<FxRateHistoryProvider.HistoryRateSnapshot> history) {
        int from = Math.max(0, history.size() - HISTORY_POINTS);
        return history.subList(from, history.size()).stream()
                .map(snapshot -> new HistoryPoint(snapshot.date(), snapshot.rate()))
                .toList();
    }

    /**
     * 해당 통화의 사용자 노출액(원화 환산). {@code user_impact.asset_krw} 의 근거다.
     *
     * <p>명세 §4 fixture 검산: USD 노출 15,790,000 → {@code per_1pct_krw} 157,900.
     * 고시 단위가 100단위인 통화(JPY)는 {@link QuoteUnitNormalizer} 로 1단위 환율로 정규화한다.
     */
    private long exposureKrw(UUID userId, String currencyCode) {
        List<Holding> holdings = holdingRepository.findByOwner_Id(userId).stream()
                .filter(holding -> currencyCode.equals(holding.getCurrencyCode()))
                .toList();
        List<Deposit> deposits = depositRepository.findByOwner_Id(userId).stream()
                .filter(deposit -> currencyCode.equals(deposit.getCurrencyCode()))
                .toList();
        if (holdings.isEmpty() && deposits.isEmpty()) {
            return 0L;
        }

        // 조회는 PerUnitFxRates 한 곳을 거친다 — 예전에는 이 두 줄이 StressRunService·
        // FxAssetValuator 에도 복사돼 있었다(이슈 #57). 이 통화의 환율은 기준선(latestRate)에서
        // 이미 확보된 것이므로 여기서 다시 실패할 여지는 없다.
        BigDecimal rate = perUnitFxRates.require(currencyCode);

        long total = 0L;
        for (Holding holding : holdings) {
            total += BigDecimal.valueOf(holding.getQuantity() * holding.getAvgPrice())
                    .multiply(rate)
                    .setScale(0, RoundingMode.HALF_UP)
                    .longValue();
        }
        for (Deposit deposit : deposits) {
            total += deposit.getAmount()
                    .multiply(rate)
                    .setScale(0, RoundingMode.HALF_UP)
                    .longValue();
        }
        return total;
    }

    /**
     * {@link #resolveSupportedPairHistory} 결과 — 시계열과, 그로부터 이미 계산해 둔 변동성 지표.
     * {@code getForecast} 가 바로 이어서 쓰는 값이라 두 번 계산하지 않는다.
     */
    private record SupportedPairHistory(
            List<FxRateHistoryProvider.HistoryRateSnapshot> history,
            double vol30d,
            double volPercentile5y) {
    }

    // ── 도메인 뷰 ────────────────────────────────────────────────

    /**
     * 예측 범위 응답 (명세 §5.7).
     *
     * @param pairCode        통화쌍 (명세 표기)
     * @param horizonDays     지평
     * @param baseDate        계산 기준일
     * @param currentRate     현재 환율
     * @param baseRate        기준선 (L1 — 계산에 쓰이는 유일한 중앙값)
     * @param history         최근 관측
     * @param band            예측 범위 (L1)
     * @param modelPath       모델 참고 중심 경로 (L2 — 표시 전용)
     * @param interval80      지평 끝 80퍼센트 구간
     * @param volatility      변동성 지표 (L1)
     * @param userImpact      1퍼센트 변동 시 내 자산 영향
     * @param labels          음영·경로의 표시 라벨
     * @param modelInfo       구간 수준·가정·한계
     * @param uncertaintyNote 불확실성 안내
     * @param disclaimer      고지
     */
    public record ForecastView(
            String pairCode,
            int horizonDays,
            LocalDate baseDate,
            double currentRate,
            double baseRate,
            List<HistoryPoint> history,
            List<BandPoint> band,
            List<ModelPathPoint> modelPath,
            IntervalView interval80,
            VolatilityView volatility,
            UserImpactView userImpact,
            LabelsView labels,
            ModelInfoView modelInfo,
            String uncertaintyNote,
            String disclaimer
    ) {
    }

    /** 과거 관측 한 점. */
    public record HistoryPoint(LocalDate date, double rate) {
    }

    /** 예측 범위 한 점 (50퍼센트·80퍼센트 경계). */
    public record BandPoint(LocalDate date, double p50Lo, double p50Hi, double p80Lo, double p80Hi) {
    }

    /** 모델 참고 중심 경로 한 점 (L2 — 표시 전용). */
    public record ModelPathPoint(LocalDate date, double rate) {
    }

    /** 지평 끝 80퍼센트 구간. */
    public record IntervalView(double lo, double hi, double widthPct) {
    }

    /** 변동성 지표. {@code regime} 은 {@code calm/normal/elevated/stress} 4종. */
    public record VolatilityView(double vol30d, double volPercentile5y, String regime) {
    }

    /** 환율 1퍼센트 변동 시 자산 영향. */
    public record UserImpactView(long per1pctKrw, long assetKrw) {
    }

    /** 표시 라벨. 음영을 "변동성"이라 부르지 않는다(FR-FC-04·05). */
    public record LabelsView(String band, String modelPath) {
    }

    /** 모델 정보 — 구간 수준·가정·한계. */
    public record ModelInfoView(List<Double> intervalLevels, String assumptions, String limitations) {
    }

    /** 모델 성적표 (명세 §5.8). */
    public record ModelPerformanceView(
            String pairCode,
            int horizonDays,
            ModelMetricsView model,
            RandomWalkMetricsView randomWalk,
            double rwImprovement,
            ValidationView validation,
            String note,
            java.time.Instant evaluatedAt
    ) {
    }

    /** 모델 지표. {@code coverage80} 은 반드시 {@code avgWidth} 와 함께 노출한다. */
    public record ModelMetricsView(double mae, double coverage80, double avgWidth) {
    }

    /** 랜덤워크 벤치마크 지표. */
    public record RandomWalkMetricsView(double mae) {
    }

    /** 검증 방법. */
    public record ValidationView(String method, int folds, boolean leakageGuard) {
    }

    /**
     * 전망 동인 (L2 — 표시 전용).
     *
     * <p>동인 값을 담는 필드가 없다. 출처가 확정되지 않아 서버가 내려보낼 동인이 <b>하나도 없기</b>
     * 때문이다 — 빈 컨테이너를 미리 만들어 두면 "값이 곧 채워진다"는 잘못된 신호를 준다(FR-CM-10).
     * 응답의 {@code factors} 는 API 계약 유지를 위해 항상 빈 배열이다.
     */
    public record FactorsView(String pairCode) {
    }

    /** 경제 이벤트. */
    public record EconomicEventView(LocalDate date, String title, String currencyCode, String importance) {
    }
}
