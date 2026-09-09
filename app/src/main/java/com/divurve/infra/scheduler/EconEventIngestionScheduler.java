package com.divurve.infra.scheduler;

import com.divurve.common.architecture.ExternalAdapter;
import com.divurve.domain.event.EconEventIngestionService;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * 경제 이벤트 추출 파이프라인을 주기적으로 돌리는 배치 스케줄러 (이슈 #74).
 *
 * <p><b>레이어 어노테이션을 {@link ExternalAdapter} 로 정한 근거</b> —
 * {@code LayerArchitectureTest.레이어_의존_방향} 은 {@code whereLayer("External")
 * .mayOnlyBeAccessedByLayers("UseCase")} 로 되어 있다. 이 ArchUnit API 는 <b>그 레이어를
 * 누가 호출할 수 있는지(incoming)</b> 만 제한하고, <b>그 레이어가 무엇을 호출하는지(outgoing)</b>
 * 는 전혀 제한하지 않는다 — 실제로 {@code UseCase} 레이어에는 어떤 아웃바운드 제약도 없다.
 * 이 스케줄러는 아무도 호출하지 않는 진입점(트리거)이고, {@link EconEventIngestionService}
 * (UseCase)를 호출하기만 하므로 "External → UseCase" 방향은 이 규칙을 위반하지 않는다.
 * 물리적 위치(infra 패키지, 배치 어댑터)와 CLAUDE.md 3장의 "infra/ ... scheduler/" 분류에도
 * 맞아 {@code @ExternalAdapter} 를 붙였다. 어노테이션 자체를 생략하면(레이어 미부착) CLAUDE.md
 * 4장이 "누락 자체가 리뷰 반려 대상"으로 규정하므로 그 선택지는 배제했다.
 *
 * <p><b>실패해도 서비스 기동/응답에 영향 없어야 한다</b>(이슈 #74 제약) — {@link #ingest()} 는
 * {@code EconEventIngestionService#ingest()} 가 던지는 모든 예외를 잡아 로그로만 남긴다.
 * 스케줄러 스레드에서 예외가 전파되면 다음 트리거부터 스케줄이 통째로 멈출 수 있어, 배치 한 번의
 * 실패가 이후 모든 실행을 막는 상황을 피한다.
 *
 * <p>기본은 꺼짐 — {@code app.external.anthropic.extract-schedule-enabled=true} 일 때만
 * 이 빈이 만들어진다({@link com.divurve.infra.config.SchedulingConfig} 가 {@code @EnableScheduling}
 * 자체도 같은 조건으로 켠다).
 */
@ExternalAdapter
@ConditionalOnProperty(
    prefix = "app.external.anthropic", name = "extract-schedule-enabled", havingValue = "true")
public class EconEventIngestionScheduler {

    private static final Logger log = LoggerFactory.getLogger(EconEventIngestionScheduler.class);

    private final EconEventIngestionService ingestionService;

    public EconEventIngestionScheduler(EconEventIngestionService ingestionService) {
        this.ingestionService = Objects.requireNonNull(ingestionService, "ingestionService");
    }

    /**
     * 배치 진입점. 주기는 {@code app.external.anthropic.extract-cron} 로 뺀다.
     * 예외를 삼키고 로그만 남긴다 — 스케줄러가 애플리케이션을 죽이면 안 된다.
     */
    @Scheduled(cron = "${app.external.anthropic.extract-cron}")
    public void ingest() {
        try {
            EconEventIngestionService.IngestionReport report = ingestionService.ingest();
            log.info("econ_event_ingestion_completed articles={} extracted={} inserted={} "
                    + "rejected={} duplicates={} failed_articles={} quota_skipped={} too_long={}",
                report.articles(), report.extracted(), report.inserted(), report.rejected(),
                report.duplicates(), report.failedArticles(),
                report.quotaSkipped(), report.tooLong());
        } catch (RuntimeException e) {
            log.error("econ_event_ingestion_failed message={}", e.getMessage(), e);
        }
    }
}
