package com.divurve.domain.ai.entity;

import com.divurve.domain.ai.AiCallOutcome;
import com.divurve.domain.ai.AiCallPurpose;
import com.divurve.domain.port.TokenUsage;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * AI 호출 한 건의 기록 (이슈 #143). 테이블 {@code ai_call_logs} 에 매핑된다.
 *
 * <p><b>{@code userId} 를 관계가 아니라 UUID 로 든다.</b> 이 기록은 사용자를 <b>탐색하지 않는다</b> —
 * 필요한 것은 "누가 썼는지" 라는 식별자뿐이고, 그 사용자는 데모 정리(이슈 #138)로 사라질 수 있다.
 * 관계로 두면 삭제된 사용자를 가리키는 프록시가 생겨 조회 경로마다 그 사실을 다뤄야 한다.
 * FK 는 {@code on delete set null} 이므로 사용자가 지워지면 이 값만 비고 행은 남는다.
 *
 * <p><b>용도·결과를 enum 이 아니라 String 으로 든다.</b> {@code @Enumerated(STRING)} 은 상수명을
 * 그대로 넣으므로 DB 에 대문자가 들어간다. 그러면 관리자가 SQL 로 보는 값과 API 응답의 값이 달라진다
 * ({@code NARRATE} vs {@code narrate}) — 값 표기는 한 가지여야 한다. 어휘는 DB CHECK 제약과
 * {@link AiCallPurpose}·{@link AiCallOutcome} 이 함께 지킨다.
 *
 * <p>페이로드(프롬프트·응답 전문)는 담지 않는다 — 이슈 #56 의 범위다.
 */
@Entity
@Table(name = "ai_call_logs")
public class AiCallLog {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "uuid")
    private UUID id;

    /**
     * 호출 시각. DB 기본값에 맡기지 않고 애플리케이션 {@code Clock} 으로 넣는다 — 테스트가 시각을
     * 고정할 수 있어야 하고, 집계의 일자 경계가 DB 서버 시계와 갈리지 않아야 한다.
     */
    @Column(name = "requested_at", nullable = false)
    private Instant requestedAt;

    @Column(name = "user_id", columnDefinition = "uuid")
    private UUID userId;

    @Column(name = "is_demo", nullable = false)
    private boolean isDemo;

    @Column(name = "purpose", nullable = false, length = 16)
    private String purpose;

    /** 서술 대상 화면. {@code extract} 경로에는 없다. */
    @Column(name = "surface", length = 64)
    private String surface;

    /** 호출한 모델. LLM 을 부르지 않은 요청(템플릿·캐시·쿼터 차단)에서는 {@code null} 이다. */
    @Column(name = "model", length = 128)
    private String model;

    @Column(name = "input_tokens", nullable = false)
    private long inputTokens;

    @Column(name = "output_tokens", nullable = false)
    private long outputTokens;

    /** 프롬프트 캐싱을 쓰지 않으면 {@code null} — "측정되지 않았다" 와 "0 이었다" 는 다르다. */
    @Column(name = "cache_read_input_tokens")
    private Long cacheReadInputTokens;

    @Column(name = "cache_creation_input_tokens")
    private Long cacheCreationInputTokens;

    @Column(name = "outcome", nullable = false, length = 16)
    private String outcome;

    /** 폴백 사유. {@code outcome} 이 {@code fallback} 이 아니면 {@code null}. */
    @Column(name = "fallback_reason", length = 32)
    private String fallbackReason;

    @Column(name = "latency_ms")
    private Integer latencyMs;

    /** 예외 클래스·메시지 요약. 스택트레이스는 남기지 않는다 — 이 표는 비용 집계용이다. */
    @Column(name = "error_summary")
    private String errorSummary;

    /** JPA 전용 기본 생성자. */
    protected AiCallLog() {
    }

    private AiCallLog(
            Instant requestedAt,
            UUID userId,
            boolean isDemo,
            AiCallPurpose purpose,
            String surface,
            String model,
            TokenUsage usage,
            AiCallOutcome outcome,
            String fallbackReason,
            Integer latencyMs,
            String errorSummary) {
        this.requestedAt = requestedAt;
        this.userId = userId;
        this.isDemo = isDemo;
        this.purpose = purpose.code();
        this.surface = surface;
        this.model = model;
        this.inputTokens = usage.inputTokens();
        this.outputTokens = usage.outputTokens();
        this.cacheReadInputTokens = usage.cacheReadInputTokens();
        this.cacheCreationInputTokens = usage.cacheCreationInputTokens();
        this.outcome = outcome.code();
        this.fallbackReason = fallbackReason;
        this.latencyMs = latencyMs;
        this.errorSummary = errorSummary;
    }

    /**
     * 서술(narrate) 호출 기록을 만든다.
     *
     * @param userId         요청한 사용자
     * @param isDemo         데모 세션의 요청인지 — 데모 트래픽 비중이 비용 분석의 핵심 축이다
     * @param surface        서술 대상 화면
     * @param model          호출한 모델. 모델별 단가가 다르므로 반드시 남긴다
     * @param usage          토큰 사용량. 실 호출이 없었으면 {@link TokenUsage#NONE}
     * @param outcome        호출 결과
     * @param fallbackReason 폴백 사유. 폴백이 아니면 {@code null}
     * @param latencyMs      소요 시간
     * @param errorSummary   예외 요약. 예외가 없었으면 {@code null}
     */
    public static AiCallLog narrate(
            Instant requestedAt,
            UUID userId,
            boolean isDemo,
            String surface,
            String model,
            TokenUsage usage,
            AiCallOutcome outcome,
            String fallbackReason,
            Integer latencyMs,
            String errorSummary) {
        return new AiCallLog(requestedAt, userId, isDemo, AiCallPurpose.NARRATE, surface, model,
                usage, outcome, fallbackReason, latencyMs, errorSummary);
    }

    /**
     * 추출(extract) 호출 기록을 만든다. 배치에서 돌아 {@code userId} 가 없고, 화면이 아니므로
     * {@code surface} 도 없다.
     */
    public static AiCallLog extract(
            Instant requestedAt,
            UUID userId,
            String model,
            TokenUsage usage,
            AiCallOutcome outcome,
            Integer latencyMs,
            String errorSummary) {
        return new AiCallLog(requestedAt, userId, false, AiCallPurpose.EXTRACT, null, model,
                usage, outcome, null, latencyMs, errorSummary);
    }

    public UUID getId() {
        return id;
    }

    public Instant getRequestedAt() {
        return requestedAt;
    }

    public UUID getUserId() {
        return userId;
    }

    public boolean isDemo() {
        return isDemo;
    }

    public String getPurpose() {
        return purpose;
    }

    public String getSurface() {
        return surface;
    }

    public String getModel() {
        return model;
    }

    public long getInputTokens() {
        return inputTokens;
    }

    public long getOutputTokens() {
        return outputTokens;
    }

    public Long getCacheReadInputTokens() {
        return cacheReadInputTokens;
    }

    public Long getCacheCreationInputTokens() {
        return cacheCreationInputTokens;
    }

    public String getOutcome() {
        return outcome;
    }

    public String getFallbackReason() {
        return fallbackReason;
    }

    public Integer getLatencyMs() {
        return latencyMs;
    }

    public String getErrorSummary() {
        return errorSummary;
    }
}
