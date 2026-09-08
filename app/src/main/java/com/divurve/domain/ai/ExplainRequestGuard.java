package com.divurve.domain.ai;

import com.divurve.common.architecture.UseCase;
import com.divurve.common.exception.InvalidRequestException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.beans.factory.annotation.Value;

/**
 * {@code POST /ai/explain} 의 입력 상한과 어휘를 강제하고, {@code facts} 를 정규화한다 (이슈 #139).
 *
 * <p><b>왜 필요한가.</b> {@code facts} 는 클라이언트가 보내는 임의 JSON 이고
 * {@code ClaudeExplainPrompt} 가 그것을 프롬프트에 <b>그대로</b> 싣는다. 상한이 없으면 수 MB JSON
 * 하나로 입력 토큰이 폭발하는데, 모델은 {@code claude-opus-5} 다 — 즉 입력 크기가 곧 청구액이다.
 * {@code surface} 도 자유 문자열이라 아무 값이나 실 API 호출을 뚫었다.
 *
 * <p><b>400 이 FR-AI-06 과 충돌하지 않는다.</b> "AI 실패는 서비스 실패가 아니다" 는 <i>호출해 본
 * 결과</i>가 나빴을 때의 규칙이다. 상한을 넘긴 요청은 AI 실패가 아니라 <b>잘못된 요청</b>이고,
 * 기존 {@code AiController#validateExplainRequest} 도 같은 이유로 400 을 낸다.
 *
 * <p><b>검사 순서가 곧 안전장치다.</b> 깊이·항목 수를 먼저 <b>순회 중에</b> 보고, 통과한 것만
 * 직렬화한다. 순서를 뒤집어 "직렬화한 뒤 길이를 본다" 로 하면 10만 노드 맵을 문자열로 만드는
 * 비용을 이미 치른 뒤에 거절하게 된다 — 막으려던 것이 그 비용이다.
 *
 * <p><b>정규화 결과를 돌려주는 이유</b> — 같은 문자열이 응답 캐시 키의 재료가 된다
 * ({@code AiService}). {@code Map} 의 순회 순서에 의존하면 <b>내용이 같은데 키가 달라져</b> 캐시가
 * 조용히 빗나가므로, 키를 정렬해 직렬화한 것만 쓴다. 검증과 캐시 키가 같은 한 번의 직렬화를
 * 공유하는 것이기도 하다.
 *
 * <p>상한은 프로퍼티로 뺀다 — 새 화면의 {@code facts} 모양이 상한에 걸렸을 때 배포 없이 올릴 수
 * 있어야 한다({@code app.external.anthropic.total-budget} 을 프로퍼티로 뺀 이슈 #123 과 같은 이유).
 */
@UseCase
public class ExplainRequestGuard {

    /**
     * 서술을 허용하는 화면. <b>여기 없는 값은 400 이다.</b>
     *
     * <p>{@code home_market_summary} 가 함께 있는 이유 — 프론트가 <b>이미 두 화면에서</b> 부른다
     * (예측 화면 {@code forecast-screen.tsx}, 홈 시장 요약 {@code market-summary-section.tsx}).
     * 백엔드 코드에는 {@code forecast_summary} 상수만 있어 하나로 보이지만, 그 하나만 허용하면
     * 홈 화면의 설명이 즉시 400 으로 깨진다. 화면을 늘릴 때는 여기에 값을 더해야 한다.
     */
    public static final Set<String> ALLOWED_SURFACES = Set.of(
            AiService.SURFACE_FORECAST_SUMMARY, AiService.SURFACE_HOME_MARKET_SUMMARY);

    /**
     * 정규화된 {@code facts} JSON 의 최대 길이. 실제 화면이 보내는 {@code facts} 는 200자 안쪽이므로
     * 스무 배 여유다 — 정상 요청을 막지 않으면서 "수 MB JSON 한 방"은 확실히 거른다.
     */
    static final String DEFAULT_MAX_FACTS_CHARS = "4096";

    /** {@code facts} 안의 전체 노드 수(스칼라 + 컨테이너). 길이만으로는 못 막는 넓고 얕은 맵을 거른다. */
    static final String DEFAULT_MAX_FACTS_NODES = "128";

    /**
     * 컨테이너 중첩 허용 단계. {@code facts} 자체가 1단계, {@code interval_80} 이 2단계다.
     * Jackson 의 기본 파싱 한계는 1,000단계라 여기서 막지 않으면 그 깊이가 그대로 들어온다.
     */
    static final String DEFAULT_MAX_FACTS_DEPTH = "4";

    /** 거절 메시지에 되돌려 적는 {@code surface} 값의 길이 상한 — 통째로 되비추지 않는다. */
    private static final int SURFACE_ECHO_LIMIT = 40;

    private final ObjectWriter canonicalWriter;
    private final int maxFactsChars;
    private final int maxFactsNodes;
    private final int maxFactsDepth;

