package com.divurve.domain.holding;

import com.divurve.common.architecture.UseCase;
import com.divurve.common.exception.InvalidRequestException;
import com.divurve.domain.holding.entity.PurchaseFxRate;
import com.divurve.domain.port.FxRateHistoryProvider;
import com.divurve.domain.port.FxRateHistoryProvider.HistoryRateSnapshot;
import com.divurve.engine.weight.QuoteUnitNormalizer;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 매입 시점 원화 환율을 해석한다 (FR-ON-04).
 *
 * <p>규칙
 * <ul>
 *   <li>KRW 자산 → 환율 컨텍스트를 만들지 않는다({@code null}).</li>
 *   <li>{@code purchasedAt == null} → 환율 컨텍스트 없음.</li>
 *   <li>클라이언트가 폴백 값을 넘겼으면 그대로 {@code source="manual"} 로 기록한다.</li>
 *   <li>그 외에는 {@link FxRateHistoryProvider}(예: ECOS)로 <b>매입일 당일 종가</b>를 조회. 실패 시
 *       {@link InvalidRequestException}(400 {@code VALIDATION_FAILED})으로 표면화해 프론트가
 *       수동 입력 UI 로 유도하게 한다.</li>
 * </ul>
 *
 * <p><b>이 클래스는 예전에 {@code FxRateProvider.fetchLatest} 를 불렀다</b> — {@code purchasedAt} 을
 * 파라미터로 받고도 쓰지 않아, 과거에 산 자산에 <b>조회 시점의 최신 종가</b>가 매입 환율로 박혔다(이슈 #98).
 * 2026-06-15 매입 USD 자산에 2026-09-04 종가가 들어가 매입원가가 10.6% 어긋났다
 * (1,520.4 → 1,359.5, $2,000 기준 3,040,800원 → 2,719,000원).
 * 옛 javadoc 이 "역사 시세 조회는 후속 확장 지점"이라고 적어 둔 그 확장은 이슈 #57 에서 이미 도착했고
 * ({@link FxRateHistoryProvider}), {@code /forecast}·{@code /market/regime} 만 옮겨가 있었다.
 *
 * <p>매입일이 휴장일(주말·공휴일)이면 <b>그 이전 최근 영업일</b> 종가를 쓴다. 없는 날의 값을 앞뒤로
 * 보간해 만들어내지 않는다(FR-CM-10) — 실제로 고시된 관측 하나를 고르고, 그 관측의 날짜를
 * {@link PurchaseFxRate#asOf()} 에 그대로 싣는다(NFR-DT-01).
 */
@UseCase
public class PurchaseFxRateResolver {

    private static final Logger log = LoggerFactory.getLogger(PurchaseFxRateResolver.class);

    /** 원화 자산은 환율 근거가 필요 없다. */
    public static final String KRW = "KRW";
    /** 수동 입력임을 표기할 때 사용하는 출처 식별자. */
    public static final String SOURCE_MANUAL = "manual";
    /**
     * 자동 조회 출처 식별자.
     *
     * <p>{@link FxRateHistoryProvider.HistoryRateSnapshot} 은 {@code RateSnapshot} 과 달리
     * {@code source} 를 싣지 않아 어댑터가 알려주지 못한다 — 포트에 {@code source} 를 더하는 편이
     * NFR-DT-01 에 맞지만 변동성 계산용 시계열 전체와 그 테스트로 번져 이 수정의 범위를 넘는다.
     * 여기서는 {@link #SOURCE_MANUAL} 과 대칭으로 상수를 두어 응답 값을 그대로 유지한다.
     */
    public static final String SOURCE_ECOS = "ECOS";
    /**
     * 자동조회 실패를 프론트가 수동 입력 UI 로 유도할 수 있게 알려주는 에러 필드명 (FR-ON-04).
     * 에러코드는 명세 §1.3 의 6종 닫힌 집합을 지켜 {@code VALIDATION_FAILED} 하나이므로,
     * 어떤 입력을 채워야 하는지는 {@code field} 로 구분한다.
     */
    public static final String FIELD_PURCHASE_FX_RATE_KRW = "purchase_fx_rate_krw";

    /**
     * 매입일에서 거슬러 올라가 영업일 종가 하나를 찾을 조회 구간(달력일).
     *
     * <p>설·추석 연휴가 주말과 붙으면 국내 휴장이 연속 6일까지 간다. 넉넉히 두어도 비용은
     * 응답에서 한 행을 고르는 것뿐이지만, 모자라면 정상 매입일이 400 으로 반려된다.
     */
    static final int LOOKBACK_CALENDAR_DAYS = 10;

    private final FxRateHistoryProvider historyProvider;
    private final QuoteUnitNormalizer quoteUnitNormalizer;

    public PurchaseFxRateResolver(
            FxRateHistoryProvider historyProvider, QuoteUnitNormalizer quoteUnitNormalizer) {
        this.historyProvider = Objects.requireNonNull(historyProvider, "historyProvider");
        this.quoteUnitNormalizer =
                Objects.requireNonNull(quoteUnitNormalizer, "quoteUnitNormalizer");
    }

    /**
     * 매입 환율 컨텍스트를 해석해 반환한다. 결과가 {@code null} 이면 저장할 환율이 없음을 뜻한다.
     *
     * @param currencyCode  자산 통화 (KRW 이면 무조건 {@code null})
     * @param purchasedAt   매입일 ({@code null} 이면 자동/수동 모두 스킵)
     * @param fallbackKrw   자동 조회 실패 시 클라이언트가 넘긴 수동 입력값 (선택)
     * @throws InvalidRequestException 조회 실패했고 폴백도 없을 때
     *                                (HTTP 400 {@code VALIDATION_FAILED}, field {@link #FIELD_PURCHASE_FX_RATE_KRW})
     */
    public PurchaseFxRate resolve(String currencyCode, LocalDate purchasedAt, BigDecimal fallbackKrw) {
        if (purchasedAt == null || KRW.equalsIgnoreCase(currencyCode)) {
            return null;
        }
        if (fallbackKrw != null) {
            return new PurchaseFxRate(fallbackKrw, SOURCE_MANUAL, purchasedAt);
        }
        String pairCode = currencyCode.toUpperCase() + "_" + KRW;

        List<HistoryRateSnapshot> observations;
        try {
            observations =
                    historyProvider.fetchHistorical(pairCode, purchasedAt, LOOKBACK_CALENDAR_DAYS);
        } catch (RuntimeException ex) {
            // 외부(ECOS) 원문 메시지에는 엔드포인트 URL·API 키가 실릴 수 있어 응답에 넘기지 않고 로그로만 남긴다.
            log.warn("매입 환율 자동조회 실패: pair={} purchasedAt={}", pairCode, purchasedAt, ex);
            throw manualInputRequired();
        }

        HistoryRateSnapshot observation = latestOnOrBefore(observations, purchasedAt);
        if (observation == null) {
            log.warn("매입일까지의 고시 종가를 찾지 못했습니다: pair={} purchasedAt={} lookbackDays={}",
                    pairCode, purchasedAt, LOOKBACK_CALENDAR_DAYS);
            throw manualInputRequired();
        }

        BigDecimal perUnitRate = quoteUnitNormalizer.toPerUnitRate(
                currencyCode, BigDecimal.valueOf(observation.rate()));
        return new PurchaseFxRate(perUnitRate, SOURCE_ECOS, observation.date());
    }

    /**
     * 매입일 이하 날짜 중 가장 최근 관측. 휴장일 매입을 직전 영업일 종가로 해석하는 자리다.
     *
     * <p>어댑터가 날짜 오름차순을 약속하지만 여기서 순서에 기대지 않는다 — 순서가 어긋나면
     * 조용히 <b>엉뚱한 날의 환율</b>이 매입가로 박히고, 그건 이 이슈에서 고친 것과 같은 종류의 오류다.
     *
     * @return 조건에 맞는 관측, 없으면 {@code null}
     */
    private static HistoryRateSnapshot latestOnOrBefore(
            List<HistoryRateSnapshot> observations, LocalDate purchasedAt) {
        if (observations == null) {
            return null;
        }
        return observations.stream()
                .filter(point -> point != null && point.date() != null && point.rate() != null)
                .filter(point -> !point.date().isAfter(purchasedAt))
                .max(java.util.Comparator.comparing(HistoryRateSnapshot::date))
                .orElse(null);
    }

    private static InvalidRequestException manualInputRequired() {
        return new InvalidRequestException(
                "매입 환율 자동조회에 실패했습니다. purchase_fx_rate_krw 를 직접 입력해주세요.",
                FIELD_PURCHASE_FX_RATE_KRW);
    }
}
