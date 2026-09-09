package com.divurve.domain.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.divurve.domain.event.OfficialEventIngestionService.IngestionReport;
import com.divurve.domain.event.entity.EconEvent;
import com.divurve.domain.port.OfficialEventCalendarSource;
import com.divurve.domain.port.OfficialEventCalendarSource.OfficialEvent;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * {@link OfficialEventIngestionService} 적재 규칙 (이슈 #163, 이슈 #187 로 조회 단위 변경).
 *
 * <p>두 가지가 핵심이다. <b>출처 우선순위</b> — 승격이 없으면 AI 추출분이나 시연용 행이 먼저
 * 자리를 잡았다는 이유로 공식 데이터가 버려진다. <b>실패 격리</b> — 지표 하나가 실패해도
 * 나머지를 계속해야 배치 한 번이 통째로 날아가지 않는다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("OfficialEventIngestionService")
class OfficialEventIngestionServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 9, 9);
    private static final LocalDate UNTIL = TODAY.plusDays(180);
    private static final Instant NOW = TODAY.atStartOfDay().toInstant(ZoneOffset.UTC);
    private static final LocalDate EVENT_DATE = LocalDate.of(2026, 9, 15);
    private static final String SOURCE_URL = "https://fred.stlouisfed.org/release?rid=10";

    /** 첫 항목이 소비자물가지수다 — 이 테스트들은 그 하나에만 일정을 물린다. */
    private static final OfficialEventCatalog.Entry FIRST = OfficialEventCatalog.entries().get(0);

    @Mock
    private OfficialEventCalendarSource source;
    @Mock
    private EconEventRepository repository;

    private OfficialEventIngestionService service;

    @BeforeEach
    void setUp() {
        service = new OfficialEventIngestionService(
                source, repository, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    /** 첫 지표만 일정을 주고 나머지는 빈 목록 — 관심 있는 경로 하나만 남긴다. */
    private void givenOnlyFirstHasDate() {
        when(source.fetchScheduled(anyString(), eq(TODAY), eq(UNTIL))).thenReturn(List.of());
        when(source.fetchScheduled(eq(FIRST.calendarKey()), eq(TODAY), eq(UNTIL)))
                .thenReturn(List.of(new OfficialEvent(EVENT_DATE, "US", SOURCE_URL)));
    }

    @Test
    @DisplayName("표의 지표를 하나씩 조회한다 — 캘린더 전체를 받지 않는다")
    void 지표를_하나씩_조회한다() {
        when(source.fetchScheduled(anyString(), eq(TODAY), eq(UNTIL))).thenReturn(List.of());

        service.ingest();

        for (OfficialEventCatalog.Entry entry : OfficialEventCatalog.entries()) {
            verify(source).fetchScheduled(entry.calendarKey(), TODAY, UNTIL);
        }
    }

    @Test
    @DisplayName("새 일정은 표의 제목·중요도를 붙여 공식 출처로 저장한다")
    void 새_일정을_저장한다() {
        givenOnlyFirstHasDate();
        when(repository.findByEventDateAndRegionAndTitle(EVENT_DATE, "US", FIRST.title()))
                .thenReturn(Optional.empty());

        IngestionReport report = service.ingest();

        ArgumentCaptor<EconEvent> saved = ArgumentCaptor.forClass(EconEvent.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getTitle()).isEqualTo(FIRST.title());
        assertThat(saved.getValue().getImpact()).isEqualTo(FIRST.impact());
        assertThat(saved.getValue().getSourceUrl()).isEqualTo(SOURCE_URL);
        assertThat(saved.getValue().isOfficial()).isTrue();
        assertThat(report.inserted()).isEqualTo(1);
        assertThat(report.scheduled()).isEqualTo(1);
        assertThat(report.failedCalendars()).isZero();
    }

    @Test
    @DisplayName("낮은 신뢰도 행은 공식으로 승격한다 — 먼저 왔다고 이기지 않는다")
    void 낮은_신뢰도를_승격한다() {
        givenOnlyFirstHasDate();
        EconEvent demo = EconEvent.extracted(
                EVENT_DATE, "US", FIRST.title(), (short) 1, null, Instant.EPOCH);
        when(repository.findByEventDateAndRegionAndTitle(EVENT_DATE, "US", FIRST.title()))
                .thenReturn(Optional.of(demo));

        IngestionReport report = service.ingest();

        assertThat(demo.isOfficial()).isTrue();
        assertThat(demo.getImpact()).isEqualTo(FIRST.impact());
        assertThat(demo.getSourceUrl()).isEqualTo(SOURCE_URL);
        assertThat(demo.getFetchedAt()).isEqualTo(NOW);
        verify(repository).save(demo);
        assertThat(report.promoted()).isEqualTo(1);
    }

    @Test
    @DisplayName("이미 공식인 행은 그대로 둔다 — 같은 신뢰도끼리는 먼저 온 것을 남긴다")
    void 이미_공식이면_두다() {
        givenOnlyFirstHasDate();
        EconEvent official = EconEvent.official(
                EVENT_DATE, "US", FIRST.title(), FIRST.impact(), SOURCE_URL, Instant.EPOCH);
        when(repository.findByEventDateAndRegionAndTitle(EVENT_DATE, "US", FIRST.title()))
                .thenReturn(Optional.of(official));

        IngestionReport report = service.ingest();

        verify(repository, never()).save(any());
        assertThat(official.getFetchedAt()).isEqualTo(Instant.EPOCH);
        assertThat(report.skipped()).isEqualTo(1);
    }

    /** 지표 하나의 실패가 배치 전체를 죽이면, 한 번의 장애로 그날 일정이 통째로 비게 된다. */
    @Test
    @DisplayName("지표 하나가 실패해도 나머지를 계속한다")
    void 하나가_실패해도_계속한다() {
        when(source.fetchScheduled(anyString(), eq(TODAY), eq(UNTIL))).thenReturn(List.of());
        when(source.fetchScheduled(eq(FIRST.calendarKey()), eq(TODAY), eq(UNTIL)))
                .thenThrow(new IllegalStateException("FRED 5xx"));

        IngestionReport report = service.ingest();

        assertThat(report.failedCalendars()).isEqualTo(1);
        // 실패한 하나를 뺀 나머지는 모두 조회됐다.
        verify(source, org.mockito.Mockito.times(OfficialEventCatalog.entries().size()))
                .fetchScheduled(anyString(), eq(TODAY), eq(UNTIL));
    }

    @Test
    @DisplayName("캘린더가 아무 일정도 주지 않으면 저장하지 않는다")
    void 일정이_없으면_저장하지_않는다() {
        when(source.fetchScheduled(anyString(), eq(TODAY), eq(UNTIL))).thenReturn(List.of());

        IngestionReport report = service.ingest();

        verify(repository, never()).save(any());
        assertThat(report.scheduled()).isZero();
    }
}
