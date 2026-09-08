package com.divurve.infra.ai;

import com.divurve.common.architecture.ExternalAdapter;
import com.divurve.domain.port.EconEventExtractor;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;

/**
 * Anthropic Claude 로 비정형 원문에서 경제 이벤트 후보를 추출하는 {@link EconEventExtractor}
 * 어댑터 (이슈 #74).
 *
 * <p><b>#73 에서 만든 시임을 재사용한다</b>(이슈 #74 제약 7) — {@link ClaudeMessageClient} 를
 * 어댑터가 직접 들면 SDK 없이 테스트할 수 없어 커버리지 100% 게이트(CLAUDE.md 8장)를 통과할
 * 방법이 없다. 프롬프트 조립·응답 파싱은 {@link ClaudeExtractPrompt} 에 위임한다(SRP).
 *
 * <p><b>기본은 꺼짐</b> — {@code app.external.anthropic.extract-enabled=true} 일 때만 이 빈이
 * 만들어진다. 대상 뉴스 소스가 아직 팀 결정 전이라({@code MockRawArticleSource} 는 시연용
 * 자리표시자) 배치가 기본으로 실 API 를 호출하게 둘 수 없다.
 *
 * <p><b>꺼져 있어도 기동은 된다</b> — {@code EconEventIngestionService} 가 {@link EconEventExtractor}
 * 빈을 요구하므로, 이 빈이 없을 때는 {@link com.divurve.infra.event.NoOpEconEventExtractor} 가
 * 대신 등록된다({@link ClaudeAiProvider}/{@code MockAiProvider} 공존 패턴과 동일, 이슈 #73).
 * 둘 다 있을 때는 {@code @Primary} 로 이쪽이 주입된다.
 *
 * <p><b>검증은 여기서 하지 않는다.</b> 타입·ENUM·범위·날짜 신뢰성 검증은 도메인
 * ({@code EconEventValidator})의 책임이다 — 어댑터는 모델이 돌려준 문자열을 그대로 옮긴다.
 * 응답이 고정 스키마를 벗어나면(JSON 파싱 실패 등) {@link AiResponseFormatException} 을 던지고,
 * 그 원문 하나는 통째로 버려진다(이슈 #74 "부분 실패 허용" — 다른 원문 처리에는 영향 없다).
 */
@ExternalAdapter
@Primary
@ConditionalOnProperty(prefix = "app.external.anthropic", name = "extract-enabled", havingValue = "true")
public class ClaudeEconEventExtractor implements EconEventExtractor {

    private static final Logger log = LoggerFactory.getLogger(ClaudeEconEventExtractor.class);

    private final ClaudeMessageClient messageClient;
    private final ClaudeExtractPrompt prompt;
    private final AnthropicProperties props;

    public ClaudeEconEventExtractor(
        ClaudeMessageClient messageClient,
        ObjectMapper objectMapper,
        AnthropicProperties props
    ) {
        this.messageClient = Objects.requireNonNull(messageClient, "messageClient");
        this.prompt = new ClaudeExtractPrompt(Objects.requireNonNull(objectMapper, "objectMapper"));
        this.props = Objects.requireNonNull(props, "props");
    }

    @Override
    public ExtractOutcome extract(RawArticle article) {
        Objects.requireNonNull(article, "article");

        ClaudeMessageClient.Completion completion =
            messageClient.complete(prompt.system(), prompt.user(article));

        List<ExtractedEvent> events = prompt.parseEvents(completion.text());
        logExtractedCount(article, events.size());
        return new ExtractOutcome(events, props.model(), completion.usage());
    }

    /**
     * 추출 건수만 로그로 남긴다 (이슈 #74 제약 6).
     *
     * <p><b>토큰 수는 더 이상 여기서 찍지 않는다</b>(이슈 #143) — 사용량은
     * {@link ExtractOutcome} 에 실려 도메인으로 올라가 {@code ai_call_logs} 에 기록된다. 추출
     * 건수는 비용이 아니라 이 어댑터의 동작 자체를 보는 값이라 로그에 남긴다.
     */
    private void logExtractedCount(RawArticle article, int extractedCount) {
        log.info("econ_event_extracted source_url={} model={} extracted_count={}",
            article.sourceUrl(), props.model(), extractedCount);
    }
}
