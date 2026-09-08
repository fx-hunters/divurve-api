package com.divurve.domain.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.divurve.common.exception.InvalidRequestException;
import com.divurve.domain.ai.entity.AiCallLog;
import com.divurve.domain.port.TokenUsage;
import java.sql.Date;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/**
 * {@link AiCallLogQueryService} 단위 테스트 (이슈 #143).
 *
 * <p>어휘 검증을 특히 본다 — 오타를 빈 결과로 돌려주면 "그 기간에 호출이 없었다" 로 읽혀, 비용을
 * 확인하려던 사람이 정확히 반대 결론을 얻는다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AiCallLogQueryService")
class AiCallLogQueryServiceTest {

    /** 기록에 함께 남는 출처 IP (이슈 #140). 이 테스트의 관심사는 아니지만 컬럼은 채워 둔다. */
    private static final String CLIENT_IP_FIXTURE = "203.0.113.7";

    private static final Instant NOW = Instant.parse("2026-09-08T03:00:00Z");

    @Mock
    private AiCallLogRepository aiCallLogRepository;

    private AiCallLogQueryService sut() {
        return new AiCallLogQueryService(aiCallLogRepository);
    }

    private static AiCallLogQueryService.CallLogFilter emptyFilter() {
        return new AiCallLogQueryService.CallLogFilter(null, null, null, null, null, null);
    }

    private static AiCallLog narrateLog() {
        return AiCallLog.narrate(NOW, UUID.randomUUID(), true, CLIENT_IP_FIXTURE, "forecast_summary",
                "claude-opus-5", new TokenUsage(120, 45, 7L, 3L),
                AiCallOutcome.FALLBACK, "provider_error", 4321, "IOException: timeout");
    }

    @Test
    @DisplayName("목록을 최신순으로 조회하고 응답 형태로 옮긴다")
    void listMapsAndSortsDescending() {
        AiCallLog log = narrateLog();
        when(aiCallLogRepository.searchForAdmin(
                any(), any(), any(), any(), any(), any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(log), PageRequest.of(0, 50), 1));

        AiCallLogQueryService.CallLogPage page = sut().list(emptyFilter(), 0, null);

        assertThat(page.items()).hasSize(1);
        AiCallLogQueryService.CallLogView view = page.items().get(0);
        assertThat(view.purpose()).isEqualTo("narrate");
        assertThat(view.outcome()).isEqualTo("fallback");
        assertThat(view.fallbackReason()).isEqualTo("provider_error");
        assertThat(view.inputTokens()).isEqualTo(120);
        assertThat(view.cacheReadInputTokens()).isEqualTo(7);
        assertThat(view.demo()).isTrue();
        assertThat(view.latencyMs()).isEqualTo(4321);
        assertThat(view.errorSummary()).isEqualTo("IOException: timeout");
        assertThat(page.totalElements()).isEqualTo(1);
        assertThat(page.totalPages()).isEqualTo(1);
        assertThat(page.size()).isEqualTo(50);

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(aiCallLogRepository).searchForAdmin(
                any(), any(), any(), any(), any(), any(), pageable.capture());
        assertThat(pageable.getValue().getSort())
                .isEqualTo(Sort.by(Sort.Direction.DESC, "requestedAt"));
    }

    @Test
    @DisplayName("빈 문자열 필터는 조건 없음으로 넘긴다 — 빈 값으로 완전일치를 걸면 0건이 된다")
    void blankFiltersBecomeNull() {
        when(aiCallLogRepository.searchForAdmin(
                any(), any(), any(), any(), any(), any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 50), 0));

        sut().list(new AiCallLogQueryService.CallLogFilter(
                null, null, "  ", "  ", "  ", null), 0, null);

        // 열린 구간은 경계 상수로 채워 넘긴다 — null Instant 는 PostgreSQL 이 타입을 정하지 못해
        // 기간 필터 없는 조회가 그대로 실패한다(AiCallLogRepository javadoc).
        verify(aiCallLogRepository).searchForAdmin(
                eq(AiCallLogQueryService.OPEN_START),
                eq(AiCallLogQueryService.OPEN_END),
                eq(null), eq(null), eq(null), eq(null), any(Pageable.class));
    }

    @Test
    @DisplayName("페이지 번호·크기가 범위를 벗어나면 400 이다")
    void rejectsBadPaging() {
        AiCallLogQueryService sut = sut();

        assertThatThrownBy(() -> sut.list(emptyFilter(), -1, null))
                .isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> sut.list(emptyFilter(), 0, 0))
                .isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() ->
                sut.list(emptyFilter(), 0, AiCallLogQueryService.MAX_PAGE_SIZE + 1))
                .isInstanceOf(InvalidRequestException.class);
    }

    @Test
    @DisplayName("어휘에 없는 purpose·outcome 은 빈 결과가 아니라 400 이다")
    void rejectsUnknownVocabulary() {
        AiCallLogQueryService sut = sut();

        assertThatThrownBy(() -> sut.list(new AiCallLogQueryService.CallLogFilter(
                null, null, "narrat", null, null, null), 0, null))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("narrate");
        assertThatThrownBy(() -> sut.list(new AiCallLogQueryService.CallLogFilter(
                null, null, null, null, "succes", null), 0, null))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("success");
    }

    @Test
    @DisplayName("기간이 뒤집혀 있으면 400 이다 — 조용한 빈 화면은 원인을 숨긴다")
    void rejectsInvertedRange() {
        AiCallLogQueryService sut = sut();
        Instant later = NOW.plusSeconds(60);

        assertThatThrownBy(() -> sut.list(
                new AiCallLogQueryService.CallLogFilter(later, NOW, null, null, null, null), 0, null))
                .isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> sut.summarize(later, NOW))
                .isInstanceOf(InvalidRequestException.class);
    }

    @Test
    @DisplayName("집계 결과를 일자·용도·모델별로 옮긴다")
    void summarizeMapsRows() {
        when(aiCallLogRepository.summarize(null, null)).thenReturn(List.of(
                new Object[] {Date.valueOf(LocalDate.of(2026, 9, 8)), "narrate", "claude-opus-5",
                        3L, 300L, 120L},
                new Object[] {Date.valueOf(LocalDate.of(2026, 9, 7)), "extract", null, 1L, 50L, 10L}));

        List<AiCallLogQueryService.UsageBucket> buckets = sut().summarize(null, null);

        assertThat(buckets).hasSize(2);
        assertThat(buckets.get(0).day()).isEqualTo(LocalDate.of(2026, 9, 8));
        assertThat(buckets.get(0).purpose()).isEqualTo("narrate");
        assertThat(buckets.get(0).model()).isEqualTo("claude-opus-5");
        assertThat(buckets.get(0).calls()).isEqualTo(3);
        assertThat(buckets.get(0).inputTokens()).isEqualTo(300);
        assertThat(buckets.get(0).outputTokens()).isEqualTo(120);
        assertThat(buckets.get(1).model())
                .as("LLM 을 부르지 않은 요청만 있었던 칸은 모델이 없다")
                .isNull();
    }

    @Test
    @DisplayName("생성자와 list 는 null 을 거부한다")
    void rejectsNulls() {
        assertThatThrownBy(() -> new AiCallLogQueryService(null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> sut().list(null, 0, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("명시한 기간과 유효한 어휘는 그대로 리포지토리로 간다")
    void explicitFiltersArePassedThrough() {
        Instant to = NOW.plusSeconds(3600);
        when(aiCallLogRepository.searchForAdmin(
                any(), any(), any(), any(), any(), any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 10), 0));

        sut().list(new AiCallLogQueryService.CallLogFilter(
                NOW, to, "extract", "forecast_summary", "cache_hit", false), 0, 10);

        verify(aiCallLogRepository).searchForAdmin(
                eq(NOW), eq(to), eq("extract"), eq("forecast_summary"), eq("cache_hit"),
                eq(false), any(Pageable.class));
    }

    @Test
    @DisplayName("한쪽만 지정한 기간은 뒤집힘 검사를 통과한다 — 열린 구간은 정상 입력이다")
    void halfOpenRangeIsAccepted() {
        when(aiCallLogRepository.summarize(any(), any())).thenReturn(List.of());

        sut().summarize(NOW, null);
        sut().summarize(null, NOW);

        verify(aiCallLogRepository).summarize(NOW, null);
        verify(aiCallLogRepository).summarize(null, NOW);
    }
}
