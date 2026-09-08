package com.divurve.domain.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

import com.divurve.domain.ai.entity.AiCallLog;
import com.divurve.domain.port.TokenUsage;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * {@link AiCallLogRecorder} 단위 테스트 (이슈 #143).
 *
 * <p>핵심은 <b>기록 실패가 전파되지 않는다</b>는 것이다 — 비용 기록 하나 때문에 AI 응답이 500 이
 * 되면 FR-AI-06·NFR-AI-03 을 어긴다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AiCallLogRecorder")
class AiCallLogRecorderTest {

    /** 기록에 함께 남는 출처 IP (이슈 #140). 이 테스트의 관심사는 아니지만 컬럼은 채워 둔다. */
    private static final String CLIENT_IP_FIXTURE = "203.0.113.7";

    @Mock
    private AiCallLogRepository aiCallLogRepository;

    private AiCallLog sample() {
        return AiCallLog.narrate(
                Instant.parse("2026-09-08T00:00:00Z"),
                UUID.randomUUID(),
                true, CLIENT_IP_FIXTURE,
                "forecast_summary",
                "claude-opus-5",
                TokenUsage.of(100, 40),
                AiCallOutcome.SUCCESS,
                null,
                123,
                null);
    }

    @Test
    @DisplayName("기록을 저장한다")
    void recordSaves() {
        AiCallLog callLog = sample();

        new AiCallLogRecorder(aiCallLogRepository).record(callLog);

        verify(aiCallLogRepository).save(callLog);
    }

    @Test
    @DisplayName("저장이 실패해도 예외를 던지지 않는다 — 비용 기록이 AI 응답을 깨뜨리지 않는다")
    void recordSwallowsPersistenceFailure() {
        doThrow(new IllegalStateException("DB 연결 끊김"))
                .when(aiCallLogRepository).save(any(AiCallLog.class));

        AiCallLogRecorder sut = new AiCallLogRecorder(aiCallLogRepository);

        // 던지지 않는 것 자체가 검증 대상이다.
        sut.record(sample());
    }

    @Test
    @DisplayName("생성자와 record 는 null 을 거부한다")
    void rejectsNulls() {
        assertThatThrownBy(() -> new AiCallLogRecorder(null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new AiCallLogRecorder(aiCallLogRepository).record(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("summarize 는 예외를 클래스명과 메시지로 줄인다")
    void summarizeShortensException() {
        assertThat(AiCallLogRecorder.summarize(null)).isNull();
        assertThat(AiCallLogRecorder.summarize(new IllegalStateException("타임아웃")))
                .isEqualTo("IllegalStateException: 타임아웃");
        assertThat(AiCallLogRecorder.summarize(new IllegalStateException()))
                .isEqualTo("IllegalStateException");
    }

    @Test
    @DisplayName("summarize 는 상한을 넘으면 자른다 — 이 표는 스택트레이스를 담는 곳이 아니다")
    void summarizeTruncates() {
        String longMessage = "x".repeat(AiCallLogRecorder.MAX_ERROR_SUMMARY_LENGTH * 2);

        String summary = AiCallLogRecorder.summarize(new IllegalStateException(longMessage));

        assertThat(summary).hasSize(AiCallLogRecorder.MAX_ERROR_SUMMARY_LENGTH);
    }
}
