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
 *
 * <p><b>입구가 둘이다</b>(이슈 #191) — 지표 발표 일정은 FRED 를 호출해 받고, 중앙은행 회의
 * 일정은 {@link CentralBankMeetingCatalog} 에서 읽는다. FRED 에는 회의체 일정이 없기
 * 때문이며, 저장 경로는 하나로 합쳐 두 출처가 같은 중복·승격 규칙을 받게 한다.
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
                counts.merge(
                        save(event.date(), event.region(), entry.title(), entry.impact(),
                                event.sourceUrl(), fetchedAt),
                        1, Integer::sum);
            }
        }

        // 중앙은행 회의는 FRED 가 주지 않으므로 domain 의 고정 표에서 온다(이슈 #191).
        // 외부 호출이 없어 실패할 것이 없고, 그래서 failedCalendars 에 잡히지 않는다.
        for (CentralBankMeetingCatalog.Meeting meeting : CentralBankMeetingCatalog.meetings()) {
            if (meeting.date().isBefore(today) || meeting.date().isAfter(until)) {
                continue;
            }
            scheduled++;
            counts.merge(
                    save(meeting.date(), meeting.region(), meeting.title(), meeting.impact(),
                            meeting.sourceUrl(), fetchedAt),
                    1, Integer::sum);
        }

        LocalDate coveredThrough = CentralBankMeetingCatalog.coveredThrough();
        if (coveredThrough.isBefore(until)) {
            // 표가 조회 구간을 다 덮지 못한다 — 그 뒤 구간은 회의 일정이 통째로 비어 있다는
            // 뜻이다. 표를 갱신하라는 신호이며, 조용히 비는 것을 막는 유일한 장치다.
            log.warn("central_bank_calendar_expiring covered_through={} requested_until={}",
                    coveredThrough, until);
        }

        IngestionReport report = new IngestionReport(
                scheduled,
                counts.getOrDefault(Outcome.INSERTED, 0),
                counts.getOrDefault(Outcome.PROMOTED, 0),
                counts.getOrDefault(Outcome.SKIPPED, 0),
                failedCalendars,
                coveredThrough);
        log.info("official_event_ingestion_completed scheduled={} inserted={} promoted={} "
                + "skipped={} failed_calendars={} central_bank_calendar_through={}",
            report.scheduled(), report.inserted(), report.promoted(),
            report.skipped(), report.failedCalendars(), report.centralBankCalendarThrough());
        return report;
    }

    private Outcome save(LocalDate date, String region, String title, short impact,
            String sourceUrl, Instant fetchedAt) {
        Optional<EconEvent> existing =
                repository.findByEventDateAndRegionAndTitle(date, region, title);

        if (existing.isEmpty()) {
            repository.save(
                    EconEvent.official(date, region, title, impact, sourceUrl, fetchedAt));
            return Outcome.INSERTED;
        }

        EconEvent found = existing.get();
        if (found.isOfficial()) {
            return Outcome.SKIPPED;
        }
        found.promoteToOfficial(impact, sourceUrl, fetchedAt);
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
     * @param centralBankCalendarThrough 중앙은행 회의 표가 유효한 마지막 날. 이 날짜가 조회
     *                                   구간 끝보다 앞이면 그 뒤는 회의 일정이 비어 있다
     */
    public record IngestionReport(
            int scheduled, int inserted, int promoted, int skipped, int failedCalendars,
            LocalDate centralBankCalendarThrough) {
    }
}
