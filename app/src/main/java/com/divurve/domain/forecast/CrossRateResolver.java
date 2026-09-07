package com.divurve.domain.forecast;

import com.divurve.common.architecture.UseCase;
import com.divurve.common.exception.InvalidRequestException;
import com.divurve.domain.fx.PerUnitFxRates;
import com.divurve.domain.fx.StoredFxRates;
import com.divurve.domain.port.FxRateHistoryProvider;
import com.divurve.domain.port.FxRateHistoryProvider.HistoryRateSnapshot;
import com.divurve.engine.fx.CrossRateDeriver;
import com.divurve.engine.fx.CrossRateDeriver.DatedRate;
import com.divurve.engine.weight.QuoteUnitNormalizer;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 통화쌍 환율 조회 — 고시가 없는 쌍은 원화 크로스에서 유도한다 (이슈 #57).
 *
 * <p>현재 환율({@link #latestRate})과 과거 시계열({@link #fetch}) 둘 다 여기를 거친다.
 * 두 값의 단위가 어긋나면 {@code /forecast} 의 기준선과 band 가 따로 놀기 때문이다 —
 * 이 클래스가 내보내는 값은 <b>언제나 1통화 단위 기준</b>이다.
 *
 * <p>외부 어댑터(ECOS)는 <b>원화 크로스만</b> 제공하는데 명세 §5.10 은 {@code USDKRW}·{@code USDJPY}·
 * {@code EURUSD} 세 쌍을 요구한다. 그래서 {@code /market/regime} 은 세 쌍 중 하나만 채우고 나머지는
 * 조용히 빠뜨렸다 — 화면에는 "국면 정보 없음"으로 보이지만 원인은 데이터가 아니라 어댑터 한계였다.
 *
 * <p>여기서 두 갈래로 나눈다.
 * <ul>
 *   <li>표시통화가 원화면({@code USDKRW}) 어댑터에 그대로 묻는다.</li>
 *   <li>아니면({@code USDJPY}·{@code EURUSD}) 두 원화 크로스를 받아
 *       {@link CrossRateDeriver} 로 나눈다 (명세 §1.4 유도 환율).</li>
 * </ul>
 *
 * <p>유도 전에 {@link QuoteUnitNormalizer} 로 1단위 환율에 접는 것이 중요하다 — ECOS 는 JPY 를
 * 원/100엔으로 고시하므로, 접지 않으면 {@code USDJPY} 가 <b>100배</b>로 나온다.
 * 계산 자체는 engine 의 순수 함수가 하고 이 클래스는 어느 계열을 가져올지만 정한다.
 */
@UseCase
public class CrossRateResolver {

    /** 어댑터가 직접 고시하는 표시통화. 이 통화가 표시통화면 유도 없이 그대로 조회한다. */
    private static final String QUOTED_AGAINST = "KRW";

    private final StoredFxRates storedFxRates;
    private final FxRateHistoryProvider historyProvider;
    private final PerUnitFxRates perUnitFxRates;
    private final CrossRateDeriver crossRateDeriver;
    private final QuoteUnitNormalizer quoteUnitNormalizer;

    public CrossRateResolver(
            StoredFxRates storedFxRates,
            FxRateHistoryProvider historyProvider,
            PerUnitFxRates perUnitFxRates,
            CrossRateDeriver crossRateDeriver,
            QuoteUnitNormalizer quoteUnitNormalizer) {
        this.storedFxRates = Objects.requireNonNull(storedFxRates, "storedFxRates");
        this.historyProvider = Objects.requireNonNull(historyProvider, "historyProvider");
        this.perUnitFxRates = Objects.requireNonNull(perUnitFxRates, "perUnitFxRates");
        this.crossRateDeriver = Objects.requireNonNull(crossRateDeriver, "crossRateDeriver");
        this.quoteUnitNormalizer =
                Objects.requireNonNull(quoteUnitNormalizer, "quoteUnitNormalizer");
    }

    /**
     * 통화쌍의 최근 환율. 고시가 없는 쌍은 두 원화 크로스의 비로 유도한다 (이슈 #57).
     *
     * <p>{@code /forecast} 의 기준선({@code base_rate})이 이 값이다. 예전에는 서비스가
     * {@code fxRateProvider.fetchLatest("USD_JPY")} 를 직접 불러 ECOS item-code 조회에서 막혔고,
     * Swagger 가 광고하는 {@code USDJPY}·{@code EURUSD} 가 항상 400 으로 떨어졌다.
     *
     * @param pair 통화쌍
     * @return 최근 환율 (1단위 기준)
     * @throws InvalidRequestException 분모 통화의 환율이 0 이하라 나눌 수 없는 경우
     */
    public double latestRate(PairCode pair) {
        Objects.requireNonNull(pair, "pair");

        BigDecimal base = perUnitFxRates.require(pair.base());
        if (QUOTED_AGAINST.equals(pair.quote())) {
            return base.doubleValue();
        }

        BigDecimal quote = perUnitFxRates.require(pair.quote());
        if (quote.signum() <= 0) {
            throw new InvalidRequestException(
                    "유도 환율을 계산할 수 없습니다 (분모 통화 환율이 0 이하).", "pair_code");
        }
        return base.doubleValue() / quote.doubleValue();
    }

    /**
     * 통화쌍의 과거 일별 환율을 조회한다.
     *
     * @param pair                 통화쌍
     * @param endDate              조회 끝 날짜 (포함)
     * @param lookbackCalendarDays 거슬러 올라갈 달력일 수 ({@link HistoryWindow} 참고)
     * @return 일별 환율 (날짜 오름차순). 유도 쌍은 두 계열에 모두 있는 날짜만 담긴다
     */
    public List<HistoryRateSnapshot> fetch(
            PairCode pair, LocalDate endDate, int lookbackCalendarDays) {
        Objects.requireNonNull(pair, "pair");

        if (QUOTED_AGAINST.equals(pair.quote())) {
            // 1단위 기준으로 접어 내보낸다 — latestRate 와 단위가 어긋나면
            // JPYKRW 의 band 가 기준선과 100배 차이로 벌어진다.
            return perUnitSeries(pair.base(), endDate, lookbackCalendarDays).stream()
                    .map(point -> new HistoryRateSnapshot(point.date(), point.rate()))
                    .toList();
        }

        List<DatedRate> base = perUnitSeries(pair.base(), endDate, lookbackCalendarDays);
        List<DatedRate> quote = perUnitSeries(pair.quote(), endDate, lookbackCalendarDays);

        return crossRateDeriver.derive(base, quote).stream()
                .map(point -> new HistoryRateSnapshot(point.date(), point.rate()))
                .toList();
    }

    /**
     * 통화의 1통화 단위 기준 시계열을 만든다.
     *
     * <p>저장분이 그 구간을 <b>빠짐없이</b> 갖고 있으면 그것을 쓰고, 아니면 ECOS 를 실시간
     * 호출한다 (이슈 #116). 부분 데이터를 이어 붙이지 않는 것이 핵심이다 — 5년 백분위는 빠진
     * 날짜를 예외 없이 흡수해 버려서, 틀린 값이 조용히 나간다.
     *
     * <p>유도 쌍은 통화 두 개를 각각 이 메서드로 가져오므로 한쪽은 저장분, 다른 쪽은 실시간이
     * 될 수 있다. 두 값 모두 출처가 ECOS 인 같은 날짜의 종가이므로 섞여도 수치가 갈라지지 않고,
     * 조달 단위를 통화별로 두는 편이 "한 통화의 구멍 때문에 나머지까지 실시간으로 끌려가는" 것보다
     * 낫다.
     *
     * <p>ECOS 경로에서만 {@link QuoteUnitNormalizer} 를 태운다 — 저장분은 적재 시점에 이미
     * 1단위로 접혀 있다(JPY 는 원/100엔 고시).
     */
    private List<DatedRate> perUnitSeries(
            String currencyCode, LocalDate endDate, int lookbackCalendarDays) {
        Optional<List<StoredFxRates.Point>> stored =
                storedFxRates.perUnitSeries(currencyCode, endDate, lookbackCalendarDays);
        if (stored.isPresent()) {
            return stored.get().stream()
                    .map(point -> new DatedRate(point.date(), point.perUnitRate().doubleValue()))
                    .toList();
        }

        List<HistoryRateSnapshot> quoted = historyProvider.fetchHistorical(
                currencyCode + "_" + QUOTED_AGAINST, endDate, lookbackCalendarDays);
        if (quoted == null) {
            // 관측이 없는 것과 같게 다룬다 — 여기서 NPE 가 나면 "관측 부족" 400 이 500 으로 바뀐다.
            return List.of();
        }
        return quoted.stream()
                .map(snapshot -> new DatedRate(
                        snapshot.date(),
                        quoteUnitNormalizer
                                .toPerUnitRate(currencyCode, BigDecimal.valueOf(snapshot.rate()))
                                .doubleValue()))
                .toList();
    }
}
