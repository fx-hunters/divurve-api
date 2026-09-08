package com.divurve.domain.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.divurve.domain.port.TokenUsage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * AI 호출 기록의 어휘와 사용량 값 객체 (이슈 #143).
 *
 * <p>{@code code()} 가 소문자여야 하는 이유 — DB CHECK 제약과 API 응답이 같은 값을 쓴다. 관리자가
 * 화면에서 본 값으로 그대로 SQL 을 짜기 때문에, 두 표기가 갈리면 조회가 조용히 0건이 된다.
 */
@DisplayName("AI 호출 기록 어휘")
class AiCallVocabularyTest {

    @Test
    @DisplayName("purpose 코드는 마이그레이션 CHECK 값과 같다")
    void purposeCodes() {
        assertThat(AiCallPurpose.NARRATE.code()).isEqualTo("narrate");
        assertThat(AiCallPurpose.EXTRACT.code()).isEqualTo("extract");
    }

    @Test
    @DisplayName("outcome 코드는 마이그레이션 CHECK 값과 같다")
    void outcomeCodes() {
        assertThat(AiCallOutcome.SUCCESS.code()).isEqualTo("success");
        assertThat(AiCallOutcome.FALLBACK.code()).isEqualTo("fallback");
        assertThat(AiCallOutcome.CACHE_HIT.code()).isEqualTo("cache_hit");
        assertThat(AiCallOutcome.QUOTA_BLOCKED.code()).isEqualTo("quota_blocked");
        assertThat(AiCallOutcome.ERROR.code()).isEqualTo("error");
    }

    @Test
    @DisplayName("쿼터 층 코드와 폴백 사유 코드가 정확히 짝을 이룬다 (이슈 #140)")
    void quotaLayerCodesMatchFallbackReasonCodes() {
        // 두 enum 이 서로를 참조하지 않는 대신(순환 의존을 피했다) 여기서 일치를 지킨다.
        // 갈리면 관리자 화면의 fallback_reason 값으로 SQL 을 짰을 때 조용히 0건이 된다.
        assertThat(AiCallQuota.Layer.USER.code())
                .isEqualTo(AiService.FallbackReason.QUOTA_USER.code());
        assertThat(AiCallQuota.Layer.IP.code())
                .isEqualTo(AiService.FallbackReason.QUOTA_IP.code());
        assertThat(AiCallQuota.Layer.GLOBAL.code())
                .isEqualTo(AiService.FallbackReason.QUOTA_GLOBAL.code());

        assertThat(AiCallQuota.Layer.values())
                .as("층이 늘면 짝이 되는 폴백 사유도 함께 늘어야 한다")
                .hasSize(3);
    }

    @Test
    @DisplayName("NONE 은 토큰을 쓰지 않은 경로를 나타낸다")
    void noneMeansNoLlmCall() {
        assertThat(TokenUsage.NONE.inputTokens()).isZero();
        assertThat(TokenUsage.NONE.outputTokens()).isZero();
        assertThat(TokenUsage.NONE.cacheReadInputTokens()).isNull();
        assertThat(TokenUsage.NONE.cacheCreationInputTokens()).isNull();
    }

    @Test
    @DisplayName("of 는 캐시 토큰을 null 로 둔다 — 프롬프트 캐싱을 쓰지 않은 호출")
    void ofLeavesCacheTokensUnmeasured() {
        TokenUsage usage = TokenUsage.of(120, 45);

        assertThat(usage.inputTokens()).isEqualTo(120);
        assertThat(usage.outputTokens()).isEqualTo(45);
        assertThat(usage.cacheReadInputTokens()).isNull();
    }

    @Test
    @DisplayName("plus 는 재시도가 쓴 토큰을 합산한다")
    void plusAccumulatesRetries() {
        TokenUsage total = TokenUsage.of(100, 40).plus(TokenUsage.of(90, 30));

        assertThat(total.inputTokens()).isEqualTo(190);
        assertThat(total.outputTokens()).isEqualTo(70);
    }

    @Test
    @DisplayName("plus 는 양쪽이 측정되지 않은 캐시 토큰을 0 으로 채우지 않는다")
    void plusPreservesUnmeasuredCacheTokens() {
        TokenUsage total = TokenUsage.NONE.plus(TokenUsage.NONE);

        assertThat(total.cacheReadInputTokens())
                .as("0 으로 채우면 프롬프트 캐싱을 켠 뒤 과거 구간과 구분되지 않는다")
                .isNull();
        assertThat(total.cacheCreationInputTokens()).isNull();
    }

    @Test
    @DisplayName("plus 는 한쪽만 측정된 캐시 토큰을 살린다")
    void plusKeepsSingleSidedCacheTokens() {
        TokenUsage measured = new TokenUsage(10, 5, 7L, 3L);

        assertThat(TokenUsage.NONE.plus(measured).cacheReadInputTokens()).isEqualTo(7);
        assertThat(measured.plus(TokenUsage.NONE).cacheCreationInputTokens()).isEqualTo(3);
        assertThat(measured.plus(measured).cacheReadInputTokens()).isEqualTo(14);
    }

    @Test
    @DisplayName("음수 토큰은 만들 수 없다")
    void rejectsNegativeTokens() {
        assertThatThrownBy(() -> new TokenUsage(-1, 0, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TokenUsage(0, -1, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TokenUsage.NONE.plus(null))
                .isInstanceOf(NullPointerException.class);
    }
}
