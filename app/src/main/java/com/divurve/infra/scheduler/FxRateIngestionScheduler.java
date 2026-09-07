package com.divurve.infra.scheduler;

import com.divurve.common.architecture.ExternalAdapter;
import com.divurve.domain.fx.FxRateGapService;
import com.divurve.domain.fx.FxRateIngestionService;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * ECOS 일별 종가를 주기적으로 {@code fx_rates} 에 적재한다 (이슈 #111).
 *
 * <p>이 클래스는 트리거일 뿐이고 판단·저장은 {@link FxRateIngestionService} 가 한다
 * ({@code EconEventIngestionScheduler} 와 같은 구조).
 *
 * <p><b>{@code zone} 을 명시하는 이유</b> — {@code @Scheduled} 는 {@code Clock} 빈이 아니라 JVM
 * 기본 타임존을 쓴다. 컨테이너가 UTC 로 뜨면(Docker·Render 기본) cron 이 9시간 어긋나 그날 ECOS
 * 갱신 전에 돌게 된다. ECOS 는 KST 영업일 오전에 고시하므로 시각이 어긋나면 그날 값을 못 받는다.
 *
 * <p>예외를 삼키고 로그만 남긴다 — 배치 한 번의 실패가 다음 트리거까지 죽이면 안 된다.
 * 실패한 통화쌍의 사유는 이미 리포트에 값으로 담겨 있으므로 함께 로그로 남긴다.
 *
 * <p><b>적재 다음에 백필을 한 번 더 돌린다 (이슈 #116).</b> 적재는 ECOS 가 준 날짜만 넣으므로
 * 공휴일은 영원히 빈칸으로 남는다. 그것을 {@code fx_rate_absences} 에 부재로 확정하지 않으면
 * 커버리지가 절대 완전해지지 않고, 계산 경로도 저장분을 영영 신뢰하지 않아 이 배치가
 * 무의미해진다. 재조회 대상은 구멍뿐이라 평상시 추가 호출은 0~1회다.
 *
 * <p>구멍이 남으면 {@code WARN} 으로 구간을 그대로 남긴다 — 배치가 조용히 반쪽만 도는 것이
 * 가장 나쁘고, 읽기 경로는 그 상태에서 말없이 ECOS 로 넘어가기 때문에 로그가 유일한 신호다.
 */
@ExternalAdapter
@Component
@ConditionalOnProperty(
        prefix = "app.external.ecos", name = "ingest-schedule-enabled", havingValue = "true")
public class FxRateIngestionScheduler {

    private static final Logger log = LoggerFactory.getLogger(FxRateIngestionScheduler.class);

    private final FxRateIngestionService ingestionService;
    private final FxRateGapService gapService;
    private final int lookbackCalendarDays;
    private final Clock clock;

    public FxRateIngestionScheduler(
            FxRateIngestionService ingestionService,
            FxRateGapService gapService,
            @Value("${app.external.ecos.ingest-lookback-days:14}") int lookbackCalendarDays,
            Clock clock) {
        this.ingestionService = Objects.requireNonNull(ingestionService, "ingestionService");
        this.gapService = Objects.requireNonNull(gapService, "gapService");
        this.lookbackCalendarDays = lookbackCalendarDays;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** 기본 KST 09:30 — ECOS 고시 이후다. */
    @Scheduled(cron = "${app.external.ecos.ingest-cron:0 30 9 * * *}", zone = "Asia/Seoul")
    public void ingest() {
        LocalDate endDate = LocalDate.now(clock);
        try {
            FxRateIngestionService.IngestionReport report =
                    ingestionService.ingest(endDate, lookbackCalendarDays);
            if (report.hasFailure()) {
                log.warn("환율 적재 일부 실패: upserted={}, pairs={}",
                        report.totalUpserted(), report.pairs());
            } else {
                log.info("환율 적재 완료: upserted={}", report.totalUpserted());
            }
        } catch (RuntimeException e) {
            log.error("환율 적재 배치 실패", e);
        }
        backfill(endDate);
    }

    /**
     * 적재 구간의 구멍을 메우고 남은 구멍을 로그로 알린다.
     *
     * <p>적재가 실패했더라도 돌린다 — 적재가 죽은 그 구간이야말로 구멍이 생기는 자리이고,
     * 여기서 다시 받아 보는 것이 마지막 기회다.
     */
    private void backfill(LocalDate endDate) {
        try {
            FxRateGapService.BackfillReport report = gapService.backfillStoredPairs(
                    endDate.minusDays(lookbackCalendarDays), endDate);
            if (report.complete() && !report.hasFailure()) {
                log.info("환율 구멍 없음: filled={}, confirmedAbsent={}",
                        report.totalFilled(), report.totalConfirmedAbsent());
                return;
            }
            // 남은 구멍은 ECOS 도 답하지 못했거나 우리가 못 받은 구간이다. 계산 경로는 이 상태에서
            // 조용히 실시간 조회로 넘어가므로, 이 로그가 없으면 아무도 눈치채지 못한다.
            log.warn("환율 구멍 잔존: filled={}, confirmedAbsent={}, pairs={}",
                    report.totalFilled(), report.totalConfirmedAbsent(), report.pairs());
        } catch (RuntimeException e) {
            log.error("환율 구멍 백필 실패", e);
        }
    }
}
