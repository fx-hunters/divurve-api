package com.divurve.infra.ai;

import com.divurve.domain.port.TokenUsage;

/**
 * Claude Messages API 호출 시임 (이슈 #73).
 *
 * <p><b>왜 인터페이스로 한 겹 감쌌나</b> — Anthropic SDK 클라이언트를 어댑터가 직접 들면
 * {@code ClaudeAiProvider} 를 네트워크 없이 테스트할 수 없고, 커버리지 100% 게이트(CLAUDE.md 8장)를
 * 통과할 방법이 없어진다. 프롬프트 조립·응답 파싱·실패 처리는 전부 어댑터 쪽에 두고, 이 시임은
 * "system·user 를 보내면 텍스트가 돌아온다"만 담당한다.
 *
 * <p>구현체({@link AnthropicMessageClient})는 {@code AnthropicConfig} 가 {@code @Bean} 으로 등록한다.
 */
public interface ClaudeMessageClient {

    /**
     * 한 번의 Messages 호출.
     *
     * @param systemPrompt 그라운딩 규약(§5 1단계) — 매 호출 동일
     * @param userPrompt   이번 서술 요청의 {@code facts} 와 설명 선호
     * @return 응답 본문 텍스트와 토큰 사용량
     * @throws RuntimeException 타임아웃·429·5xx 등 API 실패. 호출자는 즉시 폴백한다(FR-AI-06)
     */
    Completion complete(String systemPrompt, String userPrompt);

    /**
     * 호출 결과.
     *
     * <p>사용량을 {@code long} 두 개가 아니라 {@link TokenUsage} 로 드는 이유(이슈 #143) —
     * 이 값은 도메인까지 그대로 올라가 {@code ai_call_logs} 에 기록된다. 중간에서 필드를 풀었다
     * 다시 묶으면 프롬프트 캐시 토큰처럼 나중에 추가되는 항목이 조용히 빠진다.
     *
     * @param text  응답 텍스트 블록을 이어붙인 본문
     * @param usage 이 호출이 쓴 토큰
     */
    record Completion(String text, TokenUsage usage) {
    }
}
