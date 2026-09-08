package com.divurve.infra.macro;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.divurve.domain.port.MacroIndicatorProvider;
import com.divurve.domain.port.MacroSnapshot;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import com.github.benmanes.caffeine.cache.Caffeine;

class FredMacroProviderTest {

    private static final Instant NOW = Instant.parse("2026-03-05T12:00:00Z");
    private static final Clock FIXED_CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private RestClient.Builder builder;
    private MockRestServiceServer server;
    private FredProperties props;

    @BeforeEach
    void setUp() {
        builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        props = new FredProperties("https://api.stlouisfed.org/fred", "TEST_KEY", false, "0 10 4 * * *");
    }

    private FredMacroProvider provider() {
        return new FredMacroProvider(builder.build(), props, FIXED_CLOCK);
    }

    @Test
    void fetchLatest_returns_snapshot_with_source_and_asOf() {
        server.expect(method(HttpMethod.GET))
            .andExpect(requestTo(Matchers.containsString("series_id=DGS10")))
            .andExpect(requestTo(Matchers.containsString("api_key=TEST_KEY")))
            .andExpect(requestTo(Matchers.containsString("sort_order=desc")))
            .andRespond(withSuccess("""
                {"observations":[{"date":"2026-03-04","value":"4.28"}]}
                """, MediaType.APPLICATION_JSON));

        MacroSnapshot snap = provider().fetchLatest("DGS10");

        assertThat(snap.seriesId()).isEqualTo("DGS10");
        assertThat(snap.value()).isEqualByComparingTo(new BigDecimal("4.28"));
        assertThat(snap.asOf()).isEqualTo(LocalDate.of(2026, 3, 4));
        assertThat(snap.source()).isEqualTo("FRED");
        assertThat(snap.fetchedAt()).isEqualTo(NOW);
        server.verify();
    }

    @Test
    void skips_missing_value_marker() {
        server.expect(requestTo(Matchers.containsString("series_id=DGS10")))
            .andRespond(withSuccess("""
                {"observations":[
                  {"date":"2026-03-05","value":"."},
                  {"date":"2026-03-04","value":"4.28"}
                ]}
                """, MediaType.APPLICATION_JSON));

        MacroSnapshot snap = provider().fetchLatest("DGS10");
        assertThat(snap.asOf()).isEqualTo(LocalDate.of(2026, 3, 4));
        server.verify();
    }

    @Test
    void missing_api_key_throws_before_calling_fred() {
        props = new FredProperties("https://api.stlouisfed.org/fred", null, false, "0 10 4 * * *");
        assertThatThrownBy(() -> provider().fetchLatest("DGS10"))
            .isInstanceOf(IllegalStateException.class);
        server.verify();
    }

    @Test
    void empty_observations_throws() {
        server.expect(requestTo(Matchers.containsString("series_id=DGS10")))
            .andRespond(withSuccess("{\"observations\":[]}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> provider().fetchLatest("DGS10"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("no observations");
        server.verify();
    }

    @Test
    void only_missing_values_throws() {
        server.expect(requestTo(Matchers.containsString("series_id=DGS10")))
            .andRespond(withSuccess("""
                {"observations":[
                  {"date":"2026-03-05","value":"."},
                  {"date":"2026-03-04","value":null}
                ]}
                """, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> provider().fetchLatest("DGS10"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("missing observations");
        server.verify();
    }

    @Test
    void blank_api_key_throws() {
        props = new FredProperties("https://api.stlouisfed.org/fred", "   ", false, "0 10 4 * * *");
        assertThatThrownBy(() -> provider().fetchLatest("DGS10"))
            .isInstanceOf(IllegalStateException.class);
        server.verify();
    }

    @Test
    void empty_body_throws() {
        server.expect(requestTo(Matchers.containsString("series_id=DGS10")))
            .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> provider().fetchLatest("DGS10"))
            .isInstanceOf(IllegalStateException.class);
        server.verify();
    }

    @Test
    void cache_hit_avoids_second_http_call() {
        server.expect(requestTo(Matchers.containsString("series_id=DGS10")))
            .andRespond(withSuccess("""
                {"observations":[{"date":"2026-03-04","value":"4.28"}]}
                """, MediaType.APPLICATION_JSON));

        CachingConfig.INSTANCE = new FredMacroProvider(builder.build(), props, FIXED_CLOCK);
        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext(CachingConfig.class)) {
            MacroIndicatorProvider cached = ctx.getBean(MacroIndicatorProvider.class);
            MacroSnapshot first = cached.fetchLatest("DGS10");
            MacroSnapshot second = cached.fetchLatest("DGS10");
            assertThat(second).isEqualTo(first);
        }
        server.verify();
    }

    @Configuration
    @EnableCaching
    static class CachingConfig {
        static FredMacroProvider INSTANCE;

        @Bean
        CacheManager cacheManager() {
            CaffeineCacheManager manager = new CaffeineCacheManager();
            manager.setCaffeine(Caffeine.newBuilder().maximumSize(10));
            manager.setCacheNames(java.util.List.of("macro-latest"));
            return manager;
        }

        @Bean
        FredMacroProvider provider() {
            return INSTANCE;
        }
    }
}
