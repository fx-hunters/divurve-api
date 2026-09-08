package com.divurve.infra.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * {@link AnthropicProperties} 기본값·키 검증·조합 검증 테스트 (이슈 #73, 이슈 #123).
 * 기본값은 확정값이므로 상수를 그대로 비교한다 — 값이 바뀌면 테스트가 먼저 알린다.
 */
class AnthropicPropertiesTest {

    @Test
    void 비어_있는_값은_확정_기본값으로_채운다() {
        AnthropicProperties props = new AnthropicProperties(true, false, "key", "  ", 0, null);

        assertThat(props.model()).isEqualTo(AnthropicProperties.DEFAULT_MODEL);
        assertThat(props.maxTokens()).isEqualTo(AnthropicProperties.DEFAULT_MAX_TOKENS);
        assertThat(props.requestTimeout()).isEqualTo(Duration.ofSeconds(5));
    }

    /** #123 은 값을 조정 가능하게만 만든다 — 기본값 자체는 그대로다(값 결정은 #124). */
    @Test
    void 기본값은_이슈_73_확정값_그대로다() {
        assertThat(AnthropicProperties.DEFAULT_MODEL).isEqualTo("claude-opus-5");
        assertThat(AnthropicProperties.DEFAULT_MAX_TOKENS).isEqualTo(1024);
        assertThat(AnthropicProperties.DEFAULT_REQUEST_TIMEOUT).isEqualTo(Duration.ofSeconds(5));
    }

    @Test
    void model_이_null_이어도_기본값으로_채운다() {
        assertThat(new AnthropicProperties(true, false, "key", null, 1, Duration.ofSeconds(1))
                .model()).isEqualTo(AnthropicProperties.DEFAULT_MODEL);
    }

    @Test
    void 명시한_값은_그대로_둔다() {
        AnthropicProperties props = new AnthropicProperties(
                false, false, "key", "claude-sonnet-5", 512, Duration.ofSeconds(3));

        assertThat(props.enabled()).isFalse();
        assertThat(props.extractEnabled()).isFalse();
        assertThat(props.model()).isEqualTo("claude-sonnet-5");
        assertThat(props.maxTokens()).isEqualTo(512);
        assertThat(props.requestTimeout()).isEqualTo(Duration.ofSeconds(3));
    }

    @Test
    void requireApiKey_는_설정된_키를_돌려준다() {
        assertThat(new AnthropicProperties(true, false, "sk-ant-test", null, 0, null).requireApiKey())
                .isEqualTo("sk-ant-test");
    }

    @Test
    void requireApiKey_는_키가_비어_있으면_기동을_실패시킨다() {
        assertThatThrownBy(() -> new AnthropicProperties(true, false, "  ", null, 0, null).requireApiKey())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("api-key");

        assertThatThrownBy(() -> new AnthropicProperties(true, false, null, null, 0, null).requireApiKey())
                .isInstanceOf(IllegalStateException.class);
    }

    /**
     * 이슈 #123 (3) — 예전에는 이 조합이 {@code NoSuchBeanDefinitionException: ClaudeMessageClient}
     * 로 죽어 운영자에게 "enabled 도 같이 켜야 한다"는 정보가 전혀 없었다.
     */
    @Test
    void extract_만_켜면_이유를_말하고_기동을_실패시킨다() {
        assertThatThrownBy(() -> new AnthropicProperties(false, true, "key", null, 0, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("extract-enabled")
                .hasMessageContaining("enabled=false")
                .hasMessageContaining("ANTHROPIC_ENABLED");
    }

    @Test
    void 둘_다_켜거나_extract_가_꺼져_있으면_통과한다() {
        assertThatCode(() -> new AnthropicProperties(true, true, "key", null, 0, null))
                .doesNotThrowAnyException();
        assertThatCode(() -> new AnthropicProperties(false, false, "", null, 0, null))
                .doesNotThrowAnyException();
    }
}
