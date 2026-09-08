package com.divurve.domain.port;

import java.util.List;
import java.util.Map;

/**
 * 생성형 AI 포트 (API 명세 v2 §5.12, {@code docs/05-ai-usage-v2.md}).
 * domain 은 이 인터페이스만 알며, 실제 구현(infra 의 실 LLM 어댑터 등)은 알지 못한다(DIP).
 *
 * <p>AI 는 <b>서술(narrate)</b> 만 한다 — 산술을 하지 않으며, {@link ExplainContext#facts()} 에 없는
 * 숫자·사실을 만들어내지 않는다(FR-AI-01, FR-AI-02). {@code explain_level}·{@code explain_domain} 은
 * 어휘·비유·설명 밀도에만 반영하고 어떤 계산에도 넣지 않는다(FR-CM-08, FR-AI-03).
 *
 * <p>{@code parseGoal} 은 v2 에서 삭제됐다(요구사항 v2 §0 개정표 — 자연어 목표 입력은 Route 상세설계
 * 확정 전까지 MVP 범위 밖, {@code 03-api-spec-v2.md} §6.2 "그 외 {@code /goals} 쓰기 501").
 */
public interface AiProvider {

    /**
     * 엔진 결과를 설명 선호에 맞춰 서술한다.
     *
     * @param context 서술 화면·엔진 사실·설명 선호를 담은 그라운딩 컨텍스트
     * @return 서술 결과 (고정 스키마 — 문장 목록)
     */
    ExplainResult explain(ExplainContext context);

    /**
     * 서술 요청 컨텍스트 — AI 가 참조할 수 있는 <b>유일한</b> 입력이다(그라운딩, §5 1단계).
     *
     * @param surface       서술 대상 화면 (예: {@code forecast_summary}). {@code forecast_summary} 는
     *                      항상 4문장이다(FR-FC-07, FR-AI-04)
     * @param facts         계산 엔진이 만든 검증된 수치·사실. {@code regime} 이 포함되면 급변 상태
     *                      안내를 반영한다(FR-SF-03)
     * @param explainLevel  설명 선호 {@code simple/standard/detailed} — 표현에만 쓴다(FR-AI-03)
     * @param explainDomain 익숙한 설명 분야 {@code finance/dev/marketing/plain} — 비유 소재로만 쓴다
     */
    record ExplainContext(
            String surface,
            Map<String, Object> facts,
            String explainLevel,
            String explainDomain) {
    }

    /**
     * explain 결과 스키마.
     *
     * <p><b>{@code usage} 가 여기 있는 이유</b>(이슈 #143) — 토큰 사용량은 어댑터가 알고 도메인이
     * 기록해야 한다. 어댑터가 리포지토리를 직접 부르는 길은 ArchUnit 이 막으므로
     * ({@code @ExternalAdapter} → {@code @PersistenceAdapter} 금지, CLAUDE.md 4장), 사용량을
     * 결과에 실어 도메인까지 올려보낸다. 예전에는 어댑터가 {@code log.info} 로 찍고 버렸다.
     *
     * <p>{@code model} 도 함께 올린다 — 모델별 단가가 다르므로 기록에 반드시 필요한데, 어떤 모델을
     * 불렀는지는 어댑터만 안다(설정은 infra 에 있고 도메인은 그것을 읽지 않는다).
     *
     * @param sentences 서술 문장 목록 (표현 필터·수치 대조 전 원문)
     * @param model     호출한 모델 ID. LLM 을 부르지 않은 구현(템플릿)은 {@code null}
     * @param usage     이 호출이 쓴 토큰. LLM 을 부르지 않은 구현(템플릿)은 {@link TokenUsage#NONE}
     */
    record ExplainResult(List<String> sentences, String model, TokenUsage usage) {

        /** LLM 을 부르지 않은 구현이 쓰는 생성자 — 모델도 토큰도 없음을 명시한다. */
        public static ExplainResult withoutLlm(List<String> sentences) {
            return new ExplainResult(sentences, null, TokenUsage.NONE);
        }
    }
}
