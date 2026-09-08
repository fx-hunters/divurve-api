package com.divurve.infra.ai;

import com.divurve.domain.ai.ExplainSurface;
import com.divurve.domain.ai.RegimeDisclosureCheck;
import com.divurve.domain.port.AiProvider.ExplainContext;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 서술 프롬프트 조립과 응답 파싱 (이슈 #73·#135, {@code docs/05-ai-usage-v2.md} §3.1·§3.2·§5).
 *
 * <p>순수 함수만 둔다 — 네트워크도 상태도 없다. 프롬프트 문구는 규약이므로 코드에 고정하고,
 * 바뀌면 이 파일의 변경 이력이 곧 규약 변경 이력이 된다.
 *
 * <p><b>사후 검증만으로는 부족하다</b>(이슈 #73 제약 6·7). {@code AiResponseValidator}·
 * {@code NarrativeFilter} 는 이미 나온 문장을 걸러낼 뿐이고, 걸리면 폴백이라 사용자는 템플릿 문장을
 * 보게 된다. 그래서 같은 규약을 <b>system 프롬프트에도</b> 명문화해 애초에 어기지 않게 한다.
 */
final class ClaudeExplainPrompt {

    private final ObjectMapper mapper;

    /**
     * <b>전 화면 공통이며 화면마다 달라지지 않는다</b>(이슈 #135). 예전에는 문장 수 4가 이 문자열에
     * 박혀 있어 {@code forecast_summary} 전용이었고, 다른 화면은 실 API 를 아예 타지 못했다.
     * 화면마다 다른 것(문장 수·서술 초점)은 {@link #user} 가 싣는다 — 규약 본문이 고정이라야
     * 화면을 늘려도 이 파일의 변경 이력이 곧 규약 변경 이력으로 남고, 프롬프트 캐싱도 유효하다.
     */
    private static final String SYSTEM_PROMPT = """
        당신은 외화 목표·환전 타이밍 서비스의 설명 담당이다. 하는 일은 하나뿐이다 —
        이미 확정된 계산 결과를 사용자가 읽기 좋은 한국어 문장으로 옮기는 것.

        반드시 지킬 것:
        1. 그라운딩 — 입력으로 받은 facts 에 있는 값만 쓴다. facts 에 없는 숫자·날짜·비율·순위·
           시장 전망·뉴스를 절대 만들어내지 않는다. 모르는 것은 언급하지 않는다.
        2. 계산 금지 — 덧셈·뺄셈·비율 환산을 포함해 어떤 산술도 하지 않는다. facts 의 값을 그대로
           옮겨 적는다. facts 의 0.72 를 "72%%"로 표기하는 것만 허용한다.
        3. 방향 예측 금지 — 오를지 내릴지 말하지 않는다. 제시된 구간은 참고 범위일 뿐이다.
        4. 투자 권유 금지 — 매수·매도·지금이 기회 같은 표현을 쓰지 않는다. 수익·원금 보장,
           "반드시·확실히·무조건" 같은 단정 표현도 쓰지 않는다.
        5. 문장 수 고정 — user 메시지의 sentence_count 가 지정한 개수만큼 문장을 만든다.
           더도 덜도 안 된다.
        6. 급변 구간 — facts 의 regime 이 elevated 또는 stress 이면, 네 문장 중 하나에 다음 문구를
           글자 그대로 포함한다(다듬거나 바꿔 쓰지 않는다): "%s"
        7. 설명 선호(explain_level)와 익숙한 분야(explain_domain)는 어휘·비유·설명 밀도에만 반영한다.
           어떤 계산에도 넣지 않고, 문장 수도 바꾸지 않는다.

        8. 화면 맥락 — user 메시지의 surface 와 focus 가 어느 화면의 설명인지 알려준다. 그 화면이
           앞세워야 할 것부터 말한다. facts 의 키 이름을 그대로 옮겨 적지 않는다 — 키는 내부
           식별자이지 사용자가 읽을 말이 아니다.

        출력 형식 — 아래 JSON 하나만 출력한다. 코드 블록도 설명도 덧붙이지 않는다.
        {"sentences": ["문장1", "문장2", ...]}
        """.formatted(RegimeDisclosureCheck.REQUIRED_DISCLOSURE);

    ClaudeExplainPrompt(ObjectMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    /** 매 호출 동일한 규약 프롬프트. */
    String system() {
        return SYSTEM_PROMPT;
    }

    /**
     * 이번 요청의 그라운딩 입력. {@code facts} 는 JSON 으로 그대로 넘긴다 — 어댑터가 문장으로
     * 풀어 쓰면 그 과정에서 값이 바뀔 여지가 생긴다.
     *
     * <p>{@code surface}·{@code focus}·{@code sentence_count} 를 함께 싣는다(이슈 #135). 화면
     * 맥락 없이 {@code facts} 만 주면 모델이 키-값을 순서대로 옮겨 적는 쪽으로 흐르는데, 그것이
     * 정확히 사용자가 홈 화면에서 보던 문장이다.
     *
     * @param context 서술 컨텍스트
     * @param surface 서술 대상 화면의 규약
     * @return user 메시지 본문
     * @throws AiResponseFormatException facts 를 JSON 으로 직렬화할 수 없을 때
     */
    String user(ExplainContext context, ExplainSurface surface) {
        String factsJson = writeFacts(context.facts());
        return """
            surface: %s
            focus: %s
            sentence_count: %d
            explain_level: %s
            explain_domain: %s
            facts:
            %s
            """.formatted(
                surface.code(),
                surface.focus(),
                surface.sentenceCount(),
                context.explainLevel(),
                context.explainDomain(),
                factsJson);
    }

    /**
     * 응답 본문에서 문장 목록을 꺼낸다.
     *
     * @param body 모델 응답 본문
     * @return 문장 목록
     * @throws AiResponseFormatException 고정 스키마({@code {"sentences": [...]}})가 아닐 때
     */
    List<String> parseSentences(String body) {
        JsonNode root = readTree(body);
        JsonNode sentences = root.get("sentences");
        if (sentences == null || !sentences.isArray() || sentences.isEmpty()) {
            throw new AiResponseFormatException("응답에 sentences 배열이 없다");
        }

        List<String> parsed = new ArrayList<>(sentences.size());
        for (JsonNode node : sentences) {
            if (!node.isTextual() || node.asText().isBlank()) {
                throw new AiResponseFormatException("sentences 에 문자열이 아닌 항목이 있다");
            }
            parsed.add(node.asText());
        }
        return List.copyOf(parsed);
    }

    private String writeFacts(Map<String, Object> facts) {
        try {
            return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(facts);
        } catch (JsonProcessingException e) {
            throw new AiResponseFormatException("facts 를 JSON 으로 직렬화할 수 없다", e);
        }
    }

    private JsonNode readTree(String body) {
        try {
            return mapper.readTree(stripCodeFence(body));
        } catch (JsonProcessingException e) {
            throw new AiResponseFormatException("응답이 JSON 이 아니다", e);
        }
    }

    /**
     * 코드 블록으로 감싸 온 응답을 벗긴다. system 프롬프트가 "코드 블록을 덧붙이지 말라"고 지시하지만,
     * 지시를 어겼을 때 폴백으로 떨어뜨리는 것보다 벗겨서 쓰는 편이 사용자에게 낫다.
     */
    private static String stripCodeFence(String body) {
        String trimmed = body == null ? "" : body.trim();
        if (!trimmed.startsWith("```")) {
            return trimmed;
        }
        int firstLineEnd = trimmed.indexOf('\n');
        if (firstLineEnd < 0) {
            return trimmed;
        }
        String withoutOpening = trimmed.substring(firstLineEnd + 1);
        int closing = withoutOpening.lastIndexOf("```");
        return (closing < 0 ? withoutOpening : withoutOpening.substring(0, closing)).trim();
    }
}
