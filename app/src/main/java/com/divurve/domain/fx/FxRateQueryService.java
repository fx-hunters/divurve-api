package com.divurve.domain.fx;

import com.divurve.common.architecture.UseCase;
import com.divurve.common.exception.InvalidRequestException;
import com.divurve.domain.forecast.PairCode;
import com.divurve.domain.master.CurrencyPairRepository;
import com.divurve.domain.master.entity.CurrencyPair;
import com.divurve.domain.fx.entity.FxRate;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import org.springframework.transaction.annotation.Transactional;

/**
 * 적재된 환율 시계열을 읽는다 (이슈 #111). 관리자 환율 차트의 데이터 원천이다.
 *
 * <p>지금 이 표를 읽는 곳은 여기 하나뿐이다 — 계산 경로(PerUnitFxRates·CrossRateResolver)는
 * 여전히 ECOS 를 실시간 호출한다. 전환은 구멍 탐지가 선행되어야 해서 이슈 #116 으로 분리했다.
 * 그전까지 이 표의 이득(배포 후 5년치 재조회 회피·ECOS 장애 내성)은 실현되지 않는다.
 *
 * <p><b>여기서는 계산하지 않는다.</b> 저장된 관측을 날짜순으로 돌려줄 뿐이다. 이동평균·추세 같은
 * 파생값은 만들지 않는다 — 화면의 목적이 "실제로 무엇이 들어와 있는가" 를 보는 것이기 때문이다.
 *
 * <p>유도 쌍({@code is_stored = false})은 {@code fx_rates} 에 행이 없다. 삼각환산으로 만들어
 * 돌려주는 것은 계산이므로 이 범위 밖이고, 저장된 값과 유도한 값이 같은 응답에 섞이면 무엇이
 * 관측인지 알 수 없게 된다. 그래서 400 으로 명시적으로 거절한다.
 */
@UseCase
public class FxRateQueryService {

    /** 한 번에 돌려주는 관측 수 상한. 5년치(약 1,300)보다 넉넉하되 무한 조회는 막는다. */
    static final int MAX_DAYS = 3_650;

    private final CurrencyPairRepository currencyPairRepository;
    private final FxRateRepository fxRateRepository;

    public FxRateQueryService(
            CurrencyPairRepository currencyPairRepository, FxRateRepository fxRateRepository) {
        this.currencyPairRepository =
                Objects.requireNonNull(currencyPairRepository, "currencyPairRepository");
        this.fxRateRepository = Objects.requireNonNull(fxRateRepository, "fxRateRepository");
    }

    /**
     * 한 통화쌍·종류의 기간 시계열을 반환한다.
     *
     * @param rawPairCode 통화쌍. {@code USDKRW}·{@code USD_KRW} 둘 다 받는다
     * @param from        시작일 (포함)
     * @param to          끝일 (포함)
     * @param rateTypeCode 환율 종류 코드 ({@code mid} 등)
     * @throws InvalidRequestException 마스터에 없는 쌍·유도 쌍·기간이 뒤집힌 경우 (400)
     */
    @Transactional(readOnly = true)
    public RateSeries series(String rawPairCode, LocalDate from, LocalDate to, String rateTypeCode) {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");

        if (from.isAfter(to)) {
            throw new InvalidRequestException("시작일이 끝일보다 늦습니다.", "from");
        }
        if (from.plusDays(MAX_DAYS).isBefore(to)) {
            throw new InvalidRequestException(
                    "조회 기간은 최대 " + MAX_DAYS + "일입니다.", "to");
        }

        String pairCode = PairCode.parse(rawPairCode).canonical();
        CurrencyPair pair = currencyPairRepository.findById(pairCode)
                .orElseThrow(() -> new InvalidRequestException(
                        "지원하지 않는 통화쌍입니다: " + pairCode, "pair_code"));
        if (!pair.isStored()) {
            throw new InvalidRequestException(
                    "환율을 저장하지 않는 통화쌍입니다(유도 쌍): " + pairCode, "pair_code");
        }

        FxRateType rateType = FxRateType.fromCode(rateTypeCode);
        List<Point> points =
                fxRateRepository
                        .findByIdPairCodeAndIdRateTypeAndIdQuoteDateBetweenOrderByIdQuoteDateAsc(
                                pairCode, rateType.code(), from, to)
                        .stream()
                        .map(FxRateQueryService::toPoint)
                        .toList();

        return new RateSeries(pairCode, rateType.code(), from, to, points);
    }

    private static Point toPoint(FxRate rate) {
        return new Point(
                rate.getId().getQuoteDate(),
                rate.getRate(),
                rate.getDataSource(),
                rate.getFetchedAt());
    }

    /**
     * 환율 시계열.
     *
     * @param pairCode 통화쌍 6자리
     * @param rateType 환율 종류 코드
     * @param from     요청한 시작일
     * @param to       요청한 끝일
     * @param points   관측. <b>영업일에만 존재</b>하므로 요청 구간보다 적다
     */
    public record RateSeries(
            String pairCode, String rateType, LocalDate from, LocalDate to, List<Point> points) {
    }

    /**
     * 관측 하나.
     *
     * @param quoteDate  고시일
     * @param rate       1 외화당 원화
     * @param dataSource 출처
     * @param fetchedAt  우리가 가져온 시각
     */
    public record Point(
            LocalDate quoteDate, BigDecimal rate, String dataSource, Instant fetchedAt) {
    }
}
