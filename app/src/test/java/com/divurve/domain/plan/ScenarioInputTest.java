package com.divurve.domain.plan;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.divurve.common.exception.InvalidRequestException;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link ScenarioInput} — 코드마다 필요한 값 검증 (플래너 명세 §16).
 *
 * <p>빠진 값을 현재 목표 값으로 대신 채우지 않는다는 것이 요점이다. 그렇게 채우면 아무것도
 * 바뀌지 않은 변경안이 나오고, 사용자는 자기 입력이 반영됐다고 믿는다.
 */
@DisplayName("ScenarioInput")
class ScenarioInputTest {

    private static ScenarioInput of(ScenarioCode code) {
        return new ScenarioInput(code, null, null, null, null, null);
    }

    @Test
    @DisplayName("코드 없이는 만들 수 없다")
    void nullCode_Throws() {
        assertThatThrownBy(() -> new ScenarioInput(null, null, null, null, null, null))
                .isInstanceOf(InvalidRequestException.class)
                .hasFieldOrPropertyWithValue("field", "scenario_code");
    }

    @Test
    @DisplayName("환율 시나리오는 추가 입력을 요구하지 않는다 — 조회 시점 환율로 다시 계산한다")
    void rateScenarios_NeedNothing() {
        assertThatCode(() -> of(ScenarioCode.RATE_UP).validate()).doesNotThrowAnyException();
        assertThatCode(() -> of(ScenarioCode.RATE_DOWN).validate()).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("건너뛰기는 회차 번호를 요구한다")
    void stepSkipped_RequiresSeq() {
        assertMissing(of(ScenarioCode.STEP_SKIPPED), "skipped_seq");
        assertNotPositive(
                new ScenarioInput(ScenarioCode.STEP_SKIPPED, 0, null, null, null, null), "skipped_seq");
        assertThatCode(() -> new ScenarioInput(ScenarioCode.STEP_SKIPPED, 3, null, null, null, null)
                .validate()).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("예산 감소는 새 예산을 요구한다")
    void budgetDecreased_RequiresBudget() {
        assertMissing(of(ScenarioCode.BUDGET_DECREASED), "new_budget_krw");
        assertNotPositive(
                new ScenarioInput(ScenarioCode.BUDGET_DECREASED, null, 0L, null, null, null),
                "new_budget_krw");
        assertThatCode(() -> new ScenarioInput(ScenarioCode.BUDGET_DECREASED, null, 300_000L, null, null, null)
                .validate()).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("목표 날짜 변경은 새 날짜를 요구한다 — 날짜는 음수 개념이 없어 존재만 본다")
    void targetDateChanged_RequiresDate() {
        assertMissing(of(ScenarioCode.TARGET_DATE_CHANGED), "new_target_date");
        assertThatCode(() -> new ScenarioInput(
                ScenarioCode.TARGET_DATE_CHANGED, null, null, LocalDate.of(2027, 3, 1), null, null)
                .validate()).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("목표 금액 변경은 0보다 큰 금액을 요구한다")
    void targetAmountChanged_RequiresPositiveAmount() {
        assertMissing(of(ScenarioCode.TARGET_AMOUNT_CHANGED), "new_target_amount");
        assertNotPositive(
                new ScenarioInput(ScenarioCode.TARGET_AMOUNT_CHANGED, null, null, null, -1.0, null),
                "new_target_amount");
        assertThatCode(() -> new ScenarioInput(
                ScenarioCode.TARGET_AMOUNT_CHANGED, null, null, null, 12_000.0, null)
                .validate()).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("보유 추가는 0보다 큰 금액을 요구한다 — 0을 추가하는 것은 변화가 아니다")
    void holdingAdded_RequiresPositiveAmount() {
        assertMissing(of(ScenarioCode.HOLDING_ADDED), "added_holding_amount");
        assertNotPositive(
                new ScenarioInput(ScenarioCode.HOLDING_ADDED, null, null, null, null, 0.0),
                "added_holding_amount");
        assertThatCode(() -> new ScenarioInput(ScenarioCode.HOLDING_ADDED, null, null, null, null, 500.0)
                .validate()).doesNotThrowAnyException();
    }

    private static void assertMissing(ScenarioInput input, String field) {
        assertThatThrownBy(input::validate)
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("필수")
                .hasFieldOrPropertyWithValue("field", field);
    }

    private static void assertNotPositive(ScenarioInput input, String field) {
        assertThatThrownBy(input::validate)
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("0보다 커야")
                .hasFieldOrPropertyWithValue("field", field);
    }
}
