package com.divurve.domain.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.divurve.domain.event.EconEventIngestionService.IngestionReport;
import com.divurve.domain.ai.AiCallLogRecorder;
import com.divurve.domain.ai.AiCallQuota;
import com.divurve.domain.port.EconEventExtractor;
import com.divurve.domain.port.EconEventExtractor.ExtractedEvent;
import com.divurve.domain.port.EconEventExtractor.RawArticle;
import com.divurve.domain.port.RawArticleSource;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * {@link EconEventIngestionService} 검증 (이슈 #74). 정상 처리, 부분 실패, 중복 스킵,
 * 원문 처리 중 예외 발생 시 다음 원문 계속, 빈 소스를 확인한다. 실제 {@link EconEventValidator} 를
 * 그대로 써서 서비스가 검증 결과를 올바르게 소비하는지까지 확인한다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("EconEventIngestionService")
class EconEventIngestionServiceTest {

    private static final Instant FETCHED_AT = Instant.parse("2026-09-01T00:00:00Z");

    @Mock
    private RawArticleSource source;
    @Mock
    private EconEventExtractor extractor;
    @Mock
    private EconEventRepository repository;
    @Mock
    private AiCallLogRecorder aiCallLogRecorder;
    @Mock
    private AiCallQuota aiCallQuota;

    private EconEventIngestionService service;

    @BeforeEach
    void setUp() {
        service = new EconEventIngestionService(source, aiCallQuota, extractor, repository,
                new EconEventValidator(), aiCallLogRecorder,
                Clock.fixed(Instant.parse("2026-09-08T00:00:00Z"), ZoneOffset.UTC));
    }

    private static RawArticle article(String sourceUrl, String text) {
        return new RawArticle(sourceUrl, text, FETCHED_AT);
    }

    @Test
    @DisplayName("소스가 비어 있으면 모든 집계가 0이다")
    void emptySourceProducesEmptyReport() {
        when(source.fetchRecent()).thenReturn(List.of());

        IngestionReport report = service.ingest();

        assertThat(report).isEqualTo(new IngestionReport(0, 0, 0, 0, 0, 0, 0, 0));
    }

    @Test
    @DisplayName("정상 처리 — 유효한 후보를 모두 신규 저장한다")
    void validCandidatesAreAllInserted() {
        RawArticle article = article("https://news.example.com/1", "2026-09-07 FOMC 회의 예정");
        when(source.fetchRecent()).thenReturn(List.of(article));
        ExtractedEvent candidate = new ExtractedEvent("2026-09-07", "US", "FOMC 회의", 3);
        when(extractor.extract(article)).thenReturn(EconEventExtractor.ExtractOutcome.withoutLlm(List.of(candidate)));
        when(repository.existsByEventDateAndRegionAndTitle(
                LocalDate.of(2026, 9, 7), "US", "FOMC 회의")).thenReturn(false);

        IngestionReport report = service.ingest();

        assertThat(report).isEqualTo(new IngestionReport(1, 1, 1, 0, 0, 0, 0, 0));
        verify(repository, times(1)).save(any());
    }

    @Test
    @DisplayName("부분 실패 — 검증에 실패한 후보만 버리고 나머지는 저장한다")
    void invalidCandidateIsRejectedWhileOthersAreInserted() {
        RawArticle article = article(
                "https://news.example.com/2", "2026-09-07 FOMC 회의, 2026-09-08 CPI 발표 예정");
        when(source.fetchRecent()).thenReturn(List.of(article));
        ExtractedEvent valid = new ExtractedEvent("2026-09-07", "US", "FOMC 회의", 3);
        ExtractedEvent invalidImpact = new ExtractedEvent("2026-09-08", "US", "CPI 발표", 9);
        when(extractor.extract(article)).thenReturn(EconEventExtractor.ExtractOutcome.withoutLlm(List.of(valid, invalidImpact)));
        when(repository.existsByEventDateAndRegionAndTitle(
                LocalDate.of(2026, 9, 7), "US", "FOMC 회의")).thenReturn(false);

        IngestionReport report = service.ingest();

        assertThat(report).isEqualTo(new IngestionReport(1, 2, 1, 1, 0, 0, 0, 0));
        verify(repository, times(1)).save(any());
    }

    @Test
    @DisplayName("중복 — 이미 존재하는 이벤트는 저장하지 않고 duplicates 로 집계한다")
    void duplicateCandidateIsSkipped() {
        RawArticle article = article("https://news.example.com/3", "2026-09-07 FOMC 회의 예정");
        when(source.fetchRecent()).thenReturn(List.of(article));
        ExtractedEvent candidate = new ExtractedEvent("2026-09-07", "US", "FOMC 회의", 3);
        when(extractor.extract(article)).thenReturn(EconEventExtractor.ExtractOutcome.withoutLlm(List.of(candidate)));
        when(repository.existsByEventDateAndRegionAndTitle(
                LocalDate.of(2026, 9, 7), "US", "FOMC 회의")).thenReturn(true);

        IngestionReport report = service.ingest();

        assertThat(report).isEqualTo(new IngestionReport(1, 1, 0, 0, 1, 0, 0, 0));
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("한 원문에서 extractor 가 예외를 던져도 다음 원문은 계속 처리하고, "
            + "그 실패는 failedArticles 로 잡히며 rejected(후보 검증 탈락)와는 분리된다")
    void extractorFailureOnOneArticleDoesNotStopOthers() {
        RawArticle failingArticle = article("https://news.example.com/broken", "손상된 원문");
        RawArticle okArticle = article("https://news.example.com/ok", "2026-09-07 FOMC 회의 예정");
        when(source.fetchRecent()).thenReturn(List.of(failingArticle, okArticle));
        when(extractor.extract(failingArticle)).thenThrow(new IllegalStateException("파싱 실패"));
        ExtractedEvent candidate = new ExtractedEvent("2026-09-07", "US", "FOMC 회의", 3);
        when(extractor.extract(okArticle)).thenReturn(EconEventExtractor.ExtractOutcome.withoutLlm(List.of(candidate)));
        when(repository.existsByEventDateAndRegionAndTitle(
                LocalDate.of(2026, 9, 7), "US", "FOMC 회의")).thenReturn(false);

        IngestionReport report = service.ingest();

        assertThat(report.failedArticles())
                .as("원문 단위 실패는 failedArticles 로 집계된다")
                .isEqualTo(1);
        assertThat(report.rejected())
                .as("원문 단위 실패는 후보 검증 탈락(rejected)과 다르다")
                .isEqualTo(0);
        assertThat(report).isEqualTo(new IngestionReport(2, 1, 1, 0, 0, 1, 0, 0));
        verify(repository, times(1)).save(any());
    }

    // ── 쿼터·원문 길이 (이슈 #178) ───────────────────────────────

    /**
     * 이슈 #178 — 예전에는 추출 경로에 브레이크가 없어, 배치가 예산을 먹으면 아침에 접속한
     * 사용자가 템플릿 문장을 봤다. 배치가 먼저 멈춰야 나머지가 사용자 몫으로 남는다.
     */
    @Test
    @DisplayName("쿼터에 막히면 남은 원문을 건너뛰고 정상 종료한다 — 예외로 죽지 않는다")
    void 쿼터에_막히면_건너뛴다() {
        when(source.fetchRecent()).thenReturn(List.of(
                article("demo://a", "원문 A"), article("demo://b", "원문 B")));
        when(aiCallQuota.extractBlocked())
                .thenReturn(Optional.of(AiCallQuota.ExtractBlock.EXTRACT));

        IngestionReport report = service.ingest();

        assertThat(report.quotaSkipped()).isEqualTo(2);
        assertThat(report.extracted()).isZero();
        verifyNoInteractions(extractor);
    }

    @Test
    @DisplayName("원문마다 다시 본다 — 도중에 상한에 닿으면 그때 멈춘다")
    void 도중에_막히면_그때_멈춘다() {
        when(source.fetchRecent()).thenReturn(List.of(
                article("demo://a", "원문 A"), article("demo://b", "원문 B")));
        when(aiCallQuota.extractBlocked())
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(AiCallQuota.ExtractBlock.GLOBAL));
        when(extractor.extract(any()))
                .thenReturn(EconEventExtractor.ExtractOutcome.withoutLlm(List.of()));

        IngestionReport report = service.ingest();

        assertThat(report.quotaSkipped()).isEqualTo(1);
    }

    /** 상한이 미리보기에만 있던 시절에는 긴 기사가 그대로 프롬프트에 실렸다. */
    @Test
    @DisplayName("상한을 넘는 원문은 추출에 태우지 않는다")
    void 긴_원문은_태우지_않는다() {
        when(source.fetchRecent()).thenReturn(List.of(
                article("demo://long", "가".repeat(EconEventIngestionService.MAX_TEXT_LENGTH + 1))));
        when(aiCallQuota.extractBlocked()).thenReturn(Optional.empty());

        IngestionReport report = service.ingest();

        assertThat(report.tooLong()).isEqualTo(1);
        assertThat(report.extracted()).isZero();
        verifyNoInteractions(extractor);
    }

    /**
     * {@code RawArticle} 은 순수 record 라 크롤러가 텍스트 없는 원문을 줄 수 있다. 길이 검사가
     * 그것을 만나 터지면 배치 전체가 멈춘다 — 추출기까지 흘려보내 실패로 집계되게 둔다.
     */
    @Test
    @DisplayName("텍스트가 없는 원문도 배치를 멈추지 않는다")
    void 텍스트가_없어도_멈추지_않는다() {
        when(source.fetchRecent()).thenReturn(List.of(article("demo://empty", null)));
        when(aiCallQuota.extractBlocked()).thenReturn(Optional.empty());
        when(extractor.extract(any()))
                .thenThrow(new IllegalStateException("원문이 비었다"));

        IngestionReport report = service.ingest();

        assertThat(report.tooLong()).isZero();
        assertThat(report.failedArticles()).isEqualTo(1);
    }
}
