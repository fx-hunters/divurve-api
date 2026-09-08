package com.divurve.infra.ai;

import com.anthropic.client.AnthropicClient;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.TextBlock;
import com.anthropic.models.messages.Usage;
import com.divurve.domain.port.TokenUsage;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * {@link ClaudeMessageClient} 의 Anthropic 공식 Java SDK 구현 (이슈 #73).
 *
 * <p><b>레이어 어노테이션을 붙이지 않는 이유</b>(CLAUDE.md 4장) — 이 클래스는 도메인 포트의 구현체가
 * 아니라 SDK 호출을 감싼 기술 시임이다. {@code @ExternalAdapter} 를 붙이면 같은 External 레이어인
 * {@link ClaudeAiProvider} 가 이 클래스를 호출하는 형태가 되어 "External 은 UseCase 에서만 접근"
 * 규칙과 충돌한다. 레이어 경계는 {@code ClaudeAiProvider} 하나가 지키고, 이 클래스는 그 안쪽 부품이다.
 * 빈 등록은 {@code AnthropicConfig} 가 한다.
 *
 * <p><b>{@code output_config.effort} 를 반드시 싣는다</b>(이슈 #158). 이 주석은 원래 "확장 사고를
 * 켜지 않는다"고 적혀 있었는데 사실이 아니었다 — 켜지 않은 게 아니라 <b>끄지 않아서 켜져 있었다.</b>
 * {@code claude-opus-5} 는 {@code thinking} 을 생략하면 adaptive thinking 이 돌고 effort 는
 * {@code high} 가 된다(Opus 4.8/4.7 과 반대 동작). 파라미터를 안 보내는 것은 "안 쓴다"가 아니라
 * "서버 기본값을 쓴다"이고, 그 기본값이 서술 한 건을 14.8초로 만들어 전건 폴백을 낳았다.
 * 값은 {@code props.effort()}(기본 {@code low}) 가 정하며 {@code ANTHROPIC_EFFORT} 로 조절한다.
 *
 * <p>웹 검색 등 서버 툴은 선언하지 않는다: {@code facts} 밖의 사실이 문장에 섞이면
 * 그라운딩(FR-AI-02)이 무너진다.
 */
public class AnthropicMessageClient implements ClaudeMessageClient {

    private final AnthropicClient client;
    private final AnthropicProperties props;

    public AnthropicMessageClient(AnthropicClient client, AnthropicProperties props) {
        this.client = Objects.requireNonNull(client, "client");
        this.props = Objects.requireNonNull(props, "props");
    }

    @Override
    public Completion complete(String systemPrompt, String userPrompt) {
        MessageCreateParams params = MessageCreateParams.builder()
            .model(props.model())
            .maxTokens(props.maxTokens())
            .outputConfig(OutputConfig.builder()
                .effort(OutputConfig.Effort.of(props.effort()))
                .build())
            .system(systemPrompt)
            .addUserMessage(userPrompt)
            .build();

        Message message = client.messages().create(params);
        String text = message.content().stream()
            .map(ContentBlock::text)
            .filter(Optional::isPresent)
            .map(Optional::get)
            .map(TextBlock::text)
            .collect(Collectors.joining("\n"));

        return new Completion(text, usageOf(message));
    }

    /**
     * SDK 사용량을 {@link TokenUsage} 로 옮긴다.
     *
     * <p>캐시 토큰 두 항목은 SDK 가 {@code Optional<Long>} 로 준다. 프롬프트 캐싱을 쓰지 않는
     * 지금은 항상 비어 있으므로 {@code null} 로 남긴다 — 0 으로 채우면 캐싱을 켠 뒤 "캐시를 쓰지
     * 않았다" 와 "측정되지 않았다" 가 구분되지 않고, 캐싱은 단가가 달라 비용 계산이 어긋난다.
     */
    private static TokenUsage usageOf(Message message) {
        Usage usage = message.usage();
        return new TokenUsage(
            usage.inputTokens(),
            usage.outputTokens(),
            usage.cacheReadInputTokens().orElse(null),
            usage.cacheCreationInputTokens().orElse(null));
    }
}
