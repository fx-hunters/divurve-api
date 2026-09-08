package com.divurve.infra.ai;

import com.anthropic.client.AnthropicClient;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
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
 * <p>확장 사고(thinking)를 켜지 않는다 — 서술은 검증된 {@code facts} 를 문장으로 옮기는 일이고,
 * 추론 토큰은 비용과 지연만 늘린다. 웹 검색 등 서버 툴도 선언하지 않는다: {@code facts} 밖의 사실이
 * 문장에 섞이면 그라운딩(FR-AI-02)이 무너진다.
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
