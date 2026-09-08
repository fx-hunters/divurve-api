package com.divurve.infra.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.divurve.domain.ai.ExplainSurface;
import com.divurve.domain.port.AiProvider;
import com.divurve.domain.port.TokenUsage;
import com.divurve.domain.port.AiProvider.ExplainContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * {@link ClaudeAiProvider} 테스트 (이슈 #73, 이슈 #135).
 *
 * <p>확인하는 것: (1) {@link ExplainSurface} 가 아는 화면이 <b>전부</b> 실 API 로 가는지,
 * (2) 화면마다 다른 문장 수 규약을 어긴 응답을 그대로 통과시키지 않는지, (3) API 실패를 삼키지 않고
 * 그대로 던지는지. 폴백 판단은 도메인({@code AiService})의 몫이므로 어댑터가 대신 하면 안 된다.
 *
 * <p>(1) 이 이 파일에서 가장 중요한 항목이다 — 이슈 #135 는 이 어댑터가 {@code forecast_summary}
 * 이외 화면을 템플릿으로 넘기던 것이 사용자 화면에 키-값 나열로 드러난 일이었고, 그때 이 테스트는
 * 그 위임을 <b>규약으로 검증하고 있었다.</b>
 */
class ClaudeAiProviderTest {

    private static final Map<String, Object> FACTS = Map.of("current_rate", 1380.5);

    private final ObjectMapper mapper = new ObjectMapper();
    private final StubMessageClient messageClient = new StubMessageClient();
    private final AnthropicProperties props =
            new AnthropicProperties(true, false, "sk-ant-test", null, 0, null, null);

    private final ClaudeAiProvider sut = new ClaudeAiProvider(messageClient, mapper, props);

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

        AiProvider.ExplainResult result = sut.explain(context("forecast_summary"));

        assertThat(result.sentences()).containsExactly("첫째.", "둘째.", "셋째.", "넷째.");
        assertThat(messageClient.systemPrompt).contains("sentence_count 가 지정한 개수");
        assertThat(messageClient.userPrompt).contains("explain_level: simple");
        assertThat(messageClient.userPrompt).contains("sentence_count: 4");
    }

    /**
     * 이슈 #135 의 회귀 방지선. 예전에는 이 호출이 템플릿 제공자로 넘어가 키-값 나열이 돌아왔고,
     * 실 LLM 이 켜져 있는데도 그랬다.
     */
    @Test
    void forecast_이외_화면도_실_API_로_간다() {
        messageClient.response = new ClaudeMessageClient.Completion(
body("첫째.", "둘째.", "셋째."), TokenUsage.of(60, 30));

        AiProvider.ExplainResult result = sut.explain(context("home_market_summary"));

        assertThat(result.sentences()).containsExactly("첫째.", "둘째.", "셋째.");
        assertThat(result.model()).isEqualTo(props.model());
        assertThat(messageClient.userPrompt).contains("surface: home_market_summary");
        assertThat(messageClient.userPrompt).contains("sentence_count: 3");
    }

    @Test
    void 문장_수_규약은_화면마다_다르게_강제된다() {
        messageClient.response = new ClaudeMessageClient.Completion(
body("첫째.", "둘째.", "셋째.", "넷째."), TokenUsage.of(60, 30));

        // forecast 라면 통과할 4문장이지만 xray_exposure 의 규약은 3문장이다.
        assertThatThrownBy(() -> sut.explain(context("xray_exposure")))
                .isInstanceOf(AiResponseFormatException.class)
                .hasMessageContaining("xray_exposure")
                .hasMessageContaining("3문장");
    }

    @Test
    void 문장_수가_4가_아니면_형식_예외를_던진다() {
        messageClient.response = new ClaudeMessageClient.Completion(
body("하나뿐."), TokenUsage.of(10, 5));

        assertThatThrownBy(() -> sut.explain(context("forecast_summary")))
                .isInstanceOf(AiResponseFormatException.class)
                .hasMessageContaining("4문장");
    }

    /**
     * {@code ExplainRequestGuard} 가 앞에서 막으므로 실무에서는 오지 않는 경로다. 그래도 형식
     * 예외로 떨어뜨려 {@code AiService} 의 폴백에 얹는다 — 사용자는 어느 쪽이든 200 과 문장을 받는다.
     */
    @Test
    void 규약이_없는_surface_는_형식_예외를_던진다() {
        assertThatThrownBy(() -> sut.explain(context("profile_fit")))
                .isInstanceOf(AiResponseFormatException.class)
                .hasMessageContaining("profile_fit");
        assertThat(messageClient.systemPrompt).isNull();
    }

    @Test
    void API_실패는_삼키지_않고_그대로_던진다() {
        messageClient.failure = new IllegalStateException("read timed out");

        assertThatThrownBy(() -> sut.explain(context("forecast_summary")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("read timed out");
    }

    @Test
    void context가_null이면_NullPointerException을_던진다() {
        assertThatThrownBy(() -> sut.explain(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void 생성자는_협력자가_null_이면_실패한다() {
        assertThatThrownBy(() -> new ClaudeAiProvider(null, mapper, props))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ClaudeAiProvider(messageClient, null, props))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ClaudeAiProvider(messageClient, mapper, null))
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
}
