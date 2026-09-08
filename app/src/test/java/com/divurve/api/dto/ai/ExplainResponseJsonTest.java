package com.divurve.api.dto.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code verification} 의 직렬화 형태를 고정한다 (이슈 #122).
 *
 * <p>전역 설정은 {@code default-property-inclusion: non_null} 이다. 그대로 두면 "검증까지 가지
 * 못했다"를 뜻하는 {@code null} 이 <b>필드째로 사라져</b> 화면이 "검증 안 됨" 과 "필드 없음" 을
 * 구분할 수 없다 — 고치려던 문제가 그대로 남는다. {@link ExplainResponse.Verification} 이
 * {@code @JsonInclude(ALWAYS)} 로 그 기본값을 뒤집는 것이 이 계약의 전부이므로, 전역 설정을 그대로
 * 재현한 매퍼로 실제 키가 남는지 확인한다.
 */
@DisplayName("ExplainResponse.Verification 직렬화")
class ExplainResponseJsonTest {

    /** 운영 설정과 같게 구성한 매퍼 — 전역 non_null + SNAKE_CASE. */
    private final ObjectMapper objectMapper = new ObjectMapper()
            .setSerializationInclusion(JsonInclude.Include.NON_NULL)
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);

    @Test
    @DisplayName("측정하지 못한 검증값과 폴백 사유는 null 이어도 응답에서 사라지지 않는다")
    void verification_KeepsNullFields() {
        JsonNode json = objectMapper.valueToTree(new ExplainResponse.Verification(
                null, null, List.of("반드시"), "blocked_phrases"));

        assertThat(json.has("numeric_match")).isTrue();
        assertThat(json.get("numeric_match").isNull()).isTrue();
        assertThat(json.has("regime_disclosed")).isTrue();
        assertThat(json.get("regime_disclosed").isNull()).isTrue();
        assertThat(json.get("fallback_reason").asText()).isEqualTo("blocked_phrases");
        assertThat(json.get("blocked_phrases").get(0).asText()).isEqualTo("반드시");
    }

    @Test
    @DisplayName("폴백이 아니면 fallback_reason 은 null 로 남는다 — 키 자체는 있다")
    void verification_SuccessKeepsNullReason() {
        JsonNode json = objectMapper.valueToTree(
                new ExplainResponse.Verification(true, true, List.of(), null));

        assertThat(json.get("numeric_match").asBoolean()).isTrue();
        assertThat(json.get("regime_disclosed").asBoolean()).isTrue();
        assertThat(json.has("fallback_reason")).isTrue();
        assertThat(json.get("fallback_reason").isNull()).isTrue();
    }
}
