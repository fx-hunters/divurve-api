package com.divurve.infra.scheduler;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.divurve.domain.fx.FxRateIngestionService;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link FxRateIngestionScheduler} — 배치 트리거.
 *
 * <p>가장 중요한 것은 <b>예외를 삼키는가</b>다. 배치 한 번의 실패가 위로 올라가면 스프링
 * 스케줄러가 다음 트리거까지 죽는다.
 */
@DisplayName("FxRateIngestionScheduler")
class FxRateIngestionSchedulerTest {

    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-09-07T00:30:00Z"), ZoneOffset.UTC);

    private FxRateIngestionService ingestionService;
    private FxRateIngestionScheduler scheduler;

    @BeforeEach
    void setUp() {
        ingestionService = mock(FxRateIngestionService.class);
        scheduler = new FxRateIngestionScheduler(ingestionService, 14, CLOCK);
    }

    private static FxRateIngestionService.IngestionReport report(String failureReason) {
        return new FxRateIngestionService.IngestionReport(
                List.of(new FxRateIngestionService.PairResult(
                        "USDKRW", failureReason == null ? 2 : 0, null, null, failureReason)),
                Instant.now(CLOCK));
    }

    @Test
    @DisplayName("오늘 날짜와 설정된 조회 기간으로 위임한다")
    void ingest_DelegatesWithClockDate() {
        when(ingestionService.ingest(any(), anyInt())).thenReturn(report(null));

        scheduler.ingest();

        verify(ingestionService).ingest(eq(LocalDate.now(CLOCK)), eq(14));
    }

    @Test
    @DisplayName("일부 실패해도 예외를 올리지 않는다")
    void ingest_PartialFailure_DoesNotThrow() {
        when(ingestionService.ingest(any(), anyInt())).thenReturn(report("ECOS 응답 없음"));

        assertThatCode(() -> scheduler.ingest()).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("전체 실패해도 예외를 삼킨다 — 다음 트리거까지 죽으면 안 된다")
    void ingest_Throws_IsSwallowed() {
        when(ingestionService.ingest(any(), anyInt()))
                .thenThrow(new IllegalStateException("ECOS 키가 없다"));

        assertThatCode(() -> scheduler.ingest()).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("null 의존은 거부한다")
    void nullDependencies_Throw() {
        assertThatThrownBy(() -> new FxRateIngestionScheduler(null, 14, CLOCK))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new FxRateIngestionScheduler(ingestionService, 14, null))
                .isInstanceOf(NullPointerException.class);
    }
}
