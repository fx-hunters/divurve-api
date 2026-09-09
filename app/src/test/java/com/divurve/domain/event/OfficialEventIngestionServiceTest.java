package com.divurve.domain.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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
 * {@link OfficialEventIngestionService} 적재 규칙 (이슈 #163, #187 로 조회 단위 변경,
 * #191 로 중앙은행 표 추가).
 *
 * <p>세 가지가 핵심이다. <b>출처 우선순위</b> — 승격이 없으면 AI 추출분이나 시연용 행이 먼저
 * 자리를 잡았다는 이유로 공식 데이터가 버려진다. <b>실패 격리</b> — 지표 하나가 실패해도
 * 나머지를 계속해야 배치 한 번이 통째로 날아가지 않는다. <b>표 소진</b> — 중앙은행 표가
 * 조회 구간을 다 덮지 못하면 그 사실이 드러나야 한다.
 *
 * <p>중앙은행 회의는 외부 호출 없이 {@link CentralBankMeetingCatalog} 에서 오므로 FRED 를
 * 어떻게 스텁하든 항상 함께 적재된다. 기대값을 숫자로 박지 않고 표에서 세는 이유다 — 표에
 * 줄을 더해도 테스트가 거짓으로 깨지지 않는다.
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

    /** 조회 구간 [TODAY, UNTIL] 안에 드는 중앙은행 회의 수. 표에서 직접 센다. */
    private static final int MEETINGS_IN_WINDOW = (int) CentralBankMeetingCatalog.meetings()
            .stream()
            .filter(m -> !m.date().isBefore(TODAY) && !m.date().isAfter(UNTIL))
            .count();

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

    /** 어떤 조회든 기존 행이 없다고 답한다. 개별 테스트가 필요한 키만 덮어쓴다. */
    private void givenNothingStored() {
        when(repository.findByEventDateAndRegionAndTitle(any(), anyString(), anyString()))
                .thenReturn(Optional.empty());
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
        givenNothingStored();
        when(source.fetchScheduled(anyString(), eq(TODAY), eq(UNTIL))).thenReturn(List.of());

        service.ingest();

        for (OfficialEventCatalog.Entry entry : OfficialEventCatalog.entries()) {
            verify(source).fetchScheduled(entry.calendarKey(), TODAY, UNTIL);
        }
    }

    @Test
    @DisplayName("새 일정은 표의 제목·중요도를 붙여 공식 출처로 저장한다")
    void 새_일정을_저장한다() {
        givenNothingStored();
        givenOnlyFirstHasDate();

        IngestionReport report = service.ingest();

        ArgumentCaptor<EconEvent> saved = ArgumentCaptor.forClass(EconEvent.class);
        verify(repository, times(MEETINGS_IN_WINDOW + 1)).save(saved.capture());
        EconEvent fromFred = saved.getAllValues().stream()
                .filter(e -> e.getEventDate().equals(EVENT_DATE))
                .findFirst()
                .orElseThrow();
        assertThat(fromFred.getTitle()).isEqualTo(FIRST.title());
        assertThat(fromFred.getImpact()).isEqualTo(FIRST.impact());
        assertThat(fromFred.getSourceUrl()).isEqualTo(SOURCE_URL);
        assertThat(fromFred.isOfficial()).isTrue();
        assertThat(report.inserted()).isEqualTo(MEETINGS_IN_WINDOW + 1);
        assertThat(report.scheduled()).isEqualTo(MEETINGS_IN_WINDOW + 1);
        assertThat(report.failedCalendars()).isZero();
    }

    @Test
    @DisplayName("낮은 신뢰도 행은 공식으로 승격한다 — 먼저 왔다고 이기지 않는다")
    void 낮은_신뢰도를_승격한다() {
        givenNothingStored();
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
        givenNothingStored();
        givenOnlyFirstHasDate();
        EconEvent official = EconEvent.official(
                EVENT_DATE, "US", FIRST.title(), FIRST.impact(), SOURCE_URL, Instant.EPOCH);
        when(repository.findByEventDateAndRegionAndTitle(EVENT_DATE, "US", FIRST.title()))
                .thenReturn(Optional.of(official));

        IngestionReport report = service.ingest();

        assertThat(official.getFetchedAt()).isEqualTo(Instant.EPOCH);
        assertThat(report.skipped()).isEqualTo(1);
    }

    /** 지표 하나의 실패가 배치 전체를 죽이면, 한 번의 장애로 그날 일정이 통째로 비게 된다. */
    @Test
    @DisplayName("지표 하나가 실패해도 나머지를 계속한다")
    void 하나가_실패해도_계속한다() {
        givenNothingStored();
        when(source.fetchScheduled(anyString(), eq(TODAY), eq(UNTIL))).thenReturn(List.of());
        when(source.fetchScheduled(eq(FIRST.calendarKey()), eq(TODAY), eq(UNTIL)))
                .thenThrow(new IllegalStateException("FRED 5xx"));

        IngestionReport report = service.ingest();

        assertThat(report.failedCalendars()).isEqualTo(1);
        // 실패한 하나를 뺀 나머지는 모두 조회됐다.
        verify(source, times(OfficialEventCatalog.entries().size()))
                .fetchScheduled(anyString(), eq(TODAY), eq(UNTIL));
        // FRED 가 아무것도 주지 않아도 중앙은행 표는 그대로 적재된다.
        assertThat(report.inserted()).isEqualTo(MEETINGS_IN_WINDOW);
    }

    @Test
    @DisplayName("FRED 가 일정을 주지 않아도 중앙은행 회의는 적재한다 — 출처가 둘이다")
    void 중앙은행_표는_따로_들어온다() {
        givenNothingStored();
        when(source.fetchScheduled(anyString(), eq(TODAY), eq(UNTIL))).thenReturn(List.of());

        IngestionReport report = service.ingest();

        assertThat(report.scheduled()).isEqualTo(MEETINGS_IN_WINDOW);
        ArgumentCaptor<EconEvent> saved = ArgumentCaptor.forClass(EconEvent.class);
        verify(repository, times(MEETINGS_IN_WINDOW)).save(saved.capture());
        assertThat(saved.getAllValues())
                .allSatisfy(e -> {
                    assertThat(e.isOfficial()).isTrue();
                    assertThat(e.getEventDate()).isBetween(TODAY, UNTIL);
                    assertThat(e.getSourceUrl()).startsWith("https://");
                });
        assertThat(saved.getAllValues())
                .extracting(EconEvent::getRegion)
                .contains("US", "EU", "JP", "KR");
    }

    /** 구간 밖 회의까지 넣으면 지난 일정이 되살아나고 화면이 오래된 값으로 채워진다. */
    @Test
    @DisplayName("조회 구간 밖의 회의는 넣지 않는다 — 지난 회의도, 너무 먼 회의도")
    void 구간_밖_회의는_넣지_않는다() {
        givenNothingStored();
        when(source.fetchScheduled(anyString(), eq(TODAY), eq(UNTIL))).thenReturn(List.of());

        service.ingest();

        ArgumentCaptor<EconEvent> saved = ArgumentCaptor.forClass(EconEvent.class);
        verify(repository, times(MEETINGS_IN_WINDOW)).save(saved.capture());
        // 표에는 구간보다 이른 회의와 늦은 회의가 모두 들어 있다 — 둘 다 걸러졌다는 뜻이다.
        assertThat(CentralBankMeetingCatalog.meetings())
                .anySatisfy(m -> assertThat(m.date()).isBefore(TODAY))
                .anySatisfy(m -> assertThat(m.date()).isAfter(UNTIL));
        assertThat(saved.getAllValues()).hasSizeLessThan(
                CentralBankMeetingCatalog.meetings().size());
    }

    @Test
    @DisplayName("표가 조회 구간을 다 덮지 못하면 그 한계를 결과에 담는다")
    void 표의_한계를_담는다() {
        givenNothingStored();
        when(source.fetchScheduled(anyString(), eq(TODAY), eq(UNTIL))).thenReturn(List.of());

        IngestionReport report = service.ingest();

        assertThat(report.centralBankCalendarThrough())
                .isEqualTo(CentralBankMeetingCatalog.coveredThrough())
                .isBefore(UNTIL);
    }

    /** 표가 구간을 다 덮는 정상 상태에서는 경고 경로를 타지 않는다. */
    @Test
    @DisplayName("표가 조회 구간을 다 덮으면 경고 없이 지나간다")
    void 표가_구간을_덮으면_조용하다() {
        LocalDate early = LocalDate.of(2026, 1, 5);
        OfficialEventIngestionService earlyService = new OfficialEventIngestionService(
                source, repository,
                Clock.fixed(early.atStartOfDay().toInstant(ZoneOffset.UTC), ZoneOffset.UTC));
        when(source.fetchScheduled(anyString(), any(), any())).thenReturn(List.of());
        when(repository.findByEventDateAndRegionAndTitle(any(), anyString(), anyString()))
                .thenReturn(Optional.empty());

        IngestionReport report = earlyService.ingest();

        assertThat(report.centralBankCalendarThrough())
                .isAfterOrEqualTo(early.plusDays(180));
    }

    @Test
    @DisplayName("캘린더가 아무 일정도 주지 않으면 FRED 쪽으로는 저장하지 않는다")
    void 일정이_없으면_저장하지_않는다() {
        givenNothingStored();
        when(source.fetchScheduled(anyString(), eq(TODAY), eq(UNTIL))).thenReturn(List.of());

        service.ingest();

        ArgumentCaptor<EconEvent> saved = ArgumentCaptor.forClass(EconEvent.class);
        verify(repository, times(MEETINGS_IN_WINDOW)).save(saved.capture());
        assertThat(saved.getAllValues())
                .noneSatisfy(e -> assertThat(e.getEventDate()).isEqualTo(EVENT_DATE));
    }

    @Test
    @DisplayName("생성자는 협력자가 null 이면 실패한다")
    void null이면_실패한다() {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        assertThat(catchNpe(() -> new OfficialEventIngestionService(null, repository, clock)))
                .isTrue();
        assertThat(catchNpe(() -> new OfficialEventIngestionService(source, null, clock)))
                .isTrue();
        assertThat(catchNpe(() -> new OfficialEventIngestionService(source, repository, null)))
                .isTrue();
        verify(repository, never()).save(any());
    }

    private static boolean catchNpe(Runnable runnable) {
        try {
            runnable.run();
            return false;
        } catch (NullPointerException e) {
            return true;
        }
    }
}
