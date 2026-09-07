package com.divurve.domain.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.divurve.common.exception.InvalidRequestException;
import com.divurve.domain.port.EconEventExtractor;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * {@link EconEventExtractPreviewService} — 저장 없는 추출 미리보기.
 *
 * <p>고정하는 것은 셋이다: 저장하지 않는가, 거부된 후보도 사유와 함께 나오는가,
 * 어떤 추출기가 응답했는지 드러나는가(0건의 원인을 가르는 유일한 단서).
 */
@DisplayName("EconEventExtractPreviewService")
class EconEventExtractPreviewServiceTest {

    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-09-07T00:30:00Z"), ZoneOffset.UTC);
    private static final String TEXT = "연준은 2026-09-18 에 기준금리를 결정한다.";

    private EconEventExtractor extractor;
    private EconEventValidator validator;
    private EconEventRepository repository;
    private EconEventExtractPreviewService service;

    @BeforeEach
    void setUp() {
        extractor = mock(EconEventExtractor.class);
        validator = mock(EconEventValidator.class);
        repository = mock(EconEventRepository.class);
        service = new EconEventExtractPreviewService(extractor, validator, CLOCK);
    }

    private static EconEventExtractor.ExtractedEvent candidate(String date, String region) {
        return new EconEventExtractor.ExtractedEvent(date, region, "FOMC 금리 결정", 3);
    }

    @Test
    @DisplayName("추출 후보와 검증 결과를 함께 돌려주고 저장하지 않는다")
    void preview_ReturnsCandidatesWithoutSaving() {
        when(extractor.extract(any())).thenReturn(List.of(candidate("2026-09-18", "US")));
        when(validator.validate(any(), anyString())).thenReturn(new EconEventValidator.Result(
                true,
                new EconEventValidator.ValidEvent(
                        LocalDate.of(2026, 9, 18), "US", "FOMC 금리 결정", (short) 3),
                null));

        EconEventExtractPreviewService.PreviewResult result = service.preview("https://x", TEXT);

        assertThat(result.previewedAt()).isEqualTo(Instant.now(CLOCK));
        assertThat(result.candidates()).singleElement().satisfies(c -> {
            assertThat(c.eventDate()).isEqualTo("2026-09-18");
            assertThat(c.region()).isEqualTo("US");
            assertThat(c.title()).isEqualTo("FOMC 금리 결정");
            assertThat(c.impact()).isEqualTo(3);
            assertThat(c.valid()).isTrue();
            assertThat(c.rejectReason()).isNull();
        });
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("원문과 출처를 그대로 추출기에 넘긴다 — 그라운딩 대조의 근거다")
    void preview_PassesArticleThrough() {
        when(extractor.extract(any())).thenReturn(List.of());

        service.preview("https://example.com/a", TEXT);

        ArgumentCaptor<EconEventExtractor.RawArticle> article =
                ArgumentCaptor.forClass(EconEventExtractor.RawArticle.class);
        verify(extractor).extract(article.capture());
        assertThat(article.getValue().sourceUrl()).isEqualTo("https://example.com/a");
        assertThat(article.getValue().text()).isEqualTo(TEXT);
        assertThat(article.getValue().fetchedAt()).isEqualTo(Instant.now(CLOCK));
    }

    @Test
    @DisplayName("거부된 후보도 사유와 함께 돌려준다 — 이 화면의 주된 산출물이다")
    void preview_IncludesRejectedCandidates() {
        when(extractor.extract(any())).thenReturn(List.of(candidate("어제", "MARS")));
        when(validator.validate(any(), anyString()))
                .thenReturn(new EconEventValidator.Result(false, null, "허용되지 않는 region: MARS"));

        EconEventExtractPreviewService.CandidateResult result =
                service.preview(null, TEXT).candidates().get(0);

        assertThat(result.valid()).isFalse();
        assertThat(result.rejectReason()).isEqualTo("허용되지 않는 region: MARS");
        // 검증 전 원시값이 그대로 보인다 — 형식이 어긋난 것을 보는 것이 목적이다.
        assertThat(result.eventDate()).isEqualTo("어제");
        assertThat(result.region()).isEqualTo("MARS");
    }

    @Test
    @DisplayName("어떤 추출기가 응답했는지 드러난다 — 0건의 원인을 가르는 단서다")
    void preview_ReportsExtractorImplementation() {
        EconEventExtractor noop = new EconEventExtractor() {
            @Override
            public List<ExtractedEvent> extract(RawArticle article) {
                return List.of();
            }
        };
        EconEventExtractPreviewService withNoop =
                new EconEventExtractPreviewService(noop, validator, CLOCK);

        EconEventExtractPreviewService.PreviewResult result = withNoop.preview(null, TEXT);

        assertThat(result.extractor()).isEqualTo(noop.getClass().getSimpleName());
        assertThat(result.candidates()).isEmpty();
    }

    @Test
    @DisplayName("원문이 비었거나 상한을 넘으면 400 이고 추출기를 부르지 않는다")
    void preview_InvalidText_Throws() {
        assertThatThrownBy(() -> service.preview(null, null))
                .isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> service.preview(null, "   "))
                .isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> service.preview(
                null, "가".repeat(EconEventExtractPreviewService.MAX_TEXT_LENGTH + 1)))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("자까지");
        verify(extractor, never()).extract(any());
    }

    @Test
    @DisplayName("null 의존은 거부한다")
    void nullDependencies_Throw() {
        assertThatThrownBy(() -> new EconEventExtractPreviewService(null, validator, CLOCK))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new EconEventExtractPreviewService(extractor, null, CLOCK))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new EconEventExtractPreviewService(extractor, validator, null))
                .isInstanceOf(NullPointerException.class);
    }
}