    public ExplainRequestGuard(
            ObjectMapper objectMapper,
            @Value("${app.ai.explain.max-facts-chars:" + DEFAULT_MAX_FACTS_CHARS + "}")
            int maxFactsChars,
            @Value("${app.ai.explain.max-facts-nodes:" + DEFAULT_MAX_FACTS_NODES + "}")
            int maxFactsNodes,
            @Value("${app.ai.explain.max-facts-depth:" + DEFAULT_MAX_FACTS_DEPTH + "}")
            int maxFactsDepth) {
        // 전역 ObjectMapper 를 건드리지 않는다 — 설정을 바꾸면 앱 전체 직렬화가 함께 바뀐다.
        // INDENT_OUTPUT 을 명시적으로 끄는 것은 전역 설정이 나중에 바뀌어도 캐시 키가 흔들리지
        // 않게 하기 위함이다. 들여쓰기 하나로 같은 facts 가 다른 키가 된다.
        this.canonicalWriter = Objects.requireNonNull(objectMapper, "objectMapper")
                .writer()
                .with(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                .without(SerializationFeature.INDENT_OUTPUT);
        this.maxFactsChars = maxFactsChars;
        this.maxFactsNodes = maxFactsNodes;
        this.maxFactsDepth = maxFactsDepth;
    }

    /**
     * 어휘·상한을 검사하고 {@code facts} 를 정규화한다.
     *
     * @param surface 서술 대상 화면
     * @param facts   엔진이 만든 사실
     * @return 키를 정렬해 직렬화한 {@code facts} — 응답 캐시 키의 재료
     * @throws InvalidRequestException 허용 화면이 아니거나 상한을 넘겼을 때 (400)
     */
    public String canonicalize(String surface, Map<String, Object> facts) {
        Objects.requireNonNull(surface, "surface");
        Objects.requireNonNull(facts, "facts");

        requireAllowedSurface(surface);
        measure(facts, 1, 0);

        String canonical = write(facts);
        if (canonical.length() > maxFactsChars) {
            throw new InvalidRequestException(
                    "facts 가 너무 큽니다 — %d자(허용 %d자). AI 프롬프트에 그대로 실리므로 화면이 "
                            .formatted(canonical.length(), maxFactsChars)
                            + "실제로 쓰는 값만 담아 주세요.",
                    "facts");
        }
        return canonical;
    }

    private void requireAllowedSurface(String surface) {
        if (ALLOWED_SURFACES.contains(surface)) {
            return;
        }
        throw new InvalidRequestException(
                "surface '%s' 는 서술 대상이 아닙니다. 허용: %s"
                        .formatted(echo(surface), String.join(", ", new TreeSet<>(ALLOWED_SURFACES))),
                "surface");
    }

    private String echo(String surface) {
        return surface.length() <= SURFACE_ECHO_LIMIT
                ? surface
                : surface.substring(0, SURFACE_ECHO_LIMIT) + "…";
    }

    /**
     * 노드 수와 중첩 깊이를 순회하며 센다. 상한을 넘는 순간 던지므로 <b>끝까지 돌지 않는다</b> —
     * 거대한 입력을 거절하는 비용 자체가 작아야 한다.
     *
     * @param value   검사할 값
     * @param depth   {@code value} 가 컨테이너일 때 그것이 몇 번째 단계인지 ({@code facts} = 1)
     * @param counted 여기까지 센 노드 수
     * @return 이 값까지 포함해 센 노드 수
     */
    private int measure(Object value, int depth, int counted) {
        int total = counted + 1;
        if (total > maxFactsNodes) {
            throw new InvalidRequestException(
                    "facts 항목이 너무 많습니다 — 허용 %d개를 넘었습니다.".formatted(maxFactsNodes),
                    "facts");
        }
        Iterable<?> children = childrenOf(value);
        if (children == null) {
            return total;
        }
        if (depth > maxFactsDepth) {
            throw new InvalidRequestException(
                    "facts 중첩이 너무 깊습니다 — 허용 %d단계입니다.".formatted(maxFactsDepth), "facts");
        }
        for (Object child : children) {
            total = measure(child, depth + 1, total);
        }
        return total;
    }

    /** 컨테이너면 자식들, 스칼라({@code null} 포함)면 {@code null}. */
    private Iterable<?> childrenOf(Object value) {
        if (value instanceof Map<?, ?> map) {
            return map.values();
        }
        if (value instanceof Iterable<?> items) {
            return items;
        }
        return null;
    }

    private String write(Map<String, Object> facts) {
        try {
            return canonicalWriter.writeValueAsString(facts);
        } catch (JsonProcessingException e) {
            // JSON 으로 온 값만 들어오는 자리이므로 실무에서는 나오지 않는다. 그래도 500 으로
            // 새어 나가게 두지 않는다 — 직렬화할 수 없는 값은 잘못된 요청이다.
            throw new InvalidRequestException(
                    "facts 를 JSON 으로 다룰 수 없습니다 — 값이 표준 JSON 이어야 합니다.", "facts");
        }
    }
}
