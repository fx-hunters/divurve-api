package com.divurve.domain.port;

import java.time.Instant;
import java.util.List;

/**
 * 비정형 원문에서 경제 이벤트 후보를 추출하는 포트 (이슈 #74).
 *
 * <p>문서 {@code docs/05-ai-usage-v2.md} §3.4 "신뢰도 구분" — 구조가 없는 뉴스 원문일 때만 이 포트를
 * 거친다. 구조가 일정한 공식 캘린더(연준·ECB·한국은행 등)는 이 포트를 쓰지 않고 파서로 직접
 * {@code EconEventRepository} 에 적재한다(별도 이슈).
 *
 * <p><b>이 포트는 검증하지 않는다.</b> {@link ExtractedEvent} 는 LLM 이 돌려준 원시 문자열을 그대로
 * 담으며, 타입·ENUM·범위·날짜 신뢰성 검증은 {@code EconEventValidator} 가 전담한다 — 추출과 검증의
 * 책임을 어댑터와 도메인 서비스로 나눈다(SRP).
 */
public interface EconEventExtractor {

    /**
     * 원문 1건에서 이벤트 후보를 추출한다.
     *
     * @param article 크롤러가 확보한 원문 (grounding source)
     * @return 추출된 이벤트 후보와 이 호출이 쓴 토큰. 후보는 검증 전 원시 문자열 그대로다.
     */
    ExtractOutcome extract(RawArticle article);

    /**
     * 추출 한 번의 결과 (이슈 #143).
     *
     * <p>사용량을 함께 돌려주는 이유는 {@link AiProvider.ExplainResult} 와 같다 — 어댑터가 아는
     * 값을 도메인이 기록해야 하고, 어댑터가 리포지토리를 직접 부르는 길은 ArchUnit 이 막는다.
     *
     * @param events 추출된 이벤트 후보. 하나도 없으면 빈 목록
     * @param model  호출한 모델 ID. LLM 을 부르지 않은 구현은 {@code null}
     * @param usage  이 호출이 쓴 토큰. LLM 을 부르지 않은 구현은 {@link TokenUsage#NONE}
     */
    record ExtractOutcome(List<ExtractedEvent> events, String model, TokenUsage usage) {

        /** LLM 을 부르지 않은 구현이 쓰는 생성자. */
        public static ExtractOutcome withoutLlm(List<ExtractedEvent> events) {
            return new ExtractOutcome(events, null, TokenUsage.NONE);
        }
    }

    /**
     * 크롤러가 확보한 원문 한 건.
     *
     * @param sourceUrl 출처 URL (감사·중복 판정용)
     * @param text      원문 전문
     * @param fetchedAt 수집 시각
     */
    record RawArticle(String sourceUrl, String text, Instant fetchedAt) {
    }

    /**
     * 추출기가 돌려준 이벤트 후보 (검증 전 원시 문자열).
     *
     * @param eventDate {@code yyyy-MM-dd} 형식을 기대하지만 검증 전이므로 파싱 실패 문자열도 올 수 있다
     * @param region    허용 ENUM 문자열을 기대하지만 검증 전이므로 임의 문자열도 올 수 있다
     * @param title     이벤트 제목
     * @param impact    영향도 (1~3 기대, 검증 전이므로 null 이나 범위 밖 값도 올 수 있다)
     */
    record ExtractedEvent(String eventDate, String region, String title, Integer impact) {
    }
}
