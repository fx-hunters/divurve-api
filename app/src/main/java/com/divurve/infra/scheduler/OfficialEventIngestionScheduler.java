package com.divurve.infra.scheduler;

import com.divurve.common.architecture.ExternalAdapter;
import com.divurve.domain.event.OfficialEventIngestionService;
import com.divurve.domain.event.OfficialEventIngestionService.IngestionReport;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * 공식 캘린더 적재 배치 스케줄러 (이슈 #163).
 *
 * <p>레이어 어노테이션 근거는 {@link EconEventIngestionScheduler} 와 같다 — 아무도 호출하지 않는
 * 진입점이고 UseCase 를 호출하기만 하므로 {@code External → UseCase} 방향은 규칙 위반이 아니다.
 *
 * <p><b>주기는 낮게 잡는다.</b> 확정 공표 일정은 자주 바뀌지 않는다 — 매시 도는 AI 추출 배치와
 * 달리 하루 한 번이면 충분하고, 외부 호출을 아낀다.
 *
 * <p>기본은 꺼짐 — {@code app.external.fred.calendar-schedule-enabled=true} 일 때만 이 빈이
 * 만들어진다({@link com.divurve.infra.config.SchedulingConfig} 의 "작업별로 각자 끈다" 규약).
 * 실패해도 다음 트리거가 멈추지 않도록 예외를 삼키고 로그만 남긴다.
 */
@ExternalAdapter
@ConditionalOnProperty(
    prefix = "app.external.fred", name = "calendar-schedule-enabled", havingValue = "true")
public class OfficialEventIngestionScheduler {

    private static final Logger log =
            LoggerFactory.getLogger(OfficialEventIngestionScheduler.class);

    private final OfficialEventIngestionService ingestionService;

    public OfficialEventIngestionScheduler(OfficialEventIngestionService ingestionService) {
        this.ingestionService = Objects.requireNonNull(ingestionService, "ingestionService");
    }

    /** 배치 진입점. 주기는 {@code app.external.fred.calendar-cron} 으로 뺀다. */
    @Scheduled(cron = "${app.external.fred.calendar-cron}")
    public void ingest() {
        try {
            IngestionReport report = ingestionService.ingest();
            log.info("official_event_schedule_completed scheduled={} inserted={} promoted={} "
                    + "skipped={} failed_calendars={}",
                report.scheduled(), report.inserted(), report.promoted(),
                report.skipped(), report.failedCalendars());
        } catch (RuntimeException e) {
            log.error("official_event_schedule_failed message={}", e.getMessage(), e);
        }
    }
}
