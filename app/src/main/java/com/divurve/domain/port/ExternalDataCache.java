package com.divurve.domain.port;

import java.util.List;

/**
 * 외부 데이터 캐시를 비우는 포트 (이슈 #111).
 *
 * <p><b>{@code @CacheEvict} 를 도메인에 붙이지 않는 이유</b> — {@code @Cacheable} 은 infra 어댑터
 * ({@code EcosFxRateProvider} 등)에 붙어 있고, ArchUnit 상 infra 는 아무도 접근할 수 없다. 같은
 * 어댑터 클래스 안에 evict 메서드를 만들어도 내부 호출은 Spring 프록시를 타지 않아 조용히 무시된다.
 * 그래서 "캐시를 비운다" 를 명시적인 포트로 만들어 도메인이 캐시 이름만 알고 부르게 한다.
 */
public interface ExternalDataCache {

    /**
     * 지정한 캐시들을 비운다.
     *
     * @param cacheNames 비울 캐시 이름
     * @return 실제로 비운 이름. 존재하지 않는 캐시는 빠진다 — 오타를 조용히 넘기지 않기 위해
     *         호출자가 요청한 것과 비교할 수 있어야 한다
     */
    List<String> evict(List<String> cacheNames);
}
