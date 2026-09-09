package com.divurve.domain.event;

import com.divurve.common.architecture.UseCase;
import com.divurve.domain.event.entity.EconEvent;
import com.divurve.domain.port.OfficialEventCalendarSource;
import com.divurve.domain.port.OfficialEventCalendarSource.OfficialEvent;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 공식 캘린더 일정을 {@code econ_events} 에 적재하는 배치 유스케이스 (이슈 #163).
 *
 * <p><b>LLM 을 거치지 않는다.</b> 구조화된 캘린더를 그대로 읽으므로 추출·환각 검증이 필요 없고,
 * {@code ai_call_logs} 에 남길 호출도 없다 — {@link EconEventIngestionService}(AI 추출 경로)와
 * 나란한 별개 경로다.
 *
 * <p><b>트랜잭션을 걸지 않는다.</b> {@link EconEventIngestionService} 와 같은 이유다 — 한 건의
 * 실패가 이미 저장된 다른 건을 되돌리면 안 된다(이슈 #74 제약 5). Spring Data JPA 기본 메서드가
 * 건별로 자체 트랜잭션을 열어 한 건 단위의 원자성은 그것으로 충분하다.
 *
 * <p><b>출처 우선순위</b> — 같은 {@code (event_date, region, title)} 이 이미 있을 때:
 * <ul>
 *   <li>기존이 {@code OFFICIAL_PARSER} 면 그대로 둔다 (같은 신뢰도끼리는 먼저 온 것을 남긴다)</li>
 *   <li>기존이 {@code AI_EXTRACTED}·{@code DEMO_SAMPLE} 이면 <b>공식으로 승격</b>한다</li>
 * </ul>
 * 승격이 없으면 신뢰도가 낮은 행이 먼저 자리를 잡았다는 이유로 공식 데이터가 버려진다.
 */
@UseCase
public class OfficialEventIngestionService {

    private static final Logger log = LoggerFactory.getLogger(OfficialEventIngestionService.class);

    /** 앞으로 며칠치를 읽는가. 확정 공표 일정이라 넉넉히 잡아도 값이 흔들리지 않는다. */
    static final int LOOKAHEAD_DAYS = 180;

    private final OfficialEventCalendarSource source;
    private final EconEventRepository repository;
    private final Clock clock;

    public OfficialEventIngestionService(
            OfficialEventCalendarSource source, EconEventRepository repository, Clock clock) {
        this.source = Objects.requireNonNull(source, "source");
        this.repository = Objects.requireNonNull(repository, "repository");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * 공식 캘린더를 읽어 적재한다.
     *
     * @return 처리 결과 집계
     */
    public IngestionReport ingest() {
        LocalDate today = LocalDate.now(clock);
        LocalDate until = today.plusDays(LOOKAHEAD_DAYS);
        Instant fetchedAt = clock.instant();

        // enum switch 로 세지 않는다 — 컴파일러가 넣는 암묵적 default 가 도달 불가 분기로 남아
        // 브랜치 커버리지에서 영원히 미커버가 된다(이슈 #40 과 같은 종류의 함정).
        Map<Outcome, Integer> counts = new EnumMap<>(Outcome.class);
        int scheduled = 0;
        int failedCalendars = 0;

        // 지표 하나씩 조회한다(이슈 #187). 캘린더 전체를 한 번에 받던 예전 방식은 관심 없는
        // 지표까지 수천 건을 받아 응답 상한에 잘렸고, 실제로 구간의 5분의 1만 들어왔다.
        for (OfficialEventCatalog.Entry entry : OfficialEventCatalog.entries()) {
            List<OfficialEvent> dates;
            try {
                dates = source.fetchScheduled(entry.calendarKey(), today, until);
            } catch (RuntimeException e) {
                // 지표 하나가 실패해도 나머지를 계속한다 — 한 지표 때문에 배치 전체가 죽지
                // 않는다(이슈 #74 제약과 같은 방향).
                log.warn("official_event_calendar_failed key={} title={} reason={}",
                        entry.calendarKey(), entry.title(), e.getMessage());
                failedCalendars++;
                continue;
            }
            scheduled += dates.size();
            for (OfficialEvent event : dates) {
                counts.merge(save(event, entry, fetchedAt), 1, Integer::sum);
            }
        }

        IngestionReport report = new IngestionReport(
                scheduled,
                counts.getOrDefault(Outcome.INSERTED, 0),
                counts.getOrDefault(Outcome.PROMOTED, 0),
                counts.getOrDefault(Outcome.SKIPPED, 0),
                failedCalendars);
        log.info("official_event_ingestion_completed scheduled={} inserted={} promoted={} "
                + "skipped={} failed_calendars={}",
            report.scheduled(), report.inserted(), report.promoted(),
            report.skipped(), report.failedCalendars());
        return report;
    }

    private Outcome save(OfficialEvent event, OfficialEventCatalog.Entry entry, Instant fetchedAt) {
        Optional<EconEvent> existing = repository.findByEventDateAndRegionAndTitle(
                event.date(), event.region(), entry.title());

        if (existing.isEmpty()) {
            repository.save(EconEvent.official(
                    event.date(), event.region(), entry.title(),
                    entry.impact(), event.sourceUrl(), fetchedAt));
            return Outcome.INSERTED;
        }

        EconEvent found = existing.get();
        if (found.isOfficial()) {
            return Outcome.SKIPPED;
        }
        found.promoteToOfficial(entry.impact(), event.sourceUrl(), fetchedAt);
        repository.save(found);
        return Outcome.PROMOTED;
    }

    private enum Outcome {
        INSERTED, PROMOTED, SKIPPED
    }

    /**
     * 적재 결과 집계.
     *
     * @param scheduled 캘린더가 준 일정 수
     * @param inserted  새로 저장한 수
     * @param promoted  낮은 신뢰도 행을 공식으로 승격한 수
     * @param skipped   이미 공식이라 그대로 둔 수
     * @param failedCalendars 조회가 실패한 지표 수. 나머지 지표는 그대로 진행했다
     */
    public record IngestionReport(
            int scheduled, int inserted, int promoted, int skipped, int failedCalendars) {
    }
}
