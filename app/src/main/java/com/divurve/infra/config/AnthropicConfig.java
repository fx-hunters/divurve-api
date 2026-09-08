package com.divurve.infra.config;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.divurve.infra.ai.AnthropicMessageClient;
import com.divurve.infra.ai.AnthropicProperties;
import com.divurve.infra.ai.ClaudeMessageClient;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Anthropic SDK 배선 (이슈 #73, 이슈 #123).
 *
 * <p>{@link ExternalDataConfig} 와 달리 별도 설정 클래스로 둔다 — ECOS/FRED 는 수치 출처이고
 * Claude 는 서술 경로라서 켜고 끄는 조건이 다르다.
 *
 * <p><b>조건은 클래스가 아니라 {@code @Bean} 에 붙인다</b>(이슈 #123 (3)). 예전에는 클래스 전체가
 * {@code enabled=true} 조건이라 {@code enabled=false} 면 {@link AnthropicProperties} 자체가
 * 바인딩되지 않았고, 그래서 {@code extract-enabled} 만 켠 잘못된 조합을 아무도 붙잡지 못한 채
 * {@code NoSuchBeanDefinitionException} 으로 죽었다. 설정은 항상 바인딩해 조합을 검증하고,
 * SDK 빈만 조건부로 만든다.
 */
@Configuration
@EnableConfigurationProperties(AnthropicProperties.class)
public class AnthropicConfig {

    /**
     * SDK 자체 재시도는 끈다 (이슈 #73 확정). SDK 기본값 2 를 그대로 두면 {@code AiService} 의
     * 도메인 재시도(최대 2회)와 곱해져 한 번의 화면 조회가 최악 6회 호출이 된다.
     * 재시도 정책은 도메인 한 곳에서만 정한다.
     */
    static final int SDK_MAX_RETRIES = 0;

    static final String PREFIX = "app.external.anthropic";

    /**
     * {@code enabled} × {@code extract-enabled} 조합을 <b>어떤 빈보다 먼저</b> 검증한다
     * (이슈 #123 (3)).
     *
     * <p>{@code BeanFactoryPostProcessor} 인 이유는 순서 하나 때문이다. 검증을
     * {@link AnthropicProperties} 바인딩 시점에만 두면 {@code ClaudeEconEventExtractor} 가 먼저
     * 만들어지는 컨텍스트에서 {@code NoSuchBeanDefinitionException: ClaudeMessageClient} 가 앞질러
     * 나가고, 운영자는 다시 원인을 알 수 없는 메시지를 받는다. BFPP 는 일반 싱글턴 생성 이전에
     * 돌므로 이 경합이 없다.
     *
     * <p>{@code static} 이어야 한다 — 그래야 이 설정 클래스 자체가 너무 이른 시점에 생성되지 않는다.
     */
    @Bean
    static BeanFactoryPostProcessor anthropicPrerequisiteCheck() {
        return beanFactory -> {
            Environment environment = beanFactory.getBean(Environment.class);
            AnthropicProperties.requireExtractPrerequisite(
                environment.getProperty(PREFIX + ".enabled", Boolean.class, false),
                environment.getProperty(PREFIX + ".extract-enabled", Boolean.class, false));
        };
    }

    @Bean
    @ConditionalOnProperty(prefix = PREFIX, name = "enabled", havingValue = "true")
    AnthropicClient anthropicClient(AnthropicProperties props) {
        return AnthropicOkHttpClient.builder()
            .apiKey(props.requireApiKey())
            .maxRetries(SDK_MAX_RETRIES)
            .timeout(props.requestTimeout())
            .build();
    }

    @Bean
    @ConditionalOnProperty(prefix = PREFIX, name = "enabled", havingValue = "true")
    ClaudeMessageClient claudeMessageClient(AnthropicClient anthropicClient, AnthropicProperties props) {
        return new AnthropicMessageClient(anthropicClient, props);
    }
}
