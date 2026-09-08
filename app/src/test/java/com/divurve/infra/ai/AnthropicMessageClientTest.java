package com.divurve.infra.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.anthropic.client.AnthropicClient;
import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.TextBlock;
import com.anthropic.models.messages.ThinkingBlock;
import com.anthropic.models.messages.Usage;
import com.anthropic.services.blocking.MessageService;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * {@link AnthropicMessageClient} 테스트 (이슈 #73).
 *
 * <p>SDK 를 호출하는 유일한 지점이다 — 모델·maxTokens·effort·system·user 가 실제로 요청에 실리는지,
 * 텍스트 블록만 골라 이어붙이는지를 본다. 네트워크는 타지 않는다({@link AnthropicClient} 는 인터페이스).
 */
class AnthropicMessageClientTest {

    private final AnthropicClient client = mock(AnthropicClient.class);
    private final MessageService messageService = mock(MessageService.class);
    private final AnthropicProperties props =
            new AnthropicProperties(true, false, "sk-ant-test", "claude-opus-5", 777, "medium", null);

    private final AnthropicMessageClient sut = new AnthropicMessageClient(client, props);

    private static Message messageOf(List<ContentBlock> blocks, long inputTokens, long outputTokens) {
        return Message.builder()
                .id("msg_test")
                .model("claude-opus-5")
                .role(JsonValue.from("assistant"))
                .type(JsonValue.from("message"))
                .content(blocks)
                .usage(usage(inputTokens, outputTokens))
                .container(Optional.empty())
                .stopDetails(Optional.empty())
                .stopReason(Optional.empty())
                .stopSequence(Optional.empty())
                .build();
    }

    /** SDK 는 선택 필드도 명시를 요구한다 — 테스트에서는 전부 비워 둔다. */
    private static Usage usage(long inputTokens, long outputTokens) {
        return Usage.builder()
                .inputTokens(inputTokens)
                .outputTokens(outputTokens)
                .cacheCreation(Optional.empty())
                .cacheCreationInputTokens(Optional.empty())
                .cacheReadInputTokens(Optional.empty())
                .inferenceGeo(Optional.empty())
                .outputTokensDetails(Optional.empty())
                .serverToolUse(Optional.empty())
                .serviceTier(Optional.empty())
                .build();
    }

    private static ContentBlock textBlock(String text) {
        return ContentBlock.ofText(TextBlock.builder().text(text).citations(List.of()).build());
    }

    @Test
    void complete_는_설정한_모델과_프롬프트로_호출한다() {
        Message message = messageOf(List.of(textBlock("{\"sentences\": []}")), 120, 45);
        when(client.messages()).thenReturn(messageService);
        when(messageService.create(any(MessageCreateParams.class))).thenReturn(message);

        ClaudeMessageClient.Completion completion = sut.complete("규약", "facts");

        ArgumentCaptor<MessageCreateParams> captor = ArgumentCaptor.forClass(MessageCreateParams.class);
        org.mockito.Mockito.verify(messageService).create(captor.capture());
        MessageCreateParams params = captor.getValue();

        assertThat(params.model().asString()).contains("claude-opus-5");
        assertThat(params.maxTokens()).isEqualTo(777);
        // 이슈 #158 — 설정값이 실제로 요청에 실리는지가 핵심이다. 보내지 않으면 서버 기본값
        // high 가 적용되고, 그게 forecast_summary 전건 폴백의 원인이었다. props 에 low 가 아닌
        // medium 을 넣어 둔 것은 상수를 박아 넣은 구현이 이 단언을 통과하지 못하게 하기 위해서다.
        assertThat(params.outputConfig().orElseThrow().effort().orElseThrow().asString())
                .isEqualTo("medium");
        assertThat(params.system()).isPresent();
        assertThat(completion.text()).isEqualTo("{\"sentences\": []}");
        assertThat(completion.usage().inputTokens()).isEqualTo(120);
        assertThat(completion.usage().outputTokens()).isEqualTo(45);
    }

    @Test
    void complete_는_텍스트가_아닌_블록을_건너뛰고_이어붙인다() {
        ContentBlock thinking = ContentBlock.ofThinking(
                ThinkingBlock.builder().signature("sig").thinking("속으로 생각").build());
        Message message = messageOf(List.of(thinking, textBlock("앞"), textBlock("뒤")), 1, 2);
        when(client.messages()).thenReturn(messageService);
        when(messageService.create(any(MessageCreateParams.class))).thenReturn(message);

        assertThat(sut.complete("규약", "facts").text()).isEqualTo("앞\n뒤");
    }

    @Test
    void 생성자는_협력자가_null_이면_실패한다() {
        assertThatThrownBy(() -> new AnthropicMessageClient(null, props))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new AnthropicMessageClient(client, null))
                .isInstanceOf(NullPointerException.class);
    }
}
