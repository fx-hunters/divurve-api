package com.divurve.infra.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.divurve.infra.config.ExternalDataConfig;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;

/**
 * {@link CaffeineExplainResultCache} — 담고 꺼내는 왕복과, 캐시가 없을 때의 태도 (이슈 #139).
 *
 * <p>목이 아니라 실제 캐시 매니저를 쓴다. "담았다고 했는데 꺼내면 없다" 는 실패를 목으로는
 * 잡을 수 없고, 그 실패는 비용으로만 드러난다.
 */
@DisplayName("CaffeineExplainResultCache")
class CaffeineExplainResultCacheTest {

    private static CacheManager cacheManager() {
        return new ConcurrentMapCacheManager(ExternalDataConfig.EXPLAIN_CACHE_NAME);
    }

    @Test
    @DisplayName("담은 문장을 그대로 꺼낸다")
    void storesAndReturnsSentences() {
        CaffeineExplainResultCache cache = new CaffeineExplainResultCache(cacheManager());
        List<String> sentences = List.of("문장1", "문장2");

        cache.put("key", sentences);

        assertThat(cache.find("key")).contains(sentences);
    }

    @Test
    @DisplayName("담지 않은 키는 빈 값이다")
    void missingKeyIsEmpty() {
        assertThat(new CaffeineExplainResultCache(cacheManager()).find("없는키")).isEmpty();
    }

    @Test
    @DisplayName("담을 때 복사한다 — 호출자가 나중에 목록을 바꿔도 캐시는 그대로다")
    void copiesOnWrite() {
        CaffeineExplainResultCache cache = new CaffeineExplainResultCache(cacheManager());
        List<String> mutable = new ArrayList<>(List.of("원래 문장"));

        cache.put("key", mutable);
        mutable.set(0, "바뀐 문장");

        assertThat(cache.find("key")).contains(List.of("원래 문장"));
    }

    @Test
    @DisplayName("캐시가 등록되지 않았으면 기동에서 실패한다 — 조용히 캐시 없이 돌지 않는다")
    void missingCacheRegistrationFailsFast() {
        // getCache 가 null 을 주는 상태를 넘기면 캐시가 사라진 채로 배포되고, 그 사실은
        // 다음 달 청구서로만 드러난다.
        CacheManager empty = new CacheManager() {
            @Override
            public Cache getCache(String name) {
                return null;
            }

            @Override
            public Collection<String> getCacheNames() {
                return List.of();
            }
        };

        assertThatThrownBy(() -> new CaffeineExplainResultCache(empty))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(ExternalDataConfig.EXPLAIN_CACHE_NAME);
    }

    @Test
    void 인자가_null_이면_실패한다() {
        CaffeineExplainResultCache cache = new CaffeineExplainResultCache(cacheManager());
        assertThatThrownBy(() -> new CaffeineExplainResultCache(null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> cache.find(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> cache.put(null, List.of("문장")))
                .isInstanceOf(NullPointerException.class);
    }
}
