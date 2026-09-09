package com.divurve.domain.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.divurve.domain.event.EconEventAdminService.RefreshResult;
import com.divurve.domain.event.EconEventAdminService.StatusResult;
import com.divurve.domain.event.OfficialEventIngestionService.IngestionReport;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * {@link EconEventAdminService} — 수동 적재와 현황 (이슈 #176).
 *
 * <p>핵심은 <b>실패를 값으로 낸다</b>는 것이다. 이 화면의 목적이 "왜 안 되는가" 를 보는 것인데
 * 예외를 그대로 던지면 500 과 스택트레이스만 남아 목적을 잃는다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("EconEventAdminService")
class EconEventAdminServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-09T04:10:00Z");

    @Mock
    private OfficialEventIngestionService ingestionService;
    @Mock
    private EconEventRepository repository;

    private EconEventAdminService service;

    @BeforeEach
    void setUp() {
        service = new EconEventAdminService(
                ingestionService, repository, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    @DisplayName("적재 집계를 그대로 낸다 — 성공 여부만 알려주면 이 화면의 목적이 사라진다")
    void 집계를_그대로_낸다() {
        when(ingestionService.ingest()).thenReturn(new IngestionReport(12, 5, 2, 3, 2));

        RefreshResult result = service.refresh();

        assertThat(result.report().scheduled()).isEqualTo(12);
        assertThat(result.report().inserted()).isEqualTo(5);
        assertThat(result.report().promoted()).isEqualTo(2);
        assertThat(result.report().skipped()).isEqualTo(3);
        assertThat(result.report().failedCalendars()).isEqualTo(2);
        assertThat(result.failureReason()).isNull();
        assertThat(result.refreshedAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("외부 호출이 터져도 예외를 던지지 않고 사유를 값으로 낸다")
    void 실패를_값으로_낸다() {
        when(ingestionService.ingest())
                .thenThrow(new IllegalStateException("FRED API key is not configured"));

        RefreshResult result = service.refresh();

        assertThat(result.report()).isNull();
        assertThat(result.failureReason()).isEqualTo("IllegalStateException");
        assertThat(result.refreshedAt()).isEqualTo(NOW);
    }

    /** ECOS·FRED URL 경로에 API 키가 들어 있다 — 외부 메시지를 그대로 노출하지 않는다(이슈 #74). */
    @Test
    @DisplayName("실패 사유에 외부 시스템 메시지를 담지 않는다")
    void 외부_메시지를_노출하지_않는다() {
        when(ingestionService.ingest()).thenThrow(
                new IllegalStateException("https://api.stlouisfed.org/fred?api_key=SECRET"));

        assertThat(service.refresh().failureReason()).doesNotContain("SECRET", "api_key");
    }

    @Test
    @DisplayName("현황은 출처를 나눠 낸다 — 신뢰도를 한 숫자로 합치지 않는다")
    void 출처를_나눠_낸다() {
        when(repository.statsBySourceKind()).thenReturn(List.of(
                stat("OFFICIAL_PARSER", 24, Instant.parse("2026-09-09T04:10:00Z"),
                        LocalDate.of(2026, 12, 15)),
                stat("DEMO_SAMPLE", 7, Instant.parse("2026-09-08T00:00:00Z"),
                        LocalDate.of(2026, 11, 20))));

        StatusResult status = service.status();

        assertThat(status.sources()).hasSize(2);
        assertThat(status.sources()).extracting(EconEventAdminService.SourceStatus::sourceKind)
                .containsExactly("OFFICIAL_PARSER", "DEMO_SAMPLE");
        assertThat(status.sources().get(0).total()).isEqualTo(24);
        assertThat(status.sources().get(0).lastEventDate()).isEqualTo(LocalDate.of(2026, 12, 15));
        assertThat(status.checkedAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("표가 비면 빈 목록 — 터지지 않는다")
    void 표가_비면_빈_목록이다() {
        when(repository.statsBySourceKind()).thenReturn(List.of());

        assertThat(service.status().sources()).isEmpty();
    }

    private static EconEventRepository.SourceKindStat stat(
            String kind, long total, Instant fetchedAt, LocalDate eventDate) {
        return new EconEventRepository.SourceKindStat() {
            @Override
            public String getSourceKind() {
                return kind;
            }

            @Override
            public long getTotal() {
                return total;
            }

            @Override
            public Instant getLastFetchedAt() {
                return fetchedAt;
            }

            @Override
            public LocalDate getLastEventDate() {
                return eventDate;
            }
        };
    }
}
