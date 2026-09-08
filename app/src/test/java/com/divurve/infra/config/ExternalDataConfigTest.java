package com.divurve.infra.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cache.CacheManager;

/**
 * 캐시 매니저가 실제로 어떤 캐시를 갖는지 (이슈 #139).
 *
 * <p><b>왜 설정 클래스를 테스트하는가.</b> 등록되지 않은 이름은 {@code getCache} 가
 * {@code null} 을 주고, 그 상태는 "캐시가 조용히 없는" 배포로 이어진다 — {@code EXTERNAL_CACHE_NAMES}
 * 의 주석이 경고하는 바로 그 실패다. 서술 캐시는 TTL 이 달라 {@code setCacheNames} 가 아니라
 * {@code registerCustomCache} 로 들어가는데, 그 두 경로가 함께 살아 있는지는 기동해 보지 않으면
 * 알 수 없다.
 */
@DisplayName("ExternalDataConfig 캐시 등록")
class ExternalDataConfigTest {

    private final CacheManager manager = new ExternalDataConfig().externalDataCacheManager();

    @Test
    @DisplayName("외부 데이터 캐시와 서술 캐시가 한 매니저에 함께 있다")
    void registersBothTheSharedCachesAndTheExplainCache() {
        assertThat(manager.getCacheNames())
                .containsAll(ExternalDataConfig.EXTERNAL_CACHE_NAMES)
                .contains(ExternalDataConfig.EXPLAIN_CACHE_NAME);
        assertThat(manager.getCache(ExternalDataConfig.EXPLAIN_CACHE_NAME)).isNotNull();
    }

    @Test
    @DisplayName("서술 캐시는 외부 데이터 캐시보다 짧은 TTL 을 쓴다")
    void explainCacheExpiresSoonerThanExternalData() {
        // 한 통에 넣으면 서술이 6시간 고정된다 — 프롬프트를 고쳐도 반영되지 않는다.
        assertThat(ExternalDataConfig.EXPLAIN_CACHE_TTL)
                .isLessThan(ExternalDataConfig.EXTERNAL_CACHE_TTL);
    }
}
