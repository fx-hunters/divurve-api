package com.divurve.infra.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.divurve.domain.ai.ExplainSurface;
import com.divurve.domain.port.AiProvider.ExplainContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * {@link ClaudeExplainPrompt} 테스트 (이슈 #73).
 *
 * <p>프롬프트 문구는 규약이다 — 그라운딩·계산 금지·문장 수 고정·급변 안내가 실제로 프롬프트에
 * 들어 있는지 확인한다. 지워지면 사후 검증만 남아 조용히 폴백이 늘어난다.
 */
class ClaudeExplainPromptTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final ClaudeExplainPrompt prompt = new ClaudeExplainPrompt(mapper);

    @Test
    void system_프롬프트는_그라운딩과_문장수_규약을_담는다() {
        String system = prompt.system();

        assertThat(system).contains("facts 에 있는 값만");
        assertThat(system).contains("어떤 산술도 하지 않는다");
        assertThat(system).contains("sentence_count 가 지정한 개수");
        assertThat(system).contains("elevated");
        assertThat(system).contains("sentences");
    }

    @Test
    void user_프롬프트는_설명선호와_facts_를_그대로_담는다() {
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("pair_code", "USD_KRW");
        facts.put("current_rate", 1380.5);

        String user = prompt.user(
                new ExplainContext("forecast_summary", facts, "simple", "plain"),
                ExplainSurface.FORECAST_SUMMARY);

        assertThat(user).contains("explain_level: simple");
        assertThat(user).contains("explain_domain: plain");
        assertThat(user).contains("\"pair_code\" : \"USD_KRW\"");
        assertThat(user).contains("1380.5");
    }

    /**
     * 화면마다 달라지는 것이 프롬프트에 실제로 실리는지 본다 (이슈 #135). 이것이 빠지면 모델은
     * 어느 화면의 설명인지 모른 채 facts 키를 순서대로 옮겨 적게 되고, 그 결과가 사용자가 홈
     * 화면에서 보던 "interval 80 lo는(은) ..." 문장이다.
     */
    @Test
    void user_프롬프트는_화면_규약을_싣는다() {
        String user = prompt.user(
                new ExplainContext("xray_exposure", Map.of("fx_ratio", 0.632), "simple", "plain"),
                ExplainSurface.XRAY_EXPOSURE);

        assertThat(user).contains("surface: xray_exposure");
        assertThat(user).contains("sentence_count: 3");
        assertThat(user).contains(ExplainSurface.XRAY_EXPOSURE.focus());
    }

    @Test
    void parseSentences_는_고정_스키마에서_문장을_꺼낸다() {
        List<String> sentences = prompt.parseSentences(
                "{\"sentences\": [\"첫 문장.\", \"둘째 문장.\"]}");

        assertThat(sentences).containsExactly("첫 문장.", "둘째 문장.");
    }

    @Test
    void parseSentences_는_코드블록으로_감싸_온_응답도_받아준다() {
        String fenced = "```json\n{\"sentences\": [\"감싸진 문장.\"]}\n```";

        assertThat(prompt.parseSentences(fenced)).containsExactly("감싸진 문장.");
    }

    @Test
    void parseSentences_는_닫는_백틱이_없어도_받아준다() {
        String fenced = "```json\n{\"sentences\": [\"열린 문장.\"]}";

        assertThat(prompt.parseSentences(fenced)).containsExactly("열린 문장.");
    }

    @Test
    void parseSentences_는_한_줄짜리_백틱만_있으면_그대로_파싱을_시도한다() {
        assertThatThrownBy(() -> prompt.parseSentences("```"))
                .isInstanceOf(AiResponseFormatException.class);
    }

    @Test
    void parseSentences_는_JSON_이_아니면_형식_예외를_던진다() {
        assertThatThrownBy(() -> prompt.parseSentences("네, 알겠습니다. 문장을 만들어 드릴게요."))
                .isInstanceOf(AiResponseFormatException.class)
                .hasMessageContaining("JSON");
    }

    @Test
    void parseSentences_는_빈_응답이면_형식_예외를_던진다() {
        assertThatThrownBy(() -> prompt.parseSentences(null))
                .isInstanceOf(AiResponseFormatException.class)
                .hasMessageContaining("sentences");
    }

    @Test
    void parseSentences_는_sentences_키가_없으면_형식_예외를_던진다() {
        assertThatThrownBy(() -> prompt.parseSentences("{\"text\": \"...\"}"))
                .isInstanceOf(AiResponseFormatException.class)
                .hasMessageContaining("sentences");
    }

    @Test
    void parseSentences_는_배열이_아니면_형식_예외를_던진다() {
        assertThatThrownBy(() -> prompt.parseSentences("{\"sentences\": \"문장\"}"))
                .isInstanceOf(AiResponseFormatException.class);
    }

    @Test
    void parseSentences_는_빈_배열이면_형식_예외를_던진다() {
        assertThatThrownBy(() -> prompt.parseSentences("{\"sentences\": []}"))
                .isInstanceOf(AiResponseFormatException.class);
    }

    @Test
    void parseSentences_는_문자열이_아닌_항목이_있으면_형식_예외를_던진다() {
        assertThatThrownBy(() -> prompt.parseSentences("{\"sentences\": [\"정상\", 3]}"))
                .isInstanceOf(AiResponseFormatException.class)
                .hasMessageContaining("문자열");
    }

    @Test
    void parseSentences_는_빈_문장이_있으면_형식_예외를_던진다() {
        assertThatThrownBy(() -> prompt.parseSentences("{\"sentences\": [\"   \"]}"))
                .isInstanceOf(AiResponseFormatException.class);
    }

    @Test
    void user_는_직렬화할_수_없는_facts_에_형식_예외를_던진다() {
        Map<String, Object> facts = Map.of("bad", new Object());

        assertThatThrownBy(() -> prompt.user(
                new ExplainContext("forecast_summary", facts, "simple", "plain"),
                ExplainSurface.FORECAST_SUMMARY))
                .isInstanceOf(AiResponseFormatException.class)
                .hasMessageContaining("직렬화");
    }

    @Test
    void mapper_가_null_이면_생성에_실패한다() {
        assertThatThrownBy(() -> new ClaudeExplainPrompt(null))
                .isInstanceOf(NullPointerException.class);
    }
}
