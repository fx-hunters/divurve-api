package com.divurve.infra.ai;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Anthropic Claude API 접속 설정 (이슈 #73, 이슈 #123).
 *
 * <p>기본값은 이슈 #73 "확정 사항" 표를 그대로 옮긴 것이다. 특히 <b>타임아웃 5초</b>는 AI 서술이
 * 동기 HTTP 요청 안에서 일어나기 때문이다 — SDK 기본값(10분)을 그대로 두면 Anthropic 지연이 그대로
 * 우리 응답 지연이 되어 NFR-AI-03(AI 실패가 서비스 실패가 되지 않는다)을 지킬 수 없다.
 *
 * <p><b>기본값은 코드에, 실제 값은 환경변수에</b>(이슈 #123). {@code model}·{@code max-tokens}·
 * {@code request-timeout} 은 운영에서 손봐야 하는 값인데 {@code application.yml} 에 상수로 박혀 있었다 —
 * 타임아웃 하나 올려 보는 데도 코드 수정과 재배포가 필요했다. 여기 상수는 <b>주입값이 비었을 때의
 * 기본</b>일 뿐이고, 실제 값은 {@code ANTHROPIC_*} 환경변수로 바꾼다.
 *
 * <p>SDK 자체 재시도는 {@code AnthropicConfig} 에서 <b>0</b> 으로 고정한다. 기본값 2 를 두면
 * {@code AiService} 의 도메인 재시도(최대 2회)와 곱해져 최악 6회 호출이 된다 — 과금도 지연도 6배다.
 *
 * <p><b>총예산({@code total-budget})은 여기 없다</b>(이슈 #123 (2)). 예전에는 이 레코드가 값을
 * 바인딩만 하고 아무도 읽지 않았고, 실제 예산 판정은 {@code AiService} 의 별도 상수가 했다 — 값이
 * 우연히 같았을 뿐 한쪽만 바꾸면 조용히 어긋났다. 지금은 {@code AiService} 하나만 그 프로퍼티를
 * 읽는다(재시도까지 포함한 상한은 인프라 설정이 아니라 도메인 정책이다).
 *
 * @param enabled        실 API 사용 여부. {@code false}(기본)면 {@code MockAiProvider} 가 그대로 쓰인다
 * @param extractEnabled 추출(extract) 경로 사용 여부. {@code true} 면 {@code enabled} 도 켜져 있어야 한다
 * @param apiKey         발급 API 키 (환경변수 {@code ANTHROPIC_API_KEY} 로 주입)
 * @param model          모델 ID. 기본 {@value #DEFAULT_MODEL}
 * @param maxTokens      응답 상한 토큰. 서술은 4문장이므로 크게 잡을 이유가 없다
 * @param requestTimeout 요청 1건당 타임아웃
 */
@ConfigurationProperties(prefix = "app.external.anthropic")
public record AnthropicProperties(
    boolean enabled,
    boolean extractEnabled,
    String apiKey,
    String model,
    int maxTokens,
    Duration requestTimeout
) {

    /** 이슈 #73 확정 — 두 용도 모두 상위 모델을 쓴다. */
    public static final String DEFAULT_MODEL = "claude-opus-5";

    static final int DEFAULT_MAX_TOKENS = 1024;
    static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(5);

    public AnthropicProperties {
        if (model == null || model.isBlank()) {
            model = DEFAULT_MODEL;
        }
        if (maxTokens <= 0) {
            maxTokens = DEFAULT_MAX_TOKENS;
        }
        if (requestTimeout == null) {
            requestTimeout = DEFAULT_REQUEST_TIMEOUT;
        }
        requireExtractPrerequisite(enabled, extractEnabled);
    }

    /**
     * {@code extract-enabled} 의 전제조건을 확인한다 (이슈 #123 (3)).
     *
     * <p>{@code extract-enabled=true} 인데 {@code enabled=false} 면 추출기가 요구하는
     * {@code ClaudeMessageClient} 빈이 존재하지 않아 {@code NoSuchBeanDefinitionException} 으로
     * 죽었다 — 운영자에게는 "무엇을 같이 켜야 하는지"가 전혀 보이지 않는 메시지다. Render 처럼
     * 기동 실패가 <b>옛 인스턴스 유지</b>로 나타나는 환경에서는 "재배포했는데 아무것도 안 바뀜"으로만
     * 보여 원인 추적이 매우 어렵다. {@link #requireApiKey()} 와 같은 수준으로 이유를 말한다.
     *
     * <p>규칙은 여기 한 번만 적고 두 곳에서 부른다 — 이 레코드의 생성자(직접 만든 인스턴스도
     * 유효하도록)와 {@code AnthropicConfig} 의 {@code BeanFactoryPostProcessor}(빈 생성 순서에
     * 기대지 않도록. 추출기 빈이 먼저 만들어지면 {@code NoSuchBeanDefinitionException} 이 앞지른다).
     *
     * @param enabled        {@code app.external.anthropic.enabled}
     * @param extractEnabled {@code app.external.anthropic.extract-enabled}
     */
    public static void requireExtractPrerequisite(boolean enabled, boolean extractEnabled) {
        if (extractEnabled && !enabled) {
            throw new IllegalStateException(
                "app.external.anthropic.extract-enabled=true 인데 enabled=false 다 — 추출기가 쓰는 "
                    + "ClaudeMessageClient 는 enabled=true 일 때만 만들어진다 "
                    + "(ANTHROPIC_ENABLED=true 로 함께 켜거나 ANTHROPIC_EXTRACT_ENABLED=false 로 둔다)");
        }
    }

    /**
     * 키를 확인한다. {@code enabled=true} 인데 키가 없으면 <b>기동 시점에</b> 실패시킨다 —
     * 조용히 Mock 으로 되돌아가면 "실 API 를 켰다고 믿는 상태로 템플릿 문장이 나가는" 상황이 되고,
     * 그건 로그를 봐야만 알 수 있다. 켜지 않으면 이 메서드는 호출되지 않는다.
     *
     * @return 비어 있지 않은 API 키
     */
    public String requireApiKey() {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(
                "app.external.anthropic.enabled=true 인데 api-key 가 비어 있다 "
                    + "(ANTHROPIC_API_KEY 환경변수를 주입하거나 enabled=false 로 두고 Mock 을 쓴다)");
        }
        return apiKey;
    }
}
