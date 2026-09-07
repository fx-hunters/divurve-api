package com.divurve.infra.cache;

import com.divurve.common.architecture.ExternalAdapter;
import com.divurve.domain.port.ExternalDataCache;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;

/**
 * {@link ExternalDataCache} 의 Spring 캐시 구현 (이슈 #111).
 *
 * <p>{@code ExternalDataConfig} 가 만든 Caffeine 캐시 매니저를 그대로 쓴다. 등록되지 않은 이름은
 * {@code getCache} 가 {@code null} 을 주므로 건너뛰고, 반환값에서도 빠진다 — 오타를 "비웠다" 고
 * 보고하면 갱신했는데 왜 옛 값이 나오는지 알 수 없게 된다.
 */
@ExternalAdapter
public class CaffeineExternalDataCache implements ExternalDataCache {

    private final CacheManager cacheManager;

    public CaffeineExternalDataCache(CacheManager cacheManager) {
        this.cacheManager = Objects.requireNonNull(cacheManager, "cacheManager");
    }

    @Override
    public List<String> evict(List<String> cacheNames) {
        Objects.requireNonNull(cacheNames, "cacheNames");

        List<String> evicted = new ArrayList<>();
        for (String name : cacheNames) {
            Cache cache = cacheManager.getCache(name);
            if (cache != null) {
                cache.clear();
                evicted.add(name);
            }
        }
        return List.copyOf(evicted);
    }
}
