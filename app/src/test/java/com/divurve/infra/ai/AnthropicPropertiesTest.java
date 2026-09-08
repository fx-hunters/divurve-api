package com.divurve.infra.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * {@link AnthropicProperties} 기본값·키 검증·조합 검증 테스트 (이슈 #73, 이슈 #123, 이슈 #158).
 * 기본값은 확정값이므로 상수를 그대로 비교한다 — 값이 바뀌면 테스트가 먼저 알린다.
 */
class AnthropicPropertiesTest {

    @Test
    void 비어_있는_값은_확정_기본값으로_채운다() {
        AnthropicProperties props = new AnthropicProperties(true, false, "key", "  ", 0, "  ", null);

        assertThat(props.model()).isEqualTo(AnthropicProperties.DEFAULT_MODEL);
        assertThat(props.maxTokens()).isEqualTo(AnthropicProperties.DEFAULT_MAX_TOKENS);
        assertThat(props.effort()).isEqualTo(AnthropicProperties.DEFAULT_EFFORT);
        assertThat(props.requestTimeout()).isEqualTo(Duration.ofSeconds(12));
    }

    /**
     * #123 은 값을 조정 가능하게만 만들었고 기본값은 #73 그대로였다. #158 이 그중 둘을 바꾼다 —
     * {@code request-timeout} 은 5초로는 effort {@code high} 의 실측 14.8초를 담을 수 없었고,
     * {@code effort} 는 아예 없어서 서버 기본값 {@code high} 가 적용되고 있었다.
     */
    @Test
    void 기본값은_이슈_158_기준이다() {
        assertThat(AnthropicProperties.DEFAULT_MODEL).isEqualTo("claude-opus-5");
        assertThat(AnthropicProperties.DEFAULT_MAX_TOKENS).isEqualTo(1024);
        assertThat(AnthropicProperties.DEFAULT_EFFORT).isEqualTo("low");
        assertThat(AnthropicProperties.DEFAULT_REQUEST_TIMEOUT).isEqualTo(Duration.ofSeconds(12));
    }

    @Test
    void model_이_null_이어도_기본값으로_채운다() {
        assertThat(new AnthropicProperties(true, false, "key", null, 1, null, Duration.ofSeconds(1))
                .model()).isEqualTo(AnthropicProperties.DEFAULT_MODEL);
    }

    @Test
    void effort_가_null_이어도_기본값으로_채운다() {
        assertThat(new AnthropicProperties(true, false, "key", null, 1, null, null).effort())
                .isEqualTo(AnthropicProperties.DEFAULT_EFFORT);
    }

    @Test
    void 명시한_값은_그대로_둔다() {
        AnthropicProperties props = new AnthropicProperties(
                false, false, "key", "claude-sonnet-5", 512, "medium", Duration.ofSeconds(3));

        assertThat(props.enabled()).isFalse();
        assertThat(props.extractEnabled()).isFalse();
        assertThat(props.model()).isEqualTo("claude-sonnet-5");
        assertThat(props.maxTokens()).isEqualTo(512);
        assertThat(props.effort()).isEqualTo("medium");
        assertThat(props.requestTimeout()).isEqualTo(Duration.ofSeconds(3));
    }

    /** 환경변수는 사람이 손으로 넣는다 — 대소문자와 앞뒤 공백은 오타가 아니라 흔한 입력이다. */
    @Test
    void effort_는_대소문자와_공백을_정규화한다() {
        assertThat(new AnthropicProperties(true, false, "key", null, 0, " XHIGH ", null).effort())
                .isEqualTo("xhigh");
    }

    /**
     * 이슈 #158 — 허용값 밖을 그대로 흘려보내면 API 가 400 을 주고, 그건 {@code AiService} 에서
     * {@code PROVIDER_ERROR} 로 잡혀 조용히 폴백한다. 오타 하나가 "AI 가 가끔 템플릿 문장을 낸다"
     * 로만 보이는, 이 이슈에서 겪은 것과 똑같은 형태의 실패다.
     */
    @Test
    void 허용되지_않는_effort_는_이유를_말하고_기동을_실패시킨다() {
        assertThatThrownBy(() -> new AnthropicProperties(true, false, "key", null, 0, "extreme", null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("effort=extreme")
                .hasMessageContaining("ANTHROPIC_EFFORT");
    }

    @Test
    void 허용되는_effort_는_모두_통과한다() {
        for (String effort : AnthropicProperties.ALLOWED_EFFORTS) {
            assertThat(new AnthropicProperties(true, false, "key", null, 0, effort, null).effort())
                    .isEqualTo(effort);
        }
    }

    @Test
    void requireApiKey_는_설정된_키를_돌려준다() {
        assertThat(new AnthropicProperties(true, false, "sk-ant-test", null, 0, null, null)
                .requireApiKey()).isEqualTo("sk-ant-test");
    }

    @Test
    void requireApiKey_는_키가_비어_있으면_기동을_실패시킨다() {
        assertThatThrownBy(() ->
                new AnthropicProperties(true, false, "  ", null, 0, null, null).requireApiKey())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("api-key");

        assertThatThrownBy(() ->
                new AnthropicProperties(true, false, null, null, 0, null, null).requireApiKey())
                .isInstanceOf(IllegalStateException.class);
    }

    /**
     * 이슈 #123 (3) — 예전에는 이 조합이 {@code NoSuchBeanDefinitionException: ClaudeMessageClient}
     * 로 죽어 운영자에게 "enabled 도 같이 켜야 한다"는 정보가 전혀 없었다.
     */
    @Test
    void extract_만_켜면_이유를_말하고_기동을_실패시킨다() {
        assertThatThrownBy(() -> new AnthropicProperties(false, true, "key", null, 0, null, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("extract-enabled")
                .hasMessageContaining("enabled=false")
                .hasMessageContaining("ANTHROPIC_ENABLED");
    }

    @Test
    void 둘_다_켜거나_extract_가_꺼져_있으면_통과한다() {
        assertThatCode(() -> new AnthropicProperties(true, true, "key", null, 0, null, null))
                .doesNotThrowAnyException();
        assertThatCode(() -> new AnthropicProperties(false, false, "", null, 0, null, null))
                .doesNotThrowAnyException();
    }
}
