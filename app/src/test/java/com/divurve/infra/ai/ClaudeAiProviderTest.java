package com.divurve.infra.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.divurve.domain.ai.AiService;
import com.divurve.domain.port.AiProvider;
import com.divurve.domain.port.TokenUsage;
import com.divurve.domain.port.AiProvider.ExplainContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * {@link ClaudeAiProvider} 테스트 (이슈 #73).
 *
 * <p>확인하는 것: (1) {@code forecast_summary} 만 실 API 로 가고 나머지는 템플릿으로 넘어가는지,
 * (2) 4문장 규약을 어긴 응답을 그대로 통과시키지 않는지, (3) API 실패를 삼키지 않고 그대로 던지는지.
 * 폴백 판단은 도메인({@code AiService})의 몫이므로 어댑터가 대신 하면 안 된다.
 */
class ClaudeAiProviderTest {

    private static final Map<String, Object> FACTS = Map.of("current_rate", 1380.5);

    private final ObjectMapper mapper = new ObjectMapper();
    private final StubMessageClient messageClient = new StubMessageClient();
    private final StubTemplateProvider templateProvider = new StubTemplateProvider();
    private final AnthropicProperties props =
            new AnthropicProperties(true, false, "sk-ant-test", null, 0, null, null);

    private final ClaudeAiProvider sut =
            new ClaudeAiProvider(messageClient, templateProvider, mapper, props);

    private static ExplainContext context(String surface) {
        return new ExplainContext(surface, FACTS, "simple", "plain");
    }

    private static String body(String... sentences) {
        List<String> quoted = new ArrayList<>();
        for (String sentence : sentences) {
            quoted.add("\"" + sentence + "\"");
        }
        return "{\"sentences\": [" + String.join(",", quoted) + "]}";
    }

    @Test
    void forecast_summary_는_실_API_응답을_문장으로_돌려준다() {
        messageClient.response = new ClaudeMessageClient.Completion(
body("첫째.", "둘째.", "셋째.", "넷째."), TokenUsage.of(100, 50));

        AiProvider.ExplainResult result = sut.explain(context(AiService.SURFACE_FORECAST_SUMMARY));

        assertThat(result.sentences()).containsExactly("첫째.", "둘째.", "셋째.", "넷째.");
        assertThat(messageClient.systemPrompt).contains("정확히 4개의 문장");
        assertThat(messageClient.userPrompt).contains("explain_level: simple");
        assertThat(templateProvider.calls).isZero();
    }

    @Test
    void 범위_밖_화면은_템플릿_제공자에게_넘긴다() {
        AiProvider.ExplainResult result = sut.explain(context("profile_fit"));

        assertThat(result.sentences()).containsExactly("템플릿 문장.");
        assertThat(templateProvider.calls).isEqualTo(1);
        assertThat(messageClient.systemPrompt).isNull();
    }

    @Test
    void 문장_수가_4가_아니면_형식_예외를_던진다() {
        messageClient.response = new ClaudeMessageClient.Completion(
body("하나뿐."), TokenUsage.of(10, 5));

        assertThatThrownBy(() -> sut.explain(context(AiService.SURFACE_FORECAST_SUMMARY)))
                .isInstanceOf(AiResponseFormatException.class)
                .hasMessageContaining("4문장");
    }

    @Test
    void API_실패는_삼키지_않고_그대로_던진다() {
        messageClient.failure = new IllegalStateException("read timed out");

        assertThatThrownBy(() -> sut.explain(context(AiService.SURFACE_FORECAST_SUMMARY)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("read timed out");
    }

    @Test
    void context가_null이면_NullPointerException을_던진다() {
        assertThatThrownBy(() -> sut.explain(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void 생성자는_협력자가_null_이면_실패한다() {
        assertThatThrownBy(() -> new ClaudeAiProvider(null, templateProvider, mapper, props))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ClaudeAiProvider(messageClient, null, mapper, props))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ClaudeAiProvider(messageClient, templateProvider, null, props))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ClaudeAiProvider(messageClient, templateProvider, mapper, null))
                .isInstanceOf(NullPointerException.class);
    }

    private static final class StubMessageClient implements ClaudeMessageClient {

        private Completion response;
        private RuntimeException failure;
        private String systemPrompt;
        private String userPrompt;

        @Override
        public Completion complete(String systemPrompt, String userPrompt) {
            this.systemPrompt = systemPrompt;
            this.userPrompt = userPrompt;
            if (failure != null) {
                throw failure;
            }
            return response;
        }
    }

    private static final class StubTemplateProvider implements AiProvider {

        private int calls;

        @Override
        public ExplainResult explain(ExplainContext context) {
            calls++;
            return ExplainResult.withoutLlm(List.of("템플릿 문장."));
        }
    }
}
