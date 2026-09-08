package com.divurve.infra.ai;

import java.time.Duration;
import java.util.Locale;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Anthropic Claude API 접속 설정 (이슈 #73, 이슈 #123).
 *
 * <p>기본값은 이슈 #73 "확정 사항" 표를 그대로 옮긴 것이다. 타임아웃을 SDK 기본값(10분)에 맡기지
 * 않는 이유는 AI 서술이 동기 HTTP 요청 안에서 일어나기 때문이다 — Anthropic 지연이 그대로 우리 응답
 * 지연이 되어 NFR-AI-03(AI 실패가 서비스 실패가 되지 않는다)을 지킬 수 없다.
 *
 * <p><b>{@code effort} 는 이 설정의 나머지와 성격이 다르다</b>(이슈 #158). 다른 값들은 "얼마나
 * 기다릴까"를 정하지만 이것은 <b>얼마나 걸리게 만들까</b>를 정한다. {@code claude-opus-5} 는
 * {@code thinking} 을 생략하면 adaptive thinking 이 켜지고 effort 는 {@code high} 가 되는데
 * (Opus 4.8/4.7 과 반대 동작), 확정된 {@code facts} 를 4문장으로 옮기는 일에는 추론이 필요 없다.
 * 그런데 이 값을 <b>요청에 싣지 않아</b> 서버 기본값 {@code high} 로 매 호출이 돌았고, 실측 14.8초로
 * 당시 타임아웃 5초를 항상 넘겨 {@code forecast_summary} 전건이 폴백했다.
 *
 * <p><b>thinking 을 끄지는 않는다.</b> Opus 5 에서 {@code thinking} 을 끄면 {@code <thinking>} 태그가
 * 본문에 섞여 나오는 실패 모드가 있고, 그건 곧 {@link ClaudeExplainPrompt} 의 JSON 파싱 실패다 —
 * 지연을 줄이려다 폴백 사유만 바꾸는 셈이 된다. effort 를 낮추면 같은 효과를 안전하게 얻는다.
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
 * @param effort         추론 강도. 기본 {@value #DEFAULT_EFFORT}, 허용값은 {@link #ALLOWED_EFFORTS}
 * @param requestTimeout 요청 1건당 타임아웃
 */
@ConfigurationProperties(prefix = "app.external.anthropic")
public record AnthropicProperties(
    boolean enabled,
    boolean extractEnabled,
    String apiKey,
    String model,
    int maxTokens,
    String effort,
    Duration requestTimeout
) {

    /** 이슈 #73 확정 — 두 용도 모두 상위 모델을 쓴다. */
    public static final String DEFAULT_MODEL = "claude-opus-5";

    /**
     * 이슈 #158 — 서술은 확정된 {@code facts} 를 문장으로 옮기는 일이라 추론 깊이가 결과를 바꾸지
     * 않는다. 지연·토큰·비용만 늘 뿐이므로 가장 낮은 단계에서 시작한다.
     */
    public static final String DEFAULT_EFFORT = "low";

    /** API 가 받는 effort 단계. 오타를 <b>기동 시점에</b> 잡기 위해 여기서 목록으로 고정한다. */
    static final Set<String> ALLOWED_EFFORTS = Set.of("low", "medium", "high", "xhigh", "max");

    static final int DEFAULT_MAX_TOKENS = 1024;

    /**
     * 이슈 #158 — 예전 값은 5초였고, effort 를 싣지 않아 매 호출이 {@code high} 로 돌던 시절에는
     * 실측 14.8초라 전건이 타임아웃이었다. effort 를 낮춘 뒤에도 콜드 스타트와 지연 스파이크는
     * 남으므로 여유를 둔다. 최악 소요시간은 이 값 하나가 아니라 총예산과 함께 정해진다 —
     * {@code AiService} 의 {@code total-budget} 설명을 함께 본다.
     */
    static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(12);

    public AnthropicProperties {
        if (model == null || model.isBlank()) {
            model = DEFAULT_MODEL;
        }
        if (maxTokens <= 0) {
            maxTokens = DEFAULT_MAX_TOKENS;
        }
        effort = normalizeEffort(effort);
        if (requestTimeout == null) {
            requestTimeout = DEFAULT_REQUEST_TIMEOUT;
        }
        requireExtractPrerequisite(enabled, extractEnabled);
    }

    /**
     * effort 값을 정규화하고 검증한다 (이슈 #158).
     *
     * <p><b>모르는 값을 그대로 흘려보내지 않는다.</b> API 는 잘못된 effort 에 400 을 주고, 그건
     * {@code AiService} 에서 {@code PROVIDER_ERROR} 로 잡혀 조용히 폴백한다 — 오타 하나가 "AI 가
     * 가끔 템플릿 문장을 낸다"로만 보이는, 이 이슈에서 겪은 것과 똑같은 형태의 실패다. 기동 때
     * 이유를 말하고 죽는 편이 낫다({@link #requireApiKey()} 와 같은 판단).
     *
     * @param effort 주입된 값. {@code null} 이거나 비어 있으면 {@value #DEFAULT_EFFORT}
     * @return 소문자로 정규화된 허용 effort
     */
    private static String normalizeEffort(String effort) {
        if (effort == null || effort.isBlank()) {
            return DEFAULT_EFFORT;
        }
        String normalized = effort.strip().toLowerCase(Locale.ROOT);
        if (!ALLOWED_EFFORTS.contains(normalized)) {
            throw new IllegalStateException(
                ("app.external.anthropic.effort=%s 는 허용되지 않는다 — %s 중 하나여야 한다 "
                    + "(ANTHROPIC_EFFORT 환경변수를 확인한다)").formatted(effort, ALLOWED_EFFORTS));
        }
        return normalized;
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
