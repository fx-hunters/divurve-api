package com.divurve.infra.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cache.CacheManager;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;

/**
 * {@link CaffeineExternalDataCache} — 캐시를 실제로 비우는지, 없는 이름을 어떻게 다루는지.
 *
 * <p>목이 아니라 실제 캐시 매니저를 쓴다. "비웠다고 보고했지만 값이 남아 있다" 는 실패를
 * 목으로는 잡을 수 없다.
 */
@DisplayName("CaffeineExternalDataCache")
class CaffeineExternalDataCacheTest {

    private static CacheManager cacheManager() {
        return new ConcurrentMapCacheManager("fx-latest", "fx-history");
    }

    @Test
    @DisplayName("등록된 캐시를 실제로 비우고 그 이름을 돌려준다")
    void evictsRegisteredCaches() {
        CacheManager manager = cacheManager();
        manager.getCache("fx-latest").put("USDKRW", 1380.0);

        List<String> evicted =
                new CaffeineExternalDataCache(manager).evict(List.of("fx-latest", "fx-history"));

        assertThat(evicted).containsExactly("fx-latest", "fx-history");
        assertThat(manager.getCache("fx-latest").get("USDKRW")).isNull();
    }

    @Test
    @DisplayName("등록되지 않은 이름은 결과에서 빠진다 — 오타를 비웠다고 보고하지 않는다")
    void unknownCache_NotReported() {
        assertThat(new CaffeineExternalDataCache(cacheManager())
                .evict(List.of("fx-latest", "typo-cache")))
                .containsExactly("fx-latest");
    }

    @Test
    @DisplayName("빈 목록이면 아무것도 비우지 않는다")
    void emptyList_EvictsNothing() {
        assertThat(new CaffeineExternalDataCache(cacheManager()).evict(List.of())).isEmpty();
    }

    @Test
    @DisplayName("null 인자와 의존은 거부한다")
    void nullArguments_Throw() {
        assertThatThrownBy(() -> new CaffeineExternalDataCache(null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new CaffeineExternalDataCache(cacheManager()).evict(null))
                .isInstanceOf(NullPointerException.class);
    }
}
