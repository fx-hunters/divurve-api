package com.divurve.domain.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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
 * {@link OfficialEventIngestionService} 적재 규칙 (이슈 #163).
 *
 * <p>핵심은 <b>출처 우선순위</b>다 — 승격이 없으면 AI 추출분이나 시연용 행이 먼저 자리를
 * 잡았다는 이유로 공식 데이터가 버려진다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("OfficialEventIngestionService")
class OfficialEventIngestionServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 9, 9);
    private static final Instant NOW = TODAY.atStartOfDay().toInstant(ZoneOffset.UTC);
    private static final String CPI = "Consumer Price Index";
    private static final String CPI_TITLE = "미국 소비자물가지수(CPI) 발표";
    private static final LocalDate EVENT_DATE = LocalDate.of(2026, 9, 15);
    private static final String SOURCE_URL = "https://fred.stlouisfed.org/releases/10";

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

    private void given(OfficialEvent... events) {
        when(source.fetchScheduled(TODAY, TODAY.plusDays(180))).thenReturn(List.of(events));
    }

    private OfficialEvent cpi() {
        return new OfficialEvent(EVENT_DATE, "US", CPI, SOURCE_URL);
    }

    @Test
    @DisplayName("새 일정은 공식 출처로 저장한다")
    void 새_일정을_저장한다() {
        given(cpi());
        when(repository.findByEventDateAndRegionAndTitle(EVENT_DATE, "US", CPI_TITLE))
                .thenReturn(Optional.empty());

        IngestionReport report = service.ingest();

        ArgumentCaptor<EconEvent> saved = ArgumentCaptor.forClass(EconEvent.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getTitle()).isEqualTo(CPI_TITLE);
        assertThat(saved.getValue().getImpact()).isEqualTo((short) 3);
        assertThat(saved.getValue().getSourceUrl()).isEqualTo(SOURCE_URL);
        assertThat(saved.getValue().isOfficial()).isTrue();
        assertThat(report.inserted()).isEqualTo(1);
        assertThat(report.scheduled()).isEqualTo(1);
    }

    @Test
    @DisplayName("시연용 행은 공식으로 승격한다 — 낮은 신뢰도가 이기지 않는다")
    void 시연용_행을_승격한다() {
        given(cpi());
        EconEvent demo = EconEvent.extracted(
                EVENT_DATE, "US", CPI_TITLE, (short) 1, null, Instant.EPOCH);
        when(repository.findByEventDateAndRegionAndTitle(EVENT_DATE, "US", CPI_TITLE))
                .thenReturn(Optional.of(demo));

        IngestionReport report = service.ingest();

        assertThat(demo.isOfficial()).isTrue();
        assertThat(demo.getImpact()).isEqualTo((short) 3);
        assertThat(demo.getSourceUrl()).isEqualTo(SOURCE_URL);
        assertThat(demo.getFetchedAt()).isEqualTo(NOW);
        verify(repository).save(demo);
        assertThat(report.promoted()).isEqualTo(1);
        assertThat(report.inserted()).isZero();
    }

    @Test
    @DisplayName("이미 공식인 행은 그대로 둔다 — 같은 신뢰도끼리는 먼저 온 것을 남긴다")
    void 이미_공식이면_두다() {
        given(cpi());
        EconEvent official = EconEvent.official(
                EVENT_DATE, "US", CPI_TITLE, (short) 3, SOURCE_URL, Instant.EPOCH);
        when(repository.findByEventDateAndRegionAndTitle(EVENT_DATE, "US", CPI_TITLE))
                .thenReturn(Optional.of(official));

        IngestionReport report = service.ingest();

        verify(repository, never()).save(any());
        assertThat(official.getFetchedAt()).isEqualTo(Instant.EPOCH);
        assertThat(report.skipped()).isEqualTo(1);
    }

    @Test
    @DisplayName("대조표에 없는 이름은 저장하지 않는다 — 중요도를 지어내지 않는다")
    void 표에_없으면_저장하지_않는다() {
        given(new OfficialEvent(EVENT_DATE, "US", "Weekly Widget Report", SOURCE_URL));

        IngestionReport report = service.ingest();

        verify(repository, never()).save(any());
        assertThat(report.unknown()).isEqualTo(1);
        assertThat(report.inserted()).isZero();
    }

    @Test
    @DisplayName("캘린더가 비면 아무것도 하지 않는다")
    void 캘린더가_비면_아무것도_안_한다() {
        given();

        IngestionReport report = service.ingest();

        verify(repository, never()).save(any());
        assertThat(report.scheduled()).isZero();
    }
}
