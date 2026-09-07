package com.divurve.domain.macro;

import com.divurve.common.architecture.UseCase;
import com.divurve.common.exception.InvalidRequestException;
import com.divurve.domain.port.ExternalDataCache;
import com.divurve.domain.port.MacroIndicatorProvider;
import com.divurve.domain.port.MacroSnapshot;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * FRED 거시지표를 지금 다시 받아온다 (이슈 #111). 관리자 콘솔의 외부 연동 점검용이다.
 *
 * <p><b>저장하지 않는다.</b> 거시지표를 담을 테이블이 아직 없고, 이 호출의 목적은 적재가 아니라
 * "FRED 연동이 살아 있는가" 를 눈으로 확인하는 것이다. 값은 응답으로만 나간다.
 *
 * <p><b>이 서비스가 {@code FredMacroProvider} 의 첫 프로덕션 호출처다.</b> 지금까지
 * {@link MacroIndicatorProvider} 를 주입받는 곳이 없어 어댑터가 실제로 도는지 확인된 적이 없다.
 *
 * <p>그렇다고 {@code ExternalDataStatus.sources()} 에 FRED 를 넣지 않는다 — 관리자 점검 응답은
 * 사용자 화면의 수치가 아니므로, 어떤 응답의 출처도 되지 않는다(FR-CM-10). 거시지표가 실제 계산에
 * 들어가는 시점에 그 판단을 다시 한다.
 */
@UseCase
public class MacroRefreshService {

    private static final Logger log = LoggerFactory.getLogger(MacroRefreshService.class);

    /** {@code FredMacroProvider} 의 {@code @Cacheable} 이름과 정확히 같아야 한다. */
    static final List<String> MACRO_CACHE_NAMES = List.of("macro-latest");

    /** 조회 시리즈 상한. 관리자 점검용이므로 한 번에 몇 개면 충분하다. */
    static final int MAX_SERIES = 10;

    private final ExternalDataCache externalDataCache;
    private final MacroIndicatorProvider macroIndicatorProvider;
    private final Clock clock;

    public MacroRefreshService(
            ExternalDataCache externalDataCache,
            MacroIndicatorProvider macroIndicatorProvider,
            Clock clock) {
        this.externalDataCache = Objects.requireNonNull(externalDataCache, "externalDataCache");
        this.macroIndicatorProvider =
                Objects.requireNonNull(macroIndicatorProvider, "macroIndicatorProvider");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * 캐시를 비우고 지정한 시리즈를 다시 조회한다.
     *
     * @param seriesIds FRED 시리즈 id (예 {@code DGS10})
     * @throws InvalidRequestException 시리즈가 비었거나 상한을 넘은 경우 (400)
     */
    public MacroRefreshReport refresh(List<String> seriesIds) {
        Objects.requireNonNull(seriesIds, "seriesIds");
        if (seriesIds.isEmpty()) {
            throw new InvalidRequestException("조회할 시리즈를 지정해야 합니다.", "series_ids");
        }
        if (seriesIds.size() > MAX_SERIES) {
            throw new InvalidRequestException(
                    "시리즈는 한 번에 " + MAX_SERIES + "개까지입니다.", "series_ids");
        }

        Instant startedAt = Instant.now(clock);
        List<String> evicted = externalDataCache.evict(MACRO_CACHE_NAMES);

        List<SeriesResult> results = new ArrayList<>();
        for (String seriesId : seriesIds) {
            results.add(fetchOne(seriesId));
        }

        return new MacroRefreshReport(
                evicted,
                results,
                startedAt,
                Duration.between(startedAt, Instant.now(clock)).toMillis());
    }

    private SeriesResult fetchOne(String seriesId) {
        try {
            MacroSnapshot snapshot = macroIndicatorProvider.fetchLatest(seriesId);
            return new SeriesResult(seriesId, snapshot, null);
        } catch (RuntimeException e) {
            // 한 시리즈의 실패가 나머지를 막지 않는다. 사유는 값으로 남겨 화면에 보이게 한다.
            log.warn("거시지표 조회 실패: seriesId={}", seriesId, e);
            return new SeriesResult(seriesId, null, e.getMessage());
        }
    }

    /**
     * 갱신 결과.
     *
     * @param evictedCaches 실제로 비운 캐시 이름
     * @param series        시리즈별 결과
     * @param refreshedAt   갱신을 시작한 시각
     * @param elapsedMs     소요 시간 (밀리초)
     */
    public record MacroRefreshReport(
            List<String> evictedCaches,
            List<SeriesResult> series,
            Instant refreshedAt,
            long elapsedMs) {
    }

    /**
     * 시리즈 하나의 조회 결과.
     *
     * @param seriesId      FRED 시리즈 id
     * @param snapshot      조회된 값. 실패면 {@code null}
     * @param failureReason 실패 사유. 성공이면 {@code null}
     */
    public record SeriesResult(String seriesId, MacroSnapshot snapshot, String failureReason) {
    }
}
