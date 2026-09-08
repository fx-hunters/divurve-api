package com.divurve.infra.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.divurve.domain.port.AiProvider;
import com.divurve.domain.port.EconEventExtractor;
import com.divurve.infra.config.AnthropicConfig;
import com.divurve.infra.event.NoOpEconEventExtractor;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * 실 어댑터와 Mock 의 빈 배선 테스트 (이슈 #73 제약 1, 이슈 #38 과 같은 유형)이자,
 * <b>{@code enabled} × {@code extract-enabled} × {@code api-key} 조합별 기동 결과 표</b>
 * (이슈 #123) 자체다.
 *
 * <p><b>왜 컨텍스트를 띄워 보나</b> — {@code AiProvider} 구현체가 둘이 되는 순간이 여기다.
 * 단위 테스트로는 {@code @ConditionalOnProperty}·{@code @Primary} 가 실제로 먹는지 알 수 없고,
 * 틀렸을 때 나타나는 증상은 컴파일 오류가 아니라 <b>기동 실패</b>다. Render 처럼 기동 실패가
 * 옛 인스턴스 유지로 나타나는 환경에서는 그게 "재배포했는데 아무것도 안 바뀜"으로만 보인다.
 *
 * <p>{@code ApplicationContextSmokeTest} 에 프로퍼티 오버라이드를 더하지 말라는 그 클래스의 규칙을
 * 지키기 위해 전체 컨텍스트 대신 {@link ApplicationContextRunner} 를 쓴다 — 컨텍스트 캐시가
 * 갈라지지 않는다.
 */
class AnthropicWiringTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(PropertyPlaceholderAutoConfiguration.class))
            .withBean(ObjectMapper.class)
            .withUserConfiguration(MockAiProvider.class, ClaudeAiProvider.class,
                    NoOpEconEventExtractor.class, ClaudeEconEventExtractor.class, AnthropicConfig.class);

    /** 표 1행 — {@code false / false} → 기동 성공(NoOp). */
    @Test
    void 기본값에서는_Mock_하나만_뜬다() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(AiProvider.class);
            assertThat(context.getBean(AiProvider.class)).isInstanceOf(MockAiProvider.class);
            assertThat(context).doesNotHaveBean(ClaudeMessageClient.class);
            // 추출기도 자리표시자 하나만 남는다.
            assertThat(context).hasSingleBean(EconEventExtractor.class);
            assertThat(context.getBean(EconEventExtractor.class))
                    .isInstanceOf(NoOpEconEventExtractor.class);
        });
    }

    /**
     * 표 2행 — {@code false / true} → 기동 실패.
     *
     * <p>이슈 #123 이전에는 {@code NoSuchBeanDefinitionException: ClaudeMessageClient} 였다.
     * 지금은 <b>무엇을 같이 켜야 하는지</b>를 말하고 실패한다 — {@code requireApiKey()} 와 같은 수준이다.
     */
    @Test
    void extract_만_켜면_이유를_말하고_기동에_실패한다() {
        runner.withPropertyValues("app.external.anthropic.extract-enabled=true")
                .run(context -> assertThat(context).hasFailed()
                        .getFailure()
                        // BFPP 단계에서 그대로 올라온다 — 빈 생성 예외로 감싸이지 않는다.
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("extract-enabled")
                        .hasMessageContaining("ANTHROPIC_ENABLED"));
    }

    /** 표 3행 — {@code true / true / 키 없음} → 기동 실패. */
    @Test
    void 켰는데_키가_없으면_기동에_실패한다() {
        runner.withPropertyValues("app.external.anthropic.enabled=true")
                .run(context -> assertThat(context).hasFailed()
                        .getFailure()
                        .rootCause()
                        .hasMessageContaining("api-key"));
    }

    /** 표 4행 — {@code true / true / 키 있음} → 기동 성공. */
    @Test
    void 켜면_두_구현체가_공존하고_실_어댑터가_주입된다() {
        runner.withPropertyValues(
                        "app.external.anthropic.enabled=true",
                        "app.external.anthropic.extract-enabled=true",
                        "app.external.anthropic.api-key=sk-ant-test")
                .run(context -> {
                    // 기동이 실패하지 않는다 — @Primary 가 NoUniqueBeanDefinitionException 을 막는다.
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBeansOfType(AiProvider.class)).hasSize(2);
                    assertThat(context.getBean(AiProvider.class)).isInstanceOf(ClaudeAiProvider.class);
                    // Mock 은 살아남아 forecast_summary 이외 화면을 계속 담당한다.
                    assertThat(context).hasSingleBean(MockAiProvider.class);
                    assertThat(context.getBeansOfType(EconEventExtractor.class)).hasSize(2);
                    assertThat(context.getBean(EconEventExtractor.class))
                            .isInstanceOf(ClaudeEconEventExtractor.class);
                });
    }

    /**
     * 운영에서 조정해야 하는 값이 실제로 환경변수로 들어오는지 확인한다 (이슈 #123 (1)).
     * 예전에는 {@code model}·{@code max-tokens}·{@code request-timeout} 이 {@code application.yml}
     * 에 상수로 박혀 있어, 타임아웃 하나 올려 보는 데도 코드 수정과 재배포가 필요했다.
     */
    @Test
    void 모델과_토큰과_타임아웃은_설정으로_덮어쓸_수_있다() {
        runner.withPropertyValues(
                        "app.external.anthropic.enabled=true",
                        "app.external.anthropic.api-key=sk-ant-test",
                        "app.external.anthropic.model=claude-sonnet-5",
                        "app.external.anthropic.max-tokens=256",
                        "app.external.anthropic.request-timeout=20s")
                .run(context -> {
                    AnthropicProperties props = context.getBean(AnthropicProperties.class);
                    assertThat(props.model()).isEqualTo("claude-sonnet-5");
                    assertThat(props.maxTokens()).isEqualTo(256);
                    assertThat(props.requestTimeout()).isEqualTo(Duration.ofSeconds(20));
                });
    }
}
