package com.divurve.infra.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.divurve.domain.port.EconEventExtractor.ExtractedEvent;
import com.divurve.domain.port.EconEventExtractor.RawArticle;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * {@link ClaudeEconEventExtractor} 테스트 (이슈 #74).
 *
 * <p>확인하는 것: (1) 정상 응답이 이벤트 목록으로 돌아오는지, (2) 코드펜스로 감싼 응답도
 * 받아지는지, (3) 빈 events 배열이 빈 목록으로 오는지, (4) 여러 건이 모두 추출되는지,
 * (5) 형식 위반 응답을 삼키지 않고 그대로 던지는지 — 검증은 어댑터가 아니라 도메인의 몫이므로
 * 여기서 값 범위를 걸러내지 않는다는 것도 함께 확인한다.
 */
class ClaudeEconEventExtractorTest {

    private static final RawArticle ARTICLE = new RawArticle(
        "demo://sample/fomc", "2026년 9월 17일 FOMC 회의 예정.", Instant.parse("2026-09-07T00:00:00Z"));

    private final ObjectMapper mapper = new ObjectMapper();
    private final StubMessageClient messageClient = new StubMessageClient();
    private final AnthropicProperties props =
        new AnthropicProperties(true, true, "sk-ant-test", null, 0, null);

    private final ClaudeEconEventExtractor sut =
        new ClaudeEconEventExtractor(messageClient, mapper, props);

    @Test
    void extract_는_실_API_응답을_이벤트_목록으로_돌려준다() {
        messageClient.response = new ClaudeMessageClient.Completion(
            "{\"events\": [{\"event_date\": \"2026-09-17\", \"region\": \"US\", "
                + "\"title\": \"FOMC 회의\", \"impact\": 3}]}", 200, 80);

        List<ExtractedEvent> events = sut.extract(ARTICLE);

        assertThat(events).containsExactly(
            new ExtractedEvent("2026-09-17", "US", "FOMC 회의", 3));
        assertThat(messageClient.systemPrompt).contains("미래 날짜 recall");
        assertThat(messageClient.userPrompt).contains("demo://sample/fomc");
    }

    @Test
    void extract_는_코드펜스로_감싼_응답도_받는다() {
        messageClient.response = new ClaudeMessageClient.Completion(
            "```json\n{\"events\": [{\"event_date\": \"2026-09-17\", \"region\": \"US\", "
                + "\"title\": \"FOMC\", \"impact\": 2}]}\n```", 100, 40);

        assertThat(sut.extract(ARTICLE)).hasSize(1);
    }

    @Test
    void extract_는_빈_events_배열이면_빈_목록을_돌려준다() {
        messageClient.response = new ClaudeMessageClient.Completion("{\"events\": []}", 50, 10);

        assertThat(sut.extract(ARTICLE)).isEmpty();
    }

    @Test
    void extract_는_여러_건을_모두_돌려준다() {
        messageClient.response = new ClaudeMessageClient.Completion(
            "{\"events\": ["
                + "{\"event_date\": \"2026-09-17\", \"region\": \"US\", \"title\": \"FOMC\", \"impact\": 3},"
                + "{\"event_date\": \"2026-09-24\", \"region\": \"EU\", \"title\": \"ECB\", \"impact\": 1}"
                + "]}", 300, 120);

        assertThat(sut.extract(ARTICLE)).hasSize(2);
    }

    @Test
    void extract_는_고정_스키마를_벗어난_응답에_형식_예외를_던진다() {
        messageClient.response = new ClaudeMessageClient.Completion("아무 말이나 합니다.", 10, 5);

        assertThatThrownBy(() -> sut.extract(ARTICLE))
            .isInstanceOf(AiResponseFormatException.class);
    }

    @Test
    void extract_는_API_실패를_삼키지_않고_그대로_던진다() {
        messageClient.failure = new IllegalStateException("read timed out");

        assertThatThrownBy(() -> sut.extract(ARTICLE))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("read timed out");
    }

    @Test
    void extract_는_article이_null이면_실패한다() {
        assertThatThrownBy(() -> sut.extract(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void 생성자는_협력자가_null_이면_실패한다() {
        assertThatThrownBy(() -> new ClaudeEconEventExtractor(null, mapper, props))
            .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ClaudeEconEventExtractor(messageClient, null, props))
            .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ClaudeEconEventExtractor(messageClient, mapper, null))
            .isInstanceOf(NullPointerException.class);
    }

    private static final class StubMessageClient implements ClaudeMessageClient {

        private Completion response;
        private RuntimeException failure;
        private String systemPrompt;
        private String userPrompt;

        @Override
        public Completion complete(String systemPrompt, String userPrompt) {
            this.systemPrompt = systemPrompt;
            this.userPrompt = userPrompt;
            if (failure != null) {
                throw failure;
            }
            return response;
        }
    }
}
