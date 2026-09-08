package com.divurve.domain.port;

import java.util.List;
import java.util.Optional;

/**
 * 검증을 통과한 AI 서술의 캐시 포트 (이슈 #139).
 *
 * <p><b>{@code @Cacheable} 을 쓰지 않는 이유.</b> 캐시 히트는 {@code ai_call_logs} 에
 * {@code outcome='cache_hit'} 로 남겨야 하는데(이슈 #143), {@code @Cacheable} 은 히트일 때
 * 메서드 자체를 부르지 않으므로 그 안에서는 기록할 방법이 없다. 그리고 애노테이션은 infra
 * 어댑터에 붙는데 ArchUnit 상 infra 는 아무도 접근할 수 없다({@link ExternalDataCache} 가 같은
 * 이유로 포트가 됐다). 그래서 조회·저장을 도메인이 <b>명시적으로</b> 부르게 한다.
 *
 * <p><b>무엇을 담는가 — 검증을 통과한 문장만.</b> 폴백은 담지 않는다. 일시적인 provider 장애로
 * 나온 템플릿 문장이 TTL 동안 고정되면, 장애가 끝난 뒤에도 모두가 폴백을 계속 받는다.
 *
 * <p><b>모델 ID 는 담지 않는다.</b> 캐시 히트 행의 {@code model} 은 {@code null} 이다 — "이 요청에서
 * LLM 을 부르지 않았다" 가 그 컬럼의 뜻이고(({@code AiService} 기록 규약), 히트가 실 호출이 아님은
 * {@code outcome} 이 이미 말한다. 여기에 원래 모델을 적으면 관리자 화면에서 <b>토큰 0 짜리 호출</b>
 * 로 보여 모델별 호출량이 부풀려진다.
 */
public interface ExplainResultCache {

    /**
     * 캐시된 서술을 찾는다.
     *
     * @param key 정규화된 요청 키
     * @return 있으면 문장 목록, 없으면 빈 값
     */
    Optional<List<String>> find(String key);

    /**
     * 검증을 통과한 서술을 담는다.
     *
     * @param key       정규화된 요청 키
     * @param sentences 수치 대조·표현 필터를 모두 통과한 문장 목록
     */
    void put(String key, List<String> sentences);
}
