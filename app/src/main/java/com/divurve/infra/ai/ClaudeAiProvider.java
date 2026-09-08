package com.divurve.infra.ai;

import com.divurve.common.architecture.ExternalAdapter;
import com.divurve.domain.ai.ExplainSurface;
import com.divurve.domain.port.AiProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Objects;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;

/**
 * Anthropic Claude 를 실제로 호출하는 {@link AiProvider} 어댑터 (이슈 #73).
 *
 * <p><b>범위는 {@link ExplainSurface} 가 아는 화면 전부다</b>(이슈 #135). 예전에는
 * {@code forecast_summary} 하나였고 — 문서상 문장 수 규약이 확정된 화면이 거기뿐이었다 — 나머지는
 * {@code MockAiProvider} 템플릿으로 넘겼다. 그 위임이 사용자에게 어떻게 보였는가가 이슈 #135 다:
 * 홈 화면 설명이 {@code "interval 80 lo는(은) 1313.2211410234067입니다."} 처럼 {@code facts} 의
 * 키-값을 그대로 나열한 문장이었고, 실 LLM 이 <b>켜져 있는데도</b> 그랬다. 지금은 문장 수를
 * {@link ExplainSurface} 가 화면마다 정하므로 규약 없이 붙이는 것이 아니다 — 화면을 늘리려면
 * 그 enum 에 문장 수를 적어야 하고, 아래 검증이 그 수를 그대로 강제한다.
 *
 * <p><b>빈 충돌</b>(이슈 #38 과 같은 유형) — {@code app.external.anthropic.enabled=true} 일 때만
 * 생성되고, 그때는 {@link MockAiProvider} 와 함께 두 개의 {@code AiProvider} 빈이 존재한다.
 * {@code @Primary} 로 이쪽이 주입된다. 꺼져 있으면 이 클래스는 아예 만들어지지 않으므로 Mock 하나만
 * 남아 로컬·테스트의 템플릿 경로를 담당한다.
 *
 * <p><b>여기서 예외를 삼키지 않는다.</b> 타임아웃·429·5xx·형식 위반은 그대로 던지고,
 * 폴백 판단은 {@code AiService} 가 한다 — 폴백은 도메인 정책이지 어댑터의 재량이 아니다.
 *
 * <p><b>호출 메타를 기록하지 않는다</b>(이슈 #143). 예전에는 여기서 {@code log.info} 로 토큰 수를
 * 찍고 버려, 서버 로그가 비용의 유일한 근거였다. 지금은 사용량을 {@link ExplainResult} 에 실어
 * 도메인까지 올려보내고 {@code AiService} 가 {@code ai_call_logs} 에 남긴다. 이유는 둘이다 —
 * ArchUnit 이 {@code @ExternalAdapter} → {@code @PersistenceAdapter} 를 막고(CLAUDE.md 4장),
 * 폴백·캐시 히트·쿼터 차단은 <b>이 어댑터를 아예 타지 않아</b> 여기 기록을 두면 정확히 비용을
 * 아낀 세 경로가 로그에서 사라진다. 프롬프트·응답 전문은 여전히 남기지 않는다(이슈 #56).
 */
@ExternalAdapter
@Primary
@ConditionalOnProperty(prefix = "app.external.anthropic", name = "enabled", havingValue = "true")
public class ClaudeAiProvider implements AiProvider {

    private final ClaudeMessageClient messageClient;
    private final ClaudeExplainPrompt prompt;
    private final AnthropicProperties props;

    public ClaudeAiProvider(
        ClaudeMessageClient messageClient,
        ObjectMapper objectMapper,
        AnthropicProperties props
    ) {
        this.messageClient = Objects.requireNonNull(messageClient, "messageClient");
        this.prompt = new ClaudeExplainPrompt(Objects.requireNonNull(objectMapper, "objectMapper"));
        this.props = Objects.requireNonNull(props, "props");
    }

    @Override
    public ExplainResult explain(ExplainContext context) {
        Objects.requireNonNull(context, "context");

        // ExplainRequestGuard 가 허용 화면만 통과시키므로 여기서 빈 값이 나올 수 없다. 그래도
        // 형식 예외로 던져 폴백 경로에 얹는다 — 어느 쪽이든 사용자는 200 과 문장을 받는다.
        ExplainSurface surface = ExplainSurface.of(context.surface())
            .orElseThrow(() -> new AiResponseFormatException(
                "서술 규약이 없는 surface 다: %s".formatted(context.surface())));

        ClaudeMessageClient.Completion completion =
            messageClient.complete(prompt.system(), prompt.user(context, surface));

        List<String> sentences = prompt.parseSentences(completion.text());
        if (sentences.size() != surface.sentenceCount()) {
            throw new AiResponseFormatException(
                "%s 는 %d문장이어야 하는데 %d문장이 왔다"
                    .formatted(surface.code(), surface.sentenceCount(), sentences.size()));
        }

        return new ExplainResult(sentences, props.model(), completion.usage());
    }
}
