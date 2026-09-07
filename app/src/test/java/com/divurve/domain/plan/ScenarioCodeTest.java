package com.divurve.domain.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.divurve.common.exception.InvalidRequestException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * {@link ScenarioCode} — 상황 변화 코드의 닫힌 집합 (플래너 명세 §16).
 *
 * <p>모르는 코드를 조용히 흘려보내지 않는 것이 요점이다. 프론트 오타가 "아무 변화 없음"으로
 * 계산되면 사용자는 바뀌지 않은 계획을 변경안으로 보게 된다.
 */
@DisplayName("ScenarioCode")
class ScenarioCodeTest {

    @ParameterizedTest
    @EnumSource(ScenarioCode.class)
    @DisplayName("이름 그대로 파싱된다")
    void from_ExactName(ScenarioCode code) {
        assertThat(ScenarioCode.from(code.name())).isEqualTo(code);
    }

    @Test
    @DisplayName("대소문자와 앞뒤 공백은 무시한다 — 프론트 입력을 굳이 되돌려보내지 않는다")
    void from_IsLenientAboutCaseAndWhitespace() {
        assertThat(ScenarioCode.from("  rate_up  ")).isEqualTo(ScenarioCode.RATE_UP);
        assertThat(ScenarioCode.from("Target_Date_Changed"))
                .isEqualTo(ScenarioCode.TARGET_DATE_CHANGED);
    }

    @ParameterizedTest
    @ValueSource(strings = {"RATE_SIDEWAYS", "rate up", "SKIP", "0"})
    @DisplayName("집합에 없는 코드는 400 으로 돌려보낸다")
    void from_UnknownCode_Throws(String code) {
        assertThatThrownBy(() -> ScenarioCode.from(code))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("지원하지 않는 scenario_code")
                .hasFieldOrPropertyWithValue("field", "scenario_code");
    }

    @Test
    @DisplayName("코드가 비어 있으면 400 이다")
    void from_BlankOrNull_Throws() {
        assertThatThrownBy(() -> ScenarioCode.from(null))
                .isInstanceOf(InvalidRequestException.class)
                .hasFieldOrPropertyWithValue("field", "scenario_code");
        assertThatThrownBy(() -> ScenarioCode.from("   "))
                .isInstanceOf(InvalidRequestException.class);
    }

    @Test
    @DisplayName("환율 시나리오만 rate-driven 이다 — 나머지는 목표 조건이 바뀐 것이다")
    void isRateDriven() {
        assertThat(ScenarioCode.RATE_UP.isRateDriven()).isTrue();
        assertThat(ScenarioCode.RATE_DOWN.isRateDriven()).isTrue();
        assertThat(ScenarioCode.STEP_SKIPPED.isRateDriven()).isFalse();
        assertThat(ScenarioCode.BUDGET_DECREASED.isRateDriven()).isFalse();
        assertThat(ScenarioCode.TARGET_DATE_CHANGED.isRateDriven()).isFalse();
        assertThat(ScenarioCode.TARGET_AMOUNT_CHANGED.isRateDriven()).isFalse();
        assertThat(ScenarioCode.HOLDING_ADDED.isRateDriven()).isFalse();
    }
}
