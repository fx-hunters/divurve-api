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
    private final Clock clock;

    public FxRateRefreshService(
            ExternalDataCache externalDataCache,
            FxRateIngestionService fxRateIngestionService,
            Clock clock) {
        this.externalDataCache = Objects.requireNonNull(externalDataCache, "externalDataCache");
        this.fxRateIngestionService =
                Objects.requireNonNull(fxRateIngestionService, "fxRateIngestionService");
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

        return new RefreshReport(
                evicted,
                ingestion.pairs(),
                ingestion.totalUpserted(),
                ingestion.hasFailure(),
                startedAt,
                Duration.between(startedAt, Instant.now(clock)).toMillis());
    }

    /**
     * 갱신 결과.
     *
     * @param evictedCaches 실제로 비운 캐시 이름
     * @param pairs         쌍별 반영 결과
     * @param totalUpserted 반영된 행 수 합계
     * @param hasFailure    실패한 쌍이 하나라도 있는가
     * @param refreshedAt   갱신을 시작한 시각
     * @param elapsedMs     소요 시간 (밀리초)
     */
    public record RefreshReport(
            List<String> evictedCaches,
            List<FxRateIngestionService.PairResult> pairs,
            int totalUpserted,
            boolean hasFailure,
            Instant refreshedAt,
            long elapsedMs) {
    }
}
