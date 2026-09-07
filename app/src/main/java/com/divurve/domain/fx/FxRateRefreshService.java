package com.divurve.domain.fx;

import com.divurve.common.architecture.UseCase;
import com.divurve.domain.port.ExternalDataCache;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * 환율을 지금 다시 받아온다 (이슈 #111). 관리자 콘솔의 "갱신" 버튼이 부르는 유스케이스다.
 *
 * <p>순서가 중요하다 — <b>캐시를 먼저 비우고</b> 조회한다. 반대로 하면 6시간 TTL 안에 있는 옛 값을
 * 다시 읽어 그대로 저장하고는 "갱신했다" 고 보고하게 된다.
 *
 * <p>비운 캐시 이름·쌍별 반영 건수·실패 사유·소요 시간을 전부 값으로 돌려준다. 이 화면의 존재
 * 이유가 "외부 연동이 정말 살아 있는가" 를 보는 것이므로, 성공/실패가 응답에 드러나야 한다.
 * 조용히 0건이 되는 것이 가장 나쁘다.
 *
 * <p><b>갱신 후 커버리지도 함께 싣는다 (이슈 #116).</b> "몇 건 반영했다" 는 갱신이 돌았다는
 * 사실일 뿐, 그 구간이 완전해졌는지는 말하지 않는다. 읽기 경로가 저장분을 신뢰하는 조건이
 * 완전성이므로, 갱신 직후 그 조건이 충족됐는지가 이 화면에서 바로 보여야 한다.
 */
@UseCase
public class FxRateRefreshService {

    /**
     * 환율 조회 캐시. {@code EcosFxRateProvider}(fx-latest)·{@code EcosFxRateHistoryProvider}
     * (fx-history)의 {@code @Cacheable} 이름과 정확히 같아야 한다.
     */
    static final List<String> FX_CACHE_NAMES = List.of("fx-latest", "fx-history");

    private final ExternalDataCache externalDataCache;
    private final FxRateIngestionService fxRateIngestionService;
    private final FxRateGapService fxRateGapService;
    private final Clock clock;

    public FxRateRefreshService(
            ExternalDataCache externalDataCache,
            FxRateIngestionService fxRateIngestionService,
            FxRateGapService fxRateGapService,
            Clock clock) {
        this.externalDataCache = Objects.requireNonNull(externalDataCache, "externalDataCache");
        this.fxRateIngestionService =
                Objects.requireNonNull(fxRateIngestionService, "fxRateIngestionService");
        this.fxRateGapService = Objects.requireNonNull(fxRateGapService, "fxRateGapService");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * 캐시를 비우고 ECOS 를 다시 조회해 {@code fx_rates} 에 반영한다.
     *
     * @param endDate              조회 끝 날짜 (포함)
     * @param lookbackCalendarDays 거슬러 올라갈 달력일 수
     */
    public RefreshReport refresh(LocalDate endDate, int lookbackCalendarDays) {
        Instant startedAt = Instant.now(clock);

        List<String> evicted = externalDataCache.evict(FX_CACHE_NAMES);
        FxRateIngestionService.IngestionReport ingestion =
                fxRateIngestionService.ingest(endDate, lookbackCalendarDays);

        // 적재 다음에 백필을 한 번 돌린다 (이슈 #116). 적재는 ECOS 가 준 날짜만 넣으므로 공휴일은
        // 영원히 빈칸으로 남는데, 그것을 부재로 확정하지 않으면 커버리지가 절대 완전해지지 않고
        // 읽기 경로도 저장분을 영영 신뢰하지 않는다. 재조회 대상은 구멍뿐이라 비용은 작다.
        LocalDate from = endDate.minusDays(lookbackCalendarDays);
        FxRateGapService.BackfillReport backfill =
                fxRateGapService.backfillStoredPairs(from, endDate);

        // 커버리지는 갱신이 대상으로 삼은 바로 그 구간을 본다 — 다른 구간을 보여 주면
        // "갱신했는데 왜 여전히 구멍인가" 의 답이 화면에 없다.
        List<FxRateGapService.PairCoverage> coverage =
                fxRateGapService.coverageOfStoredPairs(from, endDate);

        return new RefreshReport(
                evicted,
                ingestion.pairs(),
                ingestion.totalUpserted(),
                ingestion.hasFailure() || backfill.hasFailure(),
                backfill.totalFilled(),
                backfill.totalConfirmedAbsent(),
                coverage,
                startedAt,
                Duration.between(startedAt, Instant.now(clock)).toMillis());
    }

    /**
     * 갱신 결과.
     *
     * @param evictedCaches 실제로 비운 캐시 이름
     * @param pairs         쌍별 반영 결과
     * @param totalUpserted 반영된 행 수 합계
     * @param hasFailure          실패한 쌍이 하나라도 있는가 (적재·백필 통틀어)
     * @param backfilledDays      구멍을 다시 받아 채운 날 수 (이슈 #116)
     * @param confirmedAbsentDays 고시가 없다고 확정한 날 수 — 공휴일이 여기로 들어간다
     * @param coverage            갱신 구간의 쌍별 커버리지 — 갱신 후 완전해졌는지
     * @param refreshedAt         갱신을 시작한 시각
     * @param elapsedMs           소요 시간 (밀리초)
     */
    public record RefreshReport(
            List<String> evictedCaches,
            List<FxRateIngestionService.PairResult> pairs,
            int totalUpserted,
            boolean hasFailure,
            int backfilledDays,
            int confirmedAbsentDays,
            List<FxRateGapService.PairCoverage> coverage,
            Instant refreshedAt,
            long elapsedMs) {

        /** 갱신 구간이 모든 쌍에서 완전해졌는가 — 읽기 경로가 저장분을 신뢰하는 조건이다. */
        public boolean complete() {
            return coverage.stream().allMatch(FxRateGapService.PairCoverage::complete);
        }
    }
}
