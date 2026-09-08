package com.divurve.domain.port;

import java.util.Objects;

/**
 * LLM 한 번의 호출이 쓴 토큰 (이슈 #143).
 *
 * <p><b>왜 포트 패키지에 두는가</b> — 이 값은 도메인이 기록해야 하는데 만들어지는 곳은 infra 다.
 * 어댑터가 리포지토리를 직접 부르는 길은 ArchUnit 이 막는다({@code @ExternalAdapter} →
 * {@code @PersistenceAdapter} 금지, CLAUDE.md 4장). 그래서 사용량을 포트의 반환값에 실어
 * 도메인까지 올려보내고, 기록은 유스케이스가 한다.
 *
 * <p>{@link #NONE} 은 <b>실제로 LLM 을 부르지 않은</b> 경로를 위한 값이다 — 캐시 히트·폴백·쿼터
 * 차단. 그 경로도 로그에 행을 남기므로(어느 쪽인지는 {@code outcome} 이 가른다) 토큰 0 을 표현할
 * 수단이 필요하다.
 *
 * @param inputTokens              입력 토큰
 * @param outputTokens             출력 토큰
 * @param cacheReadInputTokens     프롬프트 캐시에서 읽은 입력 토큰. 캐싱을 쓰지 않으면 {@code null}
 * @param cacheCreationInputTokens 프롬프트 캐시에 쓴 입력 토큰. 캐싱을 쓰지 않으면 {@code null}
 */
public record TokenUsage(
        long inputTokens,
        long outputTokens,
        Long cacheReadInputTokens,
        Long cacheCreationInputTokens) {

    /** LLM 을 부르지 않은 경로(캐시 히트·폴백·쿼터 차단)의 사용량. */
    public static final TokenUsage NONE = new TokenUsage(0, 0, null, null);

    public TokenUsage {
        if (inputTokens < 0 || outputTokens < 0) {
            throw new IllegalArgumentException(
                    "토큰 수는 음수일 수 없다: input=" + inputTokens + " output=" + outputTokens);
        }
    }

    /** 캐시 토큰 없이 만든다 — 프롬프트 캐싱을 쓰지 않는 호출. */
    public static TokenUsage of(long inputTokens, long outputTokens) {
        return new TokenUsage(inputTokens, outputTokens, null, null);
    }

    /** 두 사용량을 합친다. 한 요청이 재시도로 여러 번 호출됐을 때 총량을 낸다. */
    public TokenUsage plus(TokenUsage other) {
        Objects.requireNonNull(other, "other");
        return new TokenUsage(
                inputTokens + other.inputTokens,
                outputTokens + other.outputTokens,
                sum(cacheReadInputTokens, other.cacheReadInputTokens),
                sum(cacheCreationInputTokens, other.cacheCreationInputTokens));
    }

    /**
     * 둘 다 {@code null} 이면 {@code null} 을 지킨다 — "측정되지 않았다" 와 "0 이었다" 는 다르고,
     * 0 으로 채워 넣으면 프롬프트 캐싱을 켠 뒤 과거 구간과 구분되지 않는다.
     */
    private static Long sum(Long left, Long right) {
        if (left == null) {
            return right;
        }
        return right == null ? left : left + right;
    }
}
