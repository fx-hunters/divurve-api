package com.divurve.infra.scheduler;

import com.divurve.common.architecture.ExternalAdapter;
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
 */
@ExternalAdapter
@Component
@ConditionalOnProperty(
        prefix = "app.external.ecos", name = "ingest-schedule-enabled", havingValue = "true")
public class FxRateIngestionScheduler {

    private static final Logger log = LoggerFactory.getLogger(FxRateIngestionScheduler.class);

    private final FxRateIngestionService ingestionService;
    private final int lookbackCalendarDays;
    private final Clock clock;

    public FxRateIngestionScheduler(
            FxRateIngestionService ingestionService,
            @Value("${app.external.ecos.ingest-lookback-days:14}") int lookbackCalendarDays,
            Clock clock) {
        this.ingestionService = Objects.requireNonNull(ingestionService, "ingestionService");
        this.lookbackCalendarDays = lookbackCalendarDays;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** 기본 KST 09:30 — ECOS 고시 이후다. */
    @Scheduled(cron = "${app.external.ecos.ingest-cron:0 30 9 * * *}", zone = "Asia/Seoul")
    public void ingest() {
        try {
            FxRateIngestionService.IngestionReport report =
                    ingestionService.ingest(LocalDate.now(clock), lookbackCalendarDays);
            if (report.hasFailure()) {
                log.warn("환율 적재 일부 실패: upserted={}, pairs={}",
                        report.totalUpserted(), report.pairs());
            } else {
                log.info("환율 적재 완료: upserted={}", report.totalUpserted());
            }
        } catch (RuntimeException e) {
            log.error("환율 적재 배치 실패", e);
        }
    }
}
