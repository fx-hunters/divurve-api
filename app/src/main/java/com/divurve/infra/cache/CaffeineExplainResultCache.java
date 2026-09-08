package com.divurve.infra.cache;

import com.divurve.common.architecture.ExternalAdapter;
import com.divurve.domain.port.ExplainResultCache;
import com.divurve.infra.config.ExternalDataConfig;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;

/**
 * {@link ExplainResultCache} 의 Caffeine 구현 (이슈 #139).
 *
 * <p>{@code ExternalDataConfig} 가 {@link ExternalDataConfig#EXPLAIN_CACHE_NAME} 으로 등록한 캐시를
 * 쓴다. 환율·거시 캐시와 <b>TTL 이 다르므로</b> 같은 매니저 안에 별도 스펙으로 등록돼 있다.
 *
 * <p><b>캐시가 없으면 기동에서 실패한다.</b> {@code getCache} 가 {@code null} 을 주는 상태를 그냥
 * 넘기면 캐시가 조용히 사라진 채로 배포되고, 그 사실은 다음 달 청구서로만 드러난다 — 이 클래스가
 * 있는 목적이 정확히 그 청구서를 막는 것이다.
 */
@ExternalAdapter
public class CaffeineExplainResultCache implements ExplainResultCache {

    private final Cache cache;

    public CaffeineExplainResultCache(CacheManager cacheManager) {
        Objects.requireNonNull(cacheManager, "cacheManager");
        Cache resolved = cacheManager.getCache(ExternalDataConfig.EXPLAIN_CACHE_NAME);
        if (resolved == null) {
            throw new IllegalStateException(
                    "'%s' 캐시가 등록되지 않았다 — ExternalDataConfig 를 확인하라"
                            .formatted(ExternalDataConfig.EXPLAIN_CACHE_NAME));
        }
        this.cache = resolved;
    }

    @Override
    public Optional<List<String>> find(String key) {
        Objects.requireNonNull(key, "key");
        Cache.ValueWrapper wrapper = cache.get(key);
        if (wrapper == null) {
            return Optional.empty();
        }
        // 이 캐시에 값을 넣는 경로는 아래 put 하나뿐이고 거기서 List<String> 만 넣는다.
        @SuppressWarnings("unchecked")
        List<String> sentences = (List<String>) wrapper.get();
        return Optional.of(sentences);
    }

    @Override
    public void put(String key, List<String> sentences) {
        Objects.requireNonNull(key, "key");
        cache.put(key, List.copyOf(sentences));
    }
}
