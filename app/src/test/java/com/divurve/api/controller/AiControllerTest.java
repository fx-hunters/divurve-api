package com.divurve.api.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.divurve.api.dto.ai.ExplainRequest;
import com.divurve.api.dto.ai.ExplainResponse;
import com.divurve.common.exception.InvalidRequestException;
import com.divurve.common.response.ApiResponse;
import com.divurve.domain.ai.AiService;
import com.divurve.domain.port.AuthPrincipal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * {@link AiController} 매핑·입력 검증·메타 테스트 (API 명세 v2 §5.12).
 *
 * <p>미인증 401 은 {@code CurrentUserArgumentResolverTest} 가 한 벌로 검증한다 (이슈 #50).
 */
@ExtendWith(MockitoExtension.class)
class AiControllerTest {

    @Mock
    private AiService aiService;

    private final UUID userId = UUID.randomUUID();

    /** 컨트롤러가 도메인으로 넘겨야 하는 출처 IP (이슈 #140). */
    private static final String CLIENT_IP = "203.0.113.7";

    private AiController controller() {
        return new AiController(aiService);
    }

    /** 데모가 아닌 일반 세션의 principal. 컨트롤러가 is_demo 를 도메인으로 넘기는 경로다(이슈 #143). */
    private AuthPrincipal principal() {
        return new AuthPrincipal(userId, false);
    }

    @Test
    void explain_은_서술_결과를_data와_meta로_래핑한다() {
        Map<String, Object> facts = Map.of("pair_code", "USDKRW", "current_rate", 1382.40);
        when(aiService.explain(userId, false, CLIENT_IP, "forecast_summary", facts)).thenReturn(
                new AiService.ExplainOutcome(
                        List.of("문장1", "문장2", "문장3", "문장4"),
                        "standard", "finance", false, true, true, List.of(), null));

        ApiResponse<ExplainResponse> response =
                controller().explain(principal(), CLIENT_IP, new ExplainRequest("forecast_summary", facts));

        ExplainResponse.Explanation explanation = response.data().explanation();
        assertThat(explanation.sentences()).hasSize(4);
        assertThat(explanation.sentenceCount()).isEqualTo(4);
        assertThat(explanation.explainLevel()).isEqualTo("standard");
        assertThat(explanation.explainDomain()).isEqualTo("finance");
        assertThat(explanation.fallback()).isFalse();
        assertThat(response.data().verification().numericMatch()).isTrue();
        assertThat(response.data().verification().regimeDisclosed()).isTrue();
        assertThat(response.data().verification().blockedPhrases()).isEmpty();
        assertThat(response.data().verification().fallbackReason()).isNull();
        assertThat(response.meta()).isNotNull();
    }

    @Test
    void explain_검증_실패해도_예외_없이_fallback_true를_반환한다() {
        Map<String, Object> facts = Map.of("amount", 100000.0);
        when(aiService.explain(userId, false, CLIENT_IP, "home_market_summary", facts)).thenReturn(
                new AiService.ExplainOutcome(AiService.FALLBACK_SENTENCES, "simple", "plain", true,
                        null, null, List.of(), AiService.FallbackReason.PROVIDER_ERROR));

        ApiResponse<ExplainResponse> response =
                controller().explain(principal(), CLIENT_IP, new ExplainRequest("home_market_summary", facts));

        assertThat(response.data().explanation().fallback()).isTrue();
        assertThat(response.data().explanation().sentences()).isEqualTo(AiService.FALLBACK_SENTENCES);
        // 이슈 #122 — 왜 폴백했는지가 응답에 있다. 검증까지 가지도 못한 것을 null 이 말한다.
        assertThat(response.data().verification().fallbackReason()).isEqualTo("provider_error");
        assertThat(response.data().verification().numericMatch()).isNull();
        assertThat(response.data().verification().regimeDisclosed()).isNull();
    }

    @Test
    void explain_금지_표현_폴백은_검출된_표현을_그대로_내보낸다() {
        Map<String, Object> facts = Map.of("amount", 100000.0);
        when(aiService.explain(userId, false, CLIENT_IP, "home_market_summary", facts)).thenReturn(
                new AiService.ExplainOutcome(AiService.FALLBACK_SENTENCES, "simple", "plain", true,
                        null, null, List.of("반드시"), AiService.FallbackReason.BLOCKED_PHRASES));

        ApiResponse<ExplainResponse> response =
                controller().explain(principal(), CLIENT_IP, new ExplainRequest("home_market_summary", facts));

        assertThat(response.data().verification().fallbackReason()).isEqualTo("blocked_phrases");
        assertThat(response.data().verification().blockedPhrases()).containsExactly("반드시");
    }

    @Test
    void explain_facts에_regime이_있으면_meta_regime에_반영한다() {
        Map<String, Object> facts = Map.of("amount", 100000.0, "regime", "elevated");
        when(aiService.explain(userId, false, CLIENT_IP, "home_market_summary", facts)).thenReturn(
                new AiService.ExplainOutcome(
                        List.of("문장"), "simple", "plain", false, true, true, List.of(), null));

        ApiResponse<ExplainResponse> response =
                controller().explain(principal(), CLIENT_IP, new ExplainRequest("home_market_summary", facts));

        assertThat(response.meta().regime()).isEqualTo("elevated");
    }

    @Test
    void explain_facts의_regime이_blank이면_meta_regime은_null이다() {
        Map<String, Object> facts = Map.of("amount", 100000.0, "regime", "   ");
        when(aiService.explain(userId, false, CLIENT_IP, "home_market_summary", facts)).thenReturn(
                new AiService.ExplainOutcome(
                        List.of("문장"), "simple", "plain", false, true, true, List.of(), null));

        ApiResponse<ExplainResponse> response =
                controller().explain(principal(), CLIENT_IP, new ExplainRequest("home_market_summary", facts));

        assertThat(response.meta().regime()).isNull();
    }

    @Test
    void explain_facts에_regime이_없으면_meta_regime은_null이다() {
        Map<String, Object> facts = Map.of("amount", 100000.0);
        when(aiService.explain(userId, false, CLIENT_IP, "home_market_summary", facts)).thenReturn(
                new AiService.ExplainOutcome(
                        List.of("문장"), "simple", "plain", false, true, true, List.of(), null));

        ApiResponse<ExplainResponse> response =
                controller().explain(principal(), CLIENT_IP, new ExplainRequest("home_market_summary", facts));

        assertThat(response.meta().regime()).isNull();
    }

    @Test
    void explain_request가_null이면_InvalidRequestException을_던진다() {
        assertThatThrownBy(() -> controller().explain(principal(), CLIENT_IP, null))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("요청 본문");
    }

    @Test
    void explain_surface가_null이면_InvalidRequestException을_던진다() {
        ExplainRequest request = new ExplainRequest(null, Map.of("amount", 100.0));

        assertThatThrownBy(() -> controller().explain(principal(), CLIENT_IP, request))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("surface");
    }

    @Test
    void explain_surface가_blank이면_InvalidRequestException을_던진다() {
        ExplainRequest request = new ExplainRequest("   ", Map.of("amount", 100.0));

        assertThatThrownBy(() -> controller().explain(principal(), CLIENT_IP, request))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("surface");
    }

    @Test
    void explain_facts가_null이면_InvalidRequestException을_던진다() {
        ExplainRequest request = new ExplainRequest("home_market_summary", null);

        assertThatThrownBy(() -> controller().explain(principal(), CLIENT_IP, request))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("facts");
    }

    @Test
    void explain_facts가_empty이면_InvalidRequestException을_던진다() {
        ExplainRequest request = new ExplainRequest("home_market_summary", Map.of());

        assertThatThrownBy(() -> controller().explain(principal(), CLIENT_IP, request))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("facts");
    }
}
