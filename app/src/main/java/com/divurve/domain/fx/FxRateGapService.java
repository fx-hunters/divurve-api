package com.divurve.domain.fx;

import com.divurve.common.architecture.UseCase;
import com.divurve.common.exception.InvalidRequestException;
import com.divurve.domain.forecast.PairCode;
import com.divurve.domain.master.CurrencyPairRepository;
import com.divurve.domain.master.entity.CurrencyPair;
import com.divurve.domain.port.FxRateHistoryProvider;
import com.divurve.engine.fx.FxRateCoverage;
import com.divurve.engine.fx.FxRateGapDetector;
import com.divurve.engine.planner.BusinessDayCalendar;
import com.divurve.engine.weight.QuoteUnitNormalizer;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@code fx_rates} 의 구멍을 찾고 그 구간만 다시 받아 메운다 (이슈 #116 1단계).
 *
 * <h2>왜 읽기 경로 전환보다 먼저인가</h2>
 *
 * <p>계산 경로를 "DB 우선, 없으면 ECOS 폴백" 으로만 바꾸면 배치가 돈 날은 DB 값이, 빠진 날은
 * 실시간 값이 나간다. <b>같은 질문에 사용자·시점마다 다른 수치가 나가고</b>, 일관되게 실시간인
 * 이전 상태보다 나쁘다. 게다가 구멍을 봐도 원인을 구분할 수 없다 — "휴일이라 없는 날인가,
 * 배치가 실패한 날인가?" 를 답할 수 없으면 {@code fx_rates} 는 이득이 아니라 부채가 된다.
 *
 * <h2>구멍의 정의</h2>
 *
 * <pre>구멍 = 영업일 − fx_rates 에 있는 날 − fx_rate_absences 에 있는 날</pre>
 *
 * <p>{@link BusinessDayCalendar} 는 주말만 제외하므로(공휴일 테이블이 아직 없다) 값만 대조하면
 * 한국 공휴일 15일 내외가 매년 구멍으로 잡힌다. 그래서 백필이 ECOS 에 물어 보고도 값이 없던
 * 날짜를 {@code fx_rate_absences} 에 <b>부재 확정</b>으로 남긴다(V23). 그러고 나면 남아 있는
 * 구멍은 전부 "우리가 못 받은 날" 이다.
 *
 * <h2>오늘은 확정하지 않는다</h2>
 *
 * <p>부재 확정은 <b>어제까지</b>만 한다. ECOS 는 KST 오전에 고시하므로, 오늘 값이 아직 안 올라온
 * 상태를 "고시가 없는 날" 로 굳히면 그 날짜는 영원히 채워지지 않는다. 되돌릴 수 있는 실수가
 * 아니라 관측을 잃는 실수다.
 *
 * <p>수치를 만들지 않는다 — ECOS 종가를 1단위 기준으로 접어 저장하고, 없으면 없다고 기록할 뿐이다
 * (NFR-DT-01·NFR-DT-02).
 */
@UseCase
public class FxRateGapService {

    private static final Logger log = LoggerFactory.getLogger(FxRateGapService.class);

    /** ECOS 는 매매기준율만 고시한다 — 구멍 판정 대상도 이 종류 하나다. */
    static final FxRateType RATE_TYPE = FxRateType.MID;

    /** 백필이 한 번에 다루는 최대 구간. 5년치(약 1,830일)를 한 번에 돌릴 수 있어야 한다. */
    static final int MAX_RANGE_DAYS = 3_650;

    private final CurrencyPairRepository currencyPairRepository;
    private final FxRateRepository fxRateRepository;
    private final FxRateAbsenceRepository fxRateAbsenceRepository;
    private final FxRateHistoryProvider fxRateHistoryProvider;
    private final FxRateGapDetector fxRateGapDetector;
    private final BusinessDayCalendar businessDayCalendar;
    private final QuoteUnitNormalizer quoteUnitNormalizer;
    private final Clock clock;

    public FxRateGapService(
            CurrencyPairRepository currencyPairRepository,
            FxRateRepository fxRateRepository,
            FxRateAbsenceRepository fxRateAbsenceRepository,
            FxRateHistoryProvider fxRateHistoryProvider,
            FxRateGapDetector fxRateGapDetector,
            BusinessDayCalendar businessDayCalendar,
            QuoteUnitNormalizer quoteUnitNormalizer,
            Clock clock) {
        this.currencyPairRepository =
                Objects.requireNonNull(currencyPairRepository, "currencyPairRepository");
        this.fxRateRepository = Objects.requireNonNull(fxRateRepository, "fxRateRepository");
        this.fxRateAbsenceRepository =
                Objects.requireNonNull(fxRateAbsenceRepository, "fxRateAbsenceRepository");
        this.fxRateHistoryProvider =
                Objects.requireNonNull(fxRateHistoryProvider, "fxRateHistoryProvider");
        this.fxRateGapDetector = Objects.requireNonNull(fxRateGapDetector, "fxRateGapDetector");
        this.businessDayCalendar =
                Objects.requireNonNull(businessDayCalendar, "businessDayCalendar");
        this.quoteUnitNormalizer =
                Objects.requireNonNull(quoteUnitNormalizer, "quoteUnitNormalizer");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * 부재 확정을 굳혀도 되는 마지막 날짜 — <b>오늘 직전 영업일</b>.
     *
     * <p>읽기 경로의 "완전한가" 판정도 여기까지만 본다. 오늘 값이 아직 안 올라온 것은 구멍이
     * 아니라 아직 오지 않은 것이고, 그것까지 구멍으로 세면 판정이 오전 내내 거짓이 된다.
     *
     * @return 오늘 직전 영업일
     */
    public LocalDate settledThrough() {
        return businessDayCalendar.minusBusinessDays(LocalDate.now(clock), 1);
    }

    /**
     * 통화쌍 하나의 커버리지 (관리자 조회).
     *
     * @param rawPairCode 통화쌍. {@code USDKRW}·{@code USD_KRW} 둘 다 받는다
     * @param from        시작일 (포함)
     * @param to          끝일 (포함)
     * @throws InvalidRequestException 마스터에 없는 쌍·유도 쌍·기간이 뒤집히거나 너무 긴 경우 (400)
     */
    @Transactional(readOnly = true)
    public PairCoverage coverage(String rawPairCode, LocalDate from, LocalDate to) {
        validateRange(from, to);
        String pairCode = requireStoredPair(rawPairCode);
        return PairCoverage.of(pairCode, RATE_TYPE.code(), coverageOf(pairCode, from, to));
    }

    /**
     * 저장 대상 통화쌍 전부의 커버리지. 갱신 응답에 실어 "갱신 후 완전해졌는가" 를 바로 보이게 한다.
     *
     * @param from 시작일 (포함)
     * @param to   끝일 (포함)
     */
    @Transactional(readOnly = true)
    public List<PairCoverage> coverageOfStoredPairs(LocalDate from, LocalDate to) {
        validateRange(from, to);
        List<PairCoverage> coverages = new ArrayList<>();
        for (CurrencyPair pair : currencyPairRepository.findByStoredTrueOrderByPairCodeAsc()) {
            coverages.add(PairCoverage.of(
                    pair.getPairCode(),
                    RATE_TYPE.code(),
                    coverageOf(pair.getPairCode(), from, to)));
        }
        return coverages;
    }

    /**
     * 구간의 커버리지를 판정한다. 통화쌍 검증을 하지 않는 내부용 — 호출자가 이미 저장 쌍임을 안다.
     *
     * @param pairCode 통화쌍 6자리 (정규 표기)
     * @param from     시작일 (포함)
     * @param to       끝일 (포함)
     */
    @Transactional(readOnly = true)
    public FxRateCoverage coverageOf(String pairCode, LocalDate from, LocalDate to) {
        return fxRateGapDetector.detect(from, to, knownDates(pairCode, from, to));
    }

    /**
     * 통화쌍 하나의 구멍을 메운다 (관리자 백필·초기 적재).
     *
     * <p>전체 재적재가 아니라 <b>구간 단위</b>다 — 5년치 4쌍을 통째로 다시 받으면 ECOS 에
     * 불필요한 부하를 주고, 이미 들어와 있는 값을 덮어쓰며 {@code fetched_at} 만 바꾼다.
     *
     * @param rawPairCode 통화쌍
     * @param from        시작일 (포함)
     * @param to          끝일 (포함)
     * @throws InvalidRequestException 마스터에 없는 쌍·유도 쌍·기간이 뒤집히거나 너무 긴 경우 (400)
     */
    public PairBackfill backfill(String rawPairCode, LocalDate from, LocalDate to) {
        validateRange(from, to);
        String pairCode = requireStoredPair(rawPairCode);
        return backfillPair(pairCode, from, to);
    }

    /**
     * 저장 대상 통화쌍 전부의 구멍을 메운다. 초기 5년 백필이 이 경로를 쓴다.
     *
     * <p>한 쌍의 실패가 나머지를 막지 않는다 — 사유는 결과에 값으로 담긴다
     * ({@link FxRateIngestionService} 와 같은 규약).
     */
    public BackfillReport backfillStoredPairs(LocalDate from, LocalDate to) {
        validateRange(from, to);
        List<PairBackfill> pairs = new ArrayList<>();
        for (CurrencyPair pair : currencyPairRepository.findByStoredTrueOrderByPairCodeAsc()) {
            pairs.add(backfillPair(pair.getPairCode(), from, to));
        }
        return new BackfillReport(pairs, Instant.now(clock));
    }

    private PairBackfill backfillPair(String pairCode, LocalDate from, LocalDate to) {
        FxRateCoverage before = coverageOf(pairCode, from, to);
        if (before.complete()) {
            return PairBackfill.of(pairCode, before, before, 0, 0, null);
        }

        int filled = 0;
        int confirmedAbsent = 0;
        try {
            for (FxRateCoverage.Gap gap : before.gaps()) {
                GapFillResult result = fillGap(pairCode, gap);
                filled += result.filled();
                confirmedAbsent += result.confirmedAbsent();
            }
        } catch (RuntimeException e) {
            // ECOS 가 죽어 있는 것과 우리가 안 받은 것을 구분해야 한다. 여기서 예외를 삼키고
            // 부재 확정을 굳히면 장애를 "고시 없음" 으로 기록해 버린다 — 되돌릴 수 없다.
            log.warn("환율 백필 실패: pairCode={} from={} to={}", pairCode, from, to, e);
            return PairBackfill.of(
                    pairCode, before, coverageOf(pairCode, from, to), filled, confirmedAbsent,
                    e.getMessage());
        }
        return PairBackfill.of(
                pairCode, before, coverageOf(pairCode, from, to), filled, confirmedAbsent, null);
    }

    /**
     * 구멍 하나를 ECOS 에 다시 물어 메운다.
     *
     * <p>돌아온 날짜는 값으로 넣고 부재 확정을 걷어낸다(뒤늦은 고시). 돌아오지 않은 영업일은
     * 부재로 확정하되 <b>오늘 이후는 건드리지 않는다</b>.
     */
    private GapFillResult fillGap(String pairCode, FxRateCoverage.Gap gap) {
        PairCode parsed = PairCode.parse(pairCode);
        int lookbackCalendarDays =
                (int) Math.max(1, ChronoUnit.DAYS.between(gap.from(), gap.to()));

        List<FxRateHistoryProvider.HistoryRateSnapshot> snapshots =
                fxRateHistoryProvider.fetchHistorical(
                        parsed.providerCode(), gap.to(), lookbackCalendarDays);
        Instant now = Instant.now(clock);

        Set<LocalDate> received = new HashSet<>();
        int filled = 0;
        if (snapshots != null) {
            for (FxRateHistoryProvider.HistoryRateSnapshot snapshot : snapshots) {
                if (snapshot.date().isBefore(gap.from()) || snapshot.date().isAfter(gap.to())) {
                    // 어댑터가 구간을 넉넉히 돌려줘도 구멍 밖 날짜는 이 백필의 관심사가 아니다.
                    continue;
                }
                fxRateRepository.upsert(
                        pairCode,
                        snapshot.date(),
                        RATE_TYPE.code(),
                        perUnitRate(parsed.base(), snapshot.rate()),
                        FxRateIngestionService.DATA_SOURCE_ECOS,
                        now);
                fxRateAbsenceRepository.release(pairCode, snapshot.date(), RATE_TYPE.code());
                received.add(snapshot.date());
                filled++;
            }
        }

        LocalDate confirmableThrough = settledThrough();
        int confirmedAbsent = 0;
        for (LocalDate date = gap.from(); !date.isAfter(gap.to()); date = date.plusDays(1)) {
            if (!businessDayCalendar.isBusinessDay(date)
                    || received.contains(date)
                    || date.isAfter(confirmableThrough)) {
                continue;
            }
            fxRateAbsenceRepository.confirm(pairCode, date, RATE_TYPE.code(), now);
            confirmedAbsent++;
        }
        return new GapFillResult(filled, confirmedAbsent);
    }

    /** 값이 있는 날 + 부재가 확정된 날. 이 합집합의 여집합이 구멍이다. */
    private List<LocalDate> knownDates(String pairCode, LocalDate from, LocalDate to) {
        List<LocalDate> known = new ArrayList<>(
                fxRateRepository.findQuoteDates(pairCode, RATE_TYPE.code(), from, to));
        known.addAll(
                fxRateAbsenceRepository.findConfirmedDates(pairCode, RATE_TYPE.code(), from, to));
        return known;
    }

    /** ECOS 고시값을 1단위 기준으로 접는다 ({@link FxRateIngestionService} 와 같은 규약). */
    private BigDecimal perUnitRate(String baseCurrencyCode, Double quotedRate) {
        return quoteUnitNormalizer
                .toPerUnitRate(baseCurrencyCode, BigDecimal.valueOf(quotedRate))
                .setScale(FxRateIngestionService.RATE_SCALE, RoundingMode.HALF_UP);
    }

    private String requireStoredPair(String rawPairCode) {
        String pairCode = PairCode.parse(rawPairCode).canonical();
        CurrencyPair pair = currencyPairRepository.findById(pairCode)
                .orElseThrow(() -> new InvalidRequestException(
                        "지원하지 않는 통화쌍입니다: " + pairCode, "pair_code"));
        if (!pair.isStored()) {
            throw new InvalidRequestException(
                    "환율을 저장하지 않는 통화쌍입니다(유도 쌍): " + pairCode, "pair_code");
        }
        return pairCode;
    }

    private static void validateRange(LocalDate from, LocalDate to) {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        if (from.isAfter(to)) {
            throw new InvalidRequestException("시작일이 끝일보다 늦습니다.", "from");
        }
        if (from.plusDays(MAX_RANGE_DAYS).isBefore(to)) {
            throw new InvalidRequestException("조회 기간은 최대 " + MAX_RANGE_DAYS + "일입니다.", "to");
        }
    }

    private record GapFillResult(int filled, int confirmedAbsent) {
    }

    /**
     * 통화쌍 하나의 커버리지.
     *
     * <p>engine 의 {@link FxRateCoverage} 를 그대로 내보내지 않고 여기서 펼친다 — API 레이어는
     * engine 을 직접 참조할 수 없다(ArchUnit {@code ModuleArchitectureTest}). 계산 결과를 도메인
     * 언어로 옮기는 자리가 있어야 그 규칙이 우회가 아니라 구조로 지켜진다.
     *
     * @param pairCode             통화쌍 6자리
     * @param rateType             환율 종류 코드
     * @param from                 요청 시작일
     * @param to                   요청 끝일
     * @param expectedBusinessDays 구간 안의 영업일 수
     * @param coveredBusinessDays  값이나 부재 확정이 있는 날 수
     * @param missingBusinessDays  빠진 영업일 수
     * @param coverageRatio        커버리지 비율 (0.0~1.0)
     * @param complete             구멍이 하나도 없는가
     * @param gaps                 빠진 연속 구간
     */
    public record PairCoverage(
            String pairCode,
            String rateType,
            LocalDate from,
            LocalDate to,
            int expectedBusinessDays,
            int coveredBusinessDays,
            int missingBusinessDays,
            double coverageRatio,
            boolean complete,
            List<Gap> gaps) {

        static PairCoverage of(String pairCode, String rateType, FxRateCoverage coverage) {
            return new PairCoverage(
                    pairCode,
                    rateType,
                    coverage.from(),
                    coverage.to(),
                    coverage.expectedBusinessDays(),
                    coverage.coveredBusinessDays(),
                    coverage.missingBusinessDays(),
                    coverage.coverageRatio(),
                    coverage.complete(),
                    Gap.of(coverage.gaps()));
        }
    }

    /**
     * 빠진 연속 구간 하나.
     *
     * @param from         구간의 첫 영업일
     * @param to           구간의 마지막 영업일
     * @param businessDays 구간 안의 빠진 영업일 수
     */
    public record Gap(LocalDate from, LocalDate to, int businessDays) {

        static List<Gap> of(List<FxRateCoverage.Gap> gaps) {
            return gaps.stream()
                    .map(gap -> new Gap(gap.from(), gap.to(), gap.businessDays()))
                    .toList();
        }
    }

    /**
     * 통화쌍 하나의 백필 결과.
     *
     * <p>백필 전후를 둘 다 담는 이유는, 백필이 "돌았다" 가 아니라 "무엇이 좋아졌는가" 를 보여야
     * 하기 때문이다. 남은 구멍이 있으면 그것은 ECOS 도 답하지 못한 구간이거나 우리가 아직 못 받은
     * 구간이다.
     *
     * @param pairCode        통화쌍 6자리
     * @param filled          값으로 채운 날 수
     * @param confirmedAbsent 고시가 없다고 확정한 날 수
     * @param missingBefore   백필 전 빠진 영업일 수
     * @param missingAfter    백필 후 빠진 영업일 수
     * @param complete        백필 후 완전해졌는가
     * @param remainingGaps   백필 후에도 남은 구간
     * @param failureReason   실패 사유. 성공이면 {@code null}
     */
    public record PairBackfill(
            String pairCode,
            int filled,
            int confirmedAbsent,
            int missingBefore,
            int missingAfter,
            boolean complete,
            List<Gap> remainingGaps,
            String failureReason) {

        static PairBackfill of(
                String pairCode,
                FxRateCoverage before,
                FxRateCoverage after,
                int filled,
                int confirmedAbsent,
                String failureReason) {
            return new PairBackfill(
                    pairCode,
                    filled,
                    confirmedAbsent,
                    before.missingBusinessDays(),
                    after.missingBusinessDays(),
                    after.complete(),
                    Gap.of(after.gaps()),
                    failureReason);
        }
    }

    /**
     * 백필 결과 전체.
     *
     * @param pairs        쌍별 결과
     * @param backfilledAt 백필을 수행한 시각
     */
    public record BackfillReport(List<PairBackfill> pairs, Instant backfilledAt) {

        /** 값으로 채운 날 수 합계. */
        public int totalFilled() {
            return pairs.stream().mapToInt(PairBackfill::filled).sum();
        }

        /** 고시 부재로 확정한 날 수 합계. */
        public int totalConfirmedAbsent() {
            return pairs.stream().mapToInt(PairBackfill::confirmedAbsent).sum();
        }

        /** 실패한 쌍이 하나라도 있는가. */
        public boolean hasFailure() {
            return pairs.stream().anyMatch(pair -> pair.failureReason() != null);
        }

        /** 모든 쌍이 완전해졌는가. */
        public boolean complete() {
            return pairs.stream().allMatch(PairBackfill::complete);
        }
    }
}
