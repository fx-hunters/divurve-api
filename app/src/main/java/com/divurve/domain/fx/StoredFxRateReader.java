package com.divurve.domain.fx;

import com.divurve.common.architecture.UseCase;
import com.divurve.domain.forecast.PairCode;
import com.divurve.domain.fx.entity.FxRate;
import com.divurve.domain.master.CurrencyPairRepository;
import com.divurve.domain.master.entity.CurrencyPair;
import com.divurve.engine.fx.FxRateCoverage;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@code fx_rates} 를 계산 경로의 1차 출처로 읽는다 (이슈 #116 2단계).
 *
 * <h2>이 클래스가 생기기 전</h2>
 *
 * <p>{@code fx_rates}(이슈 #111)를 읽는 곳은 관리자 차트 하나뿐이었고 계산 경로 7곳은 여전히
 * ECOS 를 실시간 호출했다. 그래서 저장의 근거였던 이득 — 배포 직후 5년치 재조회 회피, ECOS
 * 장애 내성 — 이 하나도 실현되지 않았다.
 *
 * <h2>신뢰 조건: 완전할 때만</h2>
 *
 * <p>"있으면 쓴다" 가 아니다. 구간에 구멍이 하나라도 있으면 빈 값을 돌려 호출자가 ECOS 로 가게
 * 한다. 부분 데이터로 5년 변동성 백분위를 계산하면 값이 <b>조용히</b> 틀리기 때문이다 —
 * 예외도 안 나고 화면도 정상으로 보인다.
 *
 * <p>완전성은 {@link FxRateGapService#settledThrough()}(오늘 직전 영업일)까지만 본다. 오늘 값이
 * 아직 안 올라온 것은 구멍이 아니라 아직 오지 않은 것이다. 반대로 시계열에는 <b>오늘 값이 있으면
 * 함께 담는다</b> — 배치가 이미 돌았는데 굳이 빼면 ECOS 경로보다 하루 낡은 값을 주게 된다.
 *
 * <p>여기서 정규화하지 않는다. {@code fx_rates.rate} 는 적재 시점에 이미 1단위로 접혀 있고
 * ({@code FxRateIngestionService}), 다시 {@code QuoteUnitNormalizer} 를 태우면 JPY 가
 * 100분의 1이 된다.
 *
 * <p><b>{@code meta.data_state}·{@code meta.sources}(FR-CM-10)는 이 전환으로 달라지지 않는다.</b>
 * DB 가 들고 있는 값도 출처는 ECOS 이고({@code fx_rates.data_source}) 우리가 만든 수치가 아니다.
 * 읽은 경로가 DB 냐 실시간이냐는 출처가 아니라 조달 방법의 차이다.
 */
@UseCase
public class StoredFxRateReader implements StoredFxRates {

    private static final Logger log = LoggerFactory.getLogger(StoredFxRateReader.class);

    /** 원화 표시 고시 — 저장 쌍은 전부 {@code <통화>KRW} 다 (V21 주석). */
    private static final String QUOTE_CURRENCY = "KRW";

    /**
     * 최신값을 믿기 전에 되돌아보는 구간(달력일).
     *
     * <p>최신값 하나만 보면 "배치가 어제 한 번 돌고 그 전 한 달이 비어 있는" 상태를 통과시킨다.
     * 그 상태에서 최신값은 맞더라도, 같은 배치를 근거로 삼는 시계열 경로와 판정이 엇갈린다.
     * 한 달이면 배치 정지를 잡기에 충분하고 조회 비용도 날짜 20여 개다.
     */
    static final int LATEST_CHECK_CALENDAR_DAYS = 30;

    private final CurrencyPairRepository currencyPairRepository;
    private final FxRateRepository fxRateRepository;
    private final FxRateGapService fxRateGapService;
    private final Clock clock;

    public StoredFxRateReader(
            CurrencyPairRepository currencyPairRepository,
            FxRateRepository fxRateRepository,
            FxRateGapService fxRateGapService,
            Clock clock) {
        this.currencyPairRepository =
                Objects.requireNonNull(currencyPairRepository, "currencyPairRepository");
        this.fxRateRepository = Objects.requireNonNull(fxRateRepository, "fxRateRepository");
        this.fxRateGapService = Objects.requireNonNull(fxRateGapService, "fxRateGapService");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<BigDecimal> latestPerUnitRate(String currencyCode) {
        Objects.requireNonNull(currencyCode, "currencyCode");
        return storedPairCode(currencyCode).flatMap(this::latestForPair);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<List<Point>> perUnitSeries(
            String currencyCode, LocalDate endDate, int lookbackCalendarDays) {
        Objects.requireNonNull(currencyCode, "currencyCode");
        Objects.requireNonNull(endDate, "endDate");
        if (lookbackCalendarDays <= 0) {
            throw new IllegalArgumentException(
                    "lookbackCalendarDays must be positive, got %d".formatted(lookbackCalendarDays));
        }
        return storedPairCode(currencyCode)
                .flatMap(pairCode -> seriesForPair(pairCode, endDate, lookbackCalendarDays));
    }

    private Optional<BigDecimal> latestForPair(String pairCode) {
        LocalDate settledThrough = fxRateGapService.settledThrough();
        LocalDate today = LocalDate.now(clock);

        if (!isComplete(pairCode, settledThrough.minusDays(LATEST_CHECK_CALENDAR_DAYS),
                settledThrough)) {
            return Optional.empty();
        }
        return fxRateRepository
                .findTopByIdPairCodeAndIdRateTypeAndIdQuoteDateLessThanEqualOrderByIdQuoteDateDesc(
                        pairCode, FxRateGapService.RATE_TYPE.code(), today)
                .map(FxRate::getRate);
    }

    private Optional<List<Point>> seriesForPair(
            String pairCode, LocalDate endDate, int lookbackCalendarDays) {
        LocalDate from = endDate.minusDays(lookbackCalendarDays);
        LocalDate checkedThrough = min(endDate, fxRateGapService.settledThrough());
        if (checkedThrough.isBefore(from)) {
            // 구간이 통째로 미확정이다 — 판정할 근거가 없으므로 신뢰하지 않는다.
            return Optional.empty();
        }
        if (!isComplete(pairCode, from, checkedThrough)) {
            return Optional.empty();
        }

        List<Point> points = fxRateRepository
                .findByIdPairCodeAndIdRateTypeAndIdQuoteDateBetweenOrderByIdQuoteDateAsc(
                        pairCode, FxRateGapService.RATE_TYPE.code(), from, endDate)
                .stream()
                .map(rate -> new Point(rate.getId().getQuoteDate(), rate.getRate()))
                .toList();
        return Optional.of(points);
    }

    private boolean isComplete(String pairCode, LocalDate from, LocalDate to) {
        FxRateCoverage coverage = fxRateGapService.coverageOf(pairCode, from, to);
        if (!coverage.complete()) {
            // 구멍은 운영이 알아야 할 신호다 — 조용히 ECOS 로 넘어가면 배치가 죽은 것을 아무도 모른다.
            log.warn("저장 환율에 구멍이 있어 실시간 조회로 넘어간다: pairCode={} from={} to={} 구멍={}",
                    pairCode, from, to, coverage.gaps());
            return false;
        }
        return true;
    }

    /** 저장 대상 쌍일 때만 통화쌍 코드를 돌려준다 — 유도 쌍은 {@code fx_rates} 에 행이 없다. */
    private Optional<String> storedPairCode(String currencyCode) {
        String pairCode = PairCode.parse(currencyCode + QUOTE_CURRENCY).canonical();
        return currencyPairRepository.findById(pairCode)
                .filter(CurrencyPair::isStored)
                .map(CurrencyPair::getPairCode);
    }

    private static LocalDate min(LocalDate a, LocalDate b) {
        return a.isBefore(b) ? a : b;
    }
}
