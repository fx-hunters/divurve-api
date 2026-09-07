package com.divurve.domain.fx;

import com.divurve.common.architecture.UseCase;
import com.divurve.common.exception.InvalidRequestException;
import com.divurve.domain.forecast.PairCode;
import com.divurve.domain.master.CurrencyPairRepository;
import com.divurve.domain.master.entity.CurrencyPair;
import com.divurve.domain.port.FxRateHistoryProvider;
import com.divurve.engine.weight.QuoteUnitNormalizer;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * ECOS 일별 종가를 {@code fx_rates} 에 적재한다 (이슈 #111, ERD v3.0 §6 구현순서 2단계).
 *
 * <p>지금까지 환율은 어디에도 저장하지 않고 요청 시점에 ECOS 를 호출해 캐시(6시간)에만 담았다.
 * 그래서 과거 어느 시점에 어떤 값으로 계산했는지 재현할 수 없었고, "환율이 실제로 들어오고 있는가"
 * 를 확인할 대상 자체가 없었다.
 *
 * <p><b>적재 대상은 {@code currency_pairs.is_stored = true} 인 쌍뿐이다.</b> 코드에 통화쌍 상수를
 * 박지 않는다 — 통화쌍 추가는 마스터 INSERT 한 행이어야 한다(ERD §5 의 설계 의도).
 *
 * <p><b>한 쌍의 실패가 나머지를 막지 않는다.</b> ECOS 가 특정 항목만 비어 있거나 일시적으로
 * 실패하는 일이 흔한데, 그때 전체 배치를 죽이면 멀쩡한 쌍까지 오늘 값을 잃는다.
 * 실패 사유는 리포트에 담아 호출자(스케줄러 로그·관리자 응답)가 보게 한다.
 *
 * <p>수치를 만들지 않는다 — ECOS 종가를 1단위 기준으로 접기만 한다(NFR-DT-01·NFR-DT-02).
 */
@UseCase
public class FxRateIngestionService {

    private static final Logger log = LoggerFactory.getLogger(FxRateIngestionService.class);

    /** ECOS 는 매매기준율만 고시한다. 나머지 종류는 적재하지 않는다({@link FxRateType} 참고). */
    private static final FxRateType INGESTED_TYPE = FxRateType.MID;

    /** 환율 1차 출처 (NFR-DT-02). */
    static final String DATA_SOURCE_ECOS = "ECOS";

    /** {@code numeric(14,6)} — 저장 자릿수를 여기서 확정해 DB 가 조용히 반올림하지 않게 한다. */
    static final int RATE_SCALE = 6;

    private final CurrencyPairRepository currencyPairRepository;
    private final FxRateHistoryProvider fxRateHistoryProvider;
    private final FxRateRepository fxRateRepository;
    private final QuoteUnitNormalizer quoteUnitNormalizer;
    private final Clock clock;

    public FxRateIngestionService(
            CurrencyPairRepository currencyPairRepository,
            FxRateHistoryProvider fxRateHistoryProvider,
            FxRateRepository fxRateRepository,
            QuoteUnitNormalizer quoteUnitNormalizer,
            Clock clock) {
        this.currencyPairRepository =
                Objects.requireNonNull(currencyPairRepository, "currencyPairRepository");
        this.fxRateHistoryProvider =
                Objects.requireNonNull(fxRateHistoryProvider, "fxRateHistoryProvider");
        this.fxRateRepository = Objects.requireNonNull(fxRateRepository, "fxRateRepository");
        this.quoteUnitNormalizer =
                Objects.requireNonNull(quoteUnitNormalizer, "quoteUnitNormalizer");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * 저장 대상 통화쌍 전부를 적재한다.
     *
     * @param endDate              조회 끝 날짜 (포함)
     * @param lookbackCalendarDays 거슬러 올라갈 달력일 수 (영업일이 아니다)
     * @return 쌍별 결과. 실패한 쌍도 사유와 함께 담긴다
     */
    public IngestionReport ingest(LocalDate endDate, int lookbackCalendarDays) {
        Objects.requireNonNull(endDate, "endDate");
        if (lookbackCalendarDays <= 0) {
            throw new InvalidRequestException(
                    "조회 기간은 1일 이상이어야 합니다.", "lookback_calendar_days");
        }

        List<PairResult> results = new ArrayList<>();
        for (CurrencyPair pair : currencyPairRepository.findByStoredTrueOrderByPairCodeAsc()) {
            results.add(ingestPair(pair.getPairCode(), endDate, lookbackCalendarDays));
        }
        return new IngestionReport(results, Instant.now(clock));
    }

    /**
     * 통화쌍 하나를 적재한다.
     *
     * @throws InvalidRequestException 마스터에 없거나 저장 대상이 아닌 쌍인 경우 (400)
     */
    public PairResult ingestOne(String rawPairCode, LocalDate endDate, int lookbackCalendarDays) {
        String pairCode = PairCode.parse(rawPairCode).canonical();
        CurrencyPair pair = currencyPairRepository.findById(pairCode)
                .orElseThrow(() -> new InvalidRequestException(
                        "지원하지 않는 통화쌍입니다: " + pairCode, "pair_code"));
        if (!pair.isStored()) {
            throw new InvalidRequestException(
                    "저장 대상이 아닌 통화쌍입니다(유도 쌍): " + pairCode, "pair_code");
        }
        return ingestPair(pairCode, endDate, lookbackCalendarDays);
    }

    private PairResult ingestPair(String pairCode, LocalDate endDate, int lookbackCalendarDays) {
        try {
            PairCode parsed = PairCode.parse(pairCode);
            List<FxRateHistoryProvider.HistoryRateSnapshot> snapshots =
                    fxRateHistoryProvider.fetchHistorical(
                            parsed.providerCode(), endDate, lookbackCalendarDays);

            Instant fetchedAt = Instant.now(clock);
            int upserted = 0;
            LocalDate first = null;
            LocalDate last = null;
            for (FxRateHistoryProvider.HistoryRateSnapshot snapshot : snapshots) {
                fxRateRepository.upsert(
                        pairCode,
                        snapshot.date(),
                        INGESTED_TYPE.code(),
                        perUnitRate(parsed.base(), snapshot.rate()),
                        DATA_SOURCE_ECOS,
                        fetchedAt);
                upserted++;
                if (first == null) {
                    first = snapshot.date();
                }
                last = snapshot.date();
            }
            return new PairResult(pairCode, upserted, first, last, null);
        } catch (RuntimeException e) {
            // 한 쌍의 실패가 나머지를 막지 않는다 — 사유를 값으로 남겨 호출자가 보게 한다.
            log.warn("환율 적재 실패: pairCode={}", pairCode, e);
            return new PairResult(pairCode, 0, null, null, e.getMessage());
        }
    }

    /**
     * ECOS 고시값을 1단위 기준으로 접는다. JPY 는 원/100엔으로 고시되므로 그대로 저장하면
     * 이 표를 읽는 모든 코드가 통화별 분기를 갖게 된다.
     */
    private BigDecimal perUnitRate(String baseCurrencyCode, Double quotedRate) {
        return quoteUnitNormalizer
                .toPerUnitRate(baseCurrencyCode, BigDecimal.valueOf(quotedRate))
                .setScale(RATE_SCALE, RoundingMode.HALF_UP);
    }

    /**
     * 적재 결과 전체.
     *
     * @param pairs      쌍별 결과
     * @param ingestedAt 적재를 수행한 시각
     */
    public record IngestionReport(List<PairResult> pairs, Instant ingestedAt) {

        /** 실제로 반영된 행 수 합계. */
        public int totalUpserted() {
            return pairs.stream().mapToInt(PairResult::upserted).sum();
        }

        /** 실패한 쌍이 하나라도 있는가 — 스케줄러가 로그 수준을 가르는 데 쓴다. */
        public boolean hasFailure() {
            return pairs.stream().anyMatch(pair -> pair.failureReason() != null);
        }
    }

    /**
     * 통화쌍 하나의 적재 결과.
     *
     * @param pairCode      통화쌍 6자리
     * @param upserted      반영된 행 수
     * @param firstDate     반영 구간의 시작일. 관측이 없으면 {@code null}
     * @param lastDate      반영 구간의 끝일. 관측이 없으면 {@code null}
     * @param failureReason 실패 사유. 성공이면 {@code null}
     */
    public record PairResult(
            String pairCode,
            int upserted,
            LocalDate firstDate,
            LocalDate lastDate,
            String failureReason) {
    }
}
