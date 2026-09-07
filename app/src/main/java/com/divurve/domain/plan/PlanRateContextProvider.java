package com.divurve.domain.plan;

import com.divurve.common.architecture.UseCase;
import com.divurve.common.exception.InvalidRequestException;
import com.divurve.domain.forecast.ForecastService;
import com.divurve.domain.fx.PerUnitFxRates;
import com.divurve.domain.master.BankFxTermsMaster;
import com.divurve.domain.master.MasterDataService;
import com.divurve.domain.settings.BankSpreadTable;
import com.divurve.engine.planner.PlannerPolicy;
import com.divurve.engine.planner.RateRange;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;

/**
 * 계획 계산의 환율·비용 전제를 모은다 (플래너 명세 §7·§8·§20).
 *
 * <p>세 갈래로 나뉜다.
 * <ul>
 *   <li><b>환율이 없으면 계산하지 않는다</b> — 명세 §20 은 "신규 계획 계산 중단 및 데이터 갱신
 *       안내"를 요구한다. 값을 지어내면 사용자가 그 수치를 믿고 환전한다.</li>
 *   <li><b>Forecast 가 없으면 범위 없이 계산한다</b> — 같은 §20 이 "현재 환율 기준 계획과 범위
 *       계산 제한 표시"를 요구한다. 계획 자체는 만들되 하단·상단을 기준값과 같게 두고 경고를 낸다.</li>
 *   <li><b>환율이 오래됐으면 계산하지 않는다</b> — 명세 §8 의 최신성 검증이다.</li>
 * </ul>
 *
 * <p>🔒 {@link ForecastService} 에서 가져오는 것은 {@code interval_80} 뿐이다.
 * {@code modelPath} 는 <b>읽지 않는다</b> — FR-FC-12 가 방향 전망을 계획 계산의 입력으로
 * 넘기는 것을 금지한다. 이 클래스가 그 경계를 지키는 자리다.
 */
@UseCase
public class PlanRateContextProvider {

    private static final Logger log = LoggerFactory.getLogger(PlanRateContextProvider.class);

    /** 환율 기준 통화. */
    private static final String QUOTE_CURRENCY = "KRW";

    /**
     * 스프레드·수수료 가정을 뽑을 기본 은행과 채널 (명세 §7 "스프레드 가정"·"수수료 가정").
     *
     * <p>사용자의 주거래 은행 설정이 아직 없어 대표값을 쓴다. 값이 바뀌면 계획 수치가 달라지므로
     * {@link PlannerPolicy#POLICY_VERSION} 을 함께 올린다.
     */
    private static final String DEFAULT_BANK_CODE = "004";

    /** 전신환(송금) 채널 — 목표 대부분이 현찰이 아니라 계좌 환전이다. */
    private static final String DEFAULT_CHANNEL = "transfer";

    private final PerUnitFxRates perUnitFxRates;
    private final ForecastService forecastService;
    private final MasterDataService masterDataService;
    private final Clock clock;

