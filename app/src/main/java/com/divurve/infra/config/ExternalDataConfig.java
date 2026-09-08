package com.divurve.infra.config;

import com.divurve.infra.fxrate.EcosProperties;
import com.divurve.infra.macro.FredProperties;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneId;
import java.util.List;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.client.ClientHttpRequestFactories;
import org.springframework.boot.web.client.ClientHttpRequestFactorySettings;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * 외부 데이터 인프라 설정 (이슈 #12, #16).
 *
 * <p>- {@link RestClient} 공용 빈: ECOS/FRED 어댑터가 각자 baseUrl 을 얹어 사용한다.
 * - Caffeine 기반 로컬 캐시: 일별 종가는 하루에 한 번만 갱신되므로 6h TTL 로 충분.
 * - 캐시 이름은 각 어댑터의 {@code @Cacheable} 애노테이션과 정확히 일치해야 한다.
 */
@Configuration
@EnableCaching
@EnableConfigurationProperties({EcosProperties.class, FredProperties.class})
public class ExternalDataConfig {

    static final Duration EXTERNAL_CACHE_TTL = Duration.ofHours(6);
    static final long EXTERNAL_CACHE_MAX_SIZE = 500;

    /**
     * 캐시 이름은 각 어댑터의 {@code @Cacheable} 과 정확히 일치해야 한다 — 여기 없는 이름을 쓰면
     * {@code CaffeineCacheManager} 가 해당 캐시를 만들지 않아 <b>조용히 캐시가 없는 상태</b>가 된다.
     *
     * <p>{@code fx-history} 는 이슈 #57 에서 추가했다. 가장 무거운 호출인데 캐시가 없었다 —
     * {@code /forecast}·{@code /market/regime} 이 열릴 때마다 통화쌍별로 5년치(약 1,400 관측)를
     * 새로 받아 변동성·백분위를 다시 계산했다.
     *
     * <p>{@code currency-master} 는 이슈 #111 에서 추가했다. 통화 마스터가 하드코딩 상수에서 DB 표로
     * 옮겨오면서, 계획 계산 한 번마다 통화 표시 규칙 조회가 DB 를 치게 됐다. 이 표는 마이그레이션
     * 시드로만 바뀌므로 TTL 안에서 낡을 일이 사실상 없다.
     */
    static final List<String> EXTERNAL_CACHE_NAMES =
        List.of("fx-latest", "fx-history", "macro-latest", "currency-master");

    /**
     * AI 서술 응답 캐시 (이슈 #139). {@code EXTERNAL_CACHE_NAMES} 에 넣지 않고 아래에서 따로
     * 등록하는 이유는 <b>TTL 이 다르기 때문</b>이다 — {@code setCaffeine} 은 매니저 전체에 하나의
     * 스펙만 적용하므로, 6시간을 쓰는 외부 데이터 캐시와 한 통에 넣으면 서술이 6시간 고정된다.
     */
    public static final String EXPLAIN_CACHE_NAME = "ai-explain";

    /**
     * 서술 캐시 TTL. 같은 {@code facts} 는 언제 물어도 같은 문장이므로 값이 낡는 문제는 없다 —
     * 이 상한은 (a) 메모리를 무한정 붙잡아 두지 않기 위한 것이고 (b) 프롬프트·모델을 바꿨을 때 옛 문장이
     * 영구히 남지 않게 하기 위한 것이다.
     */
    static final Duration EXPLAIN_CACHE_TTL = Duration.ofHours(1);

    /**
     * 데모 계정은 {@code DemoSampleData} 템플릿 하나를 복제하므로 {@code facts} 가 문자 그대로
     * 같다 — 데모 트래픽 전체가 (화면 × explain_level × explain_domain) 조합 수십 개로 수렴한다.
     * 실 사용자 몫까지 합쳐 500개면 충분하고, 넘치면 오래된 것부터 밀린다.
     */
    static final long EXPLAIN_CACHE_MAX_SIZE = 500;

    /** 연결 타임아웃 — 외부가 응답하지 않을 때 요청 스레드를 붙잡아 두지 않는다. */
    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);

    /**
     * 읽기 타임아웃. 5년치 시계열은 응답이 크므로 단건 조회보다 넉넉히 준다.
     * 타임아웃이 없으면 ECOS 지연이 그대로 우리 서비스의 지연이 된다.
     */
    static final Duration READ_TIMEOUT = Duration.ofSeconds(15);

    @Bean
    RestClient externalRestClient() {
        return RestClient.builder()
            .requestFactory(ClientHttpRequestFactories.get(
                ClientHttpRequestFactorySettings.DEFAULTS
                    .withConnectTimeout(CONNECT_TIMEOUT)
                    .withReadTimeout(READ_TIMEOUT)))
            .build();
    }

    /**
     * 도메인 전역의 "오늘" 기준 타임존. 환율 출처인 ECOS 가 KST 영업일로 고시하므로,
     * 도메인이 판단하는 오늘도 KST 여야 어댑터가 받아 온 날짜와 어긋나지 않는다.
     */
    static final ZoneId SERVICE_ZONE = ZoneId.of("Asia/Seoul");

    /**
     * UTC 가 아니라 KST 다(이슈 #99). UTC 로 두면 매일 00:00~09:00 KST 동안
     * {@code LocalDate.now(clock)} 이 하루 전을 가리켜, {@code /market/regime} 의 {@code as_of} 가
     * 영업일이 아닌 날짜로 나가고 데이터 신선도 판정도 하루 어긋났다.
     */
    @Bean
    Clock systemClock() {
        return Clock.system(SERVICE_ZONE);
    }

    @Bean
    CacheManager externalDataCacheManager() {
        CaffeineCacheManager manager = new CaffeineCacheManager();
        manager.setCaffeine(Caffeine.newBuilder()
            .expireAfterWrite(EXTERNAL_CACHE_TTL)
            .maximumSize(EXTERNAL_CACHE_MAX_SIZE));
        manager.setCacheNames(EXTERNAL_CACHE_NAMES);
        // 서술 캐시만 자기 TTL 로 등록한다(이슈 #139). registerCustomCache 로 넣어도
        // getCacheNames() 에 함께 잡히므로 ExternalDataCache.evict 로 비울 수 있다.
        manager.registerCustomCache(EXPLAIN_CACHE_NAME, Caffeine.newBuilder()
            .expireAfterWrite(EXPLAIN_CACHE_TTL)
            .maximumSize(EXPLAIN_CACHE_MAX_SIZE)
            .build());
        return manager;
    }

    // FxRateHistoryProvider(EcosFxRateHistoryProvider) · ForecastService 를 여기서 @Bean 으로 다시
    // 만들지 않는다. 두 구현체 모두 @ExternalAdapter / @UseCase
    // (각각 @Component / @Service 를 메타 어노테이션으로 갖는다)가 붙어 이미 컴포넌트 스캔 대상이므로,
    // @Bean 을 함께 두면 같은 타입의 빈이 2개가 되어 NoUniqueBeanDefinitionException 으로 기동이 실패한다(이슈 #38).
    // 다른 어댑터(EcosFxRateProvider·FredMacroProvider·MockAiProvider)도 스캔에만 의존한다.
}