    public PlanRateContextProvider(
            PerUnitFxRates perUnitFxRates,
            ForecastService forecastService,
            MasterDataService masterDataService,
            Clock clock) {
        this.perUnitFxRates = Objects.requireNonNull(perUnitFxRates, "perUnitFxRates");
        this.forecastService = Objects.requireNonNull(forecastService, "forecastService");
        this.masterDataService = Objects.requireNonNull(masterDataService, "masterDataService");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * 통화의 계산 전제를 만든다.
     *
     * @param userId       조회 사용자 (Forecast 가 자산 영향 계산에 쓴다)
     * @param currencyCode 목표 통화
     * @return 환율 범위와 비용 가정
     * @throws InvalidRequestException 환율이 없거나 허용된 최신성 범위를 벗어난 경우 (명세 §8·§20)
     */
    @Transactional(readOnly = true)
    public PlanRateContext resolve(UUID userId, String currencyCode) {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(currencyCode, "currencyCode");

        BigDecimal baseRate = requireRate(currencyCode);
        MasterDataService.CurrencyView currency = masterDataService.requireCurrency(currencyCode);
        BankFxTermsMaster.Term terms = resolveTerms(currencyCode);

        Optional<ForecastService.ForecastView> forecast = findForecast(userId, currencyCode);
        RateRange rates = forecast
                .map(view -> toRateRange(baseRate, view))
                .orElseGet(() -> new RateRange(baseRate, baseRate, baseRate));
        Instant forecastAsOf = forecast.map(view -> toInstant(view.baseDate())).orElse(null);
        Instant rateAsOf = forecastAsOf != null ? forecastAsOf : Instant.now(clock);

        requireFresh(rateAsOf, currencyCode);

        return new PlanRateContext(
                currencyCode,
                rates.low().doubleValue(),
                rates.base().doubleValue(),
                rates.high().doubleValue(),
                terms.listSpread(),
                terms.fixedFeeKrw(),
                currency.quoteUnit(),
                currency.minorUnits(),
                rateAsOf,
                forecastAsOf,
                forecast.isPresent());
    }

    private BigDecimal requireRate(String currencyCode) {
        return perUnitFxRates.find(currencyCode)
                .filter(rate -> rate.signum() > 0)
                .orElseThrow(() -> new InvalidRequestException(
                        "환율을 조회할 수 없어 계획을 계산하지 않습니다: " + currencyCode, "currency_code"));
    }

    /** 통화·채널별 조건. 마스터에 없으면 기본 스프레드와 무수수료로 둔다. */
    private BankFxTermsMaster.Term resolveTerms(String currencyCode) {
        return BankFxTermsMaster.termsOf(DEFAULT_BANK_CODE).stream()
                .filter(term -> term.currencyCode().equals(currencyCode))
                .filter(term -> DEFAULT_CHANNEL.equals(term.channel()))
                .findFirst()
                .orElseGet(() -> new BankFxTermsMaster.Term(
                        currencyCode, DEFAULT_CHANNEL, BankSpreadTable.DEFAULT_BASE_SPREAD_RATIO, 0L));
    }

    /**
     * 예측 구간을 가져온다. 실패는 계획 생성을 막지 않는다 (명세 §20 "Forecast 없음").
     *
     * <p>관측이 부족한 통화(신규 상장 등)에서 {@link ForecastService} 가 400 을 던진다. 그때
     * 계획까지 막으면 "환율은 있는데 계획을 못 만드는" 상태가 되므로, 구간 없이 계산하고
     * 경고를 남긴다.
     */
    private Optional<ForecastService.ForecastView> findForecast(UUID userId, String currencyCode) {
        try {
            return Optional.of(forecastService.getForecast(
                    userId, currencyCode + QUOTE_CURRENCY, ForecastService.DEFAULT_HORIZON_DAYS));
        } catch (RuntimeException e) {
            log.warn("예측 구간을 얻지 못해 기준 환율만으로 계획을 계산합니다: {}", currencyCode, e);
            return Optional.empty();
        }
    }

    /**
     * 예측 구간을 환율 범위로 바꾼다.
     *
     * <p>기준값은 Forecast 의 {@code baseRate} 가 아니라 <b>지금 조회한 환율</b>을 쓴다 —
     * 두 값이 미세하게 다를 수 있는데, 비용 계산의 기준은 사용자가 실제로 마주할 현재 환율이어야
     * 한다. 구간이 기준값을 감싸지 못하면 기준값 쪽으로 넓혀 {@code low <= base <= high} 를
     * 지킨다 (그렇지 않으면 {@link RateRange} 가 거부한다).
     */
    private RateRange toRateRange(BigDecimal baseRate, ForecastService.ForecastView view) {
        BigDecimal low = BigDecimal.valueOf(view.interval80().lo()).min(baseRate);
        BigDecimal high = BigDecimal.valueOf(view.interval80().hi()).max(baseRate);
        return new RateRange(low, baseRate, high);
    }

    /** 명세 §8 — 기준 시각이 허용된 최신성 범위 안인지 확인한다. */
    private void requireFresh(Instant asOf, String currencyCode) {
        long days = ChronoUnit.DAYS.between(asOf, Instant.now(clock));
        if (days > PlannerPolicy.MAX_RATE_STALENESS_DAYS) {
            throw new InvalidRequestException(
                    "환율 데이터가 " + days + "일 지났습니다. 갱신 후 다시 시도해 주세요: " + currencyCode,
                    "currency_code");
        }
    }

    private Instant toInstant(LocalDate date) {
        return date.atStartOfDay(ZoneOffset.UTC).toInstant();
    }
}
