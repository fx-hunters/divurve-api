package com.divurve.api.dto.plan;

import com.divurve.domain.plan.ScenarioCode;
import com.divurve.domain.plan.ScenarioInput;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import java.time.LocalDate;

/**
 * 상황 변화 미리보기 요청 (플래너 명세 §16).
 *
 * <p>{@code scenario_code} 마다 필요한 값이 다르다. 빠진 값을 현재 목표 값으로 대신 채우지
 * 않고 400 을 낸다 — 그렇게 채우면 아무것도 바뀌지 않은 변경안이 나오고, 사용자는 자기 입력이
 * 반영됐다고 믿는다. 어느 값이 필요한지는 {@link ScenarioInput#validate()} 가 정한다.
 *
 * @param scenarioCode        상황 변화 코드 (명세 §16 의 닫힌 집합 7종)
 * @param skippedSeq          {@code STEP_SKIPPED} — 건너뛴 회차 번호
 * @param newBudgetKrw        {@code BUDGET_DECREASED} — 줄어든 회차 예산 (원)
 * @param newTargetDate       {@code TARGET_DATE_CHANGED} — 새 목표 날짜
 * @param newTargetAmount     {@code TARGET_AMOUNT_CHANGED} — 새 목표 외화 금액
 * @param addedHoldingAmount  {@code HOLDING_ADDED} — 이 목표에 추가 배정할 보유 외화
 */
@Schema(description = "상황 변화 미리보기 요청 (플래너 명세 §16)")
public record ScenarioPreviewRequest(
        @NotBlank(message = "scenario_code 는 필수입니다.")
        @Schema(description = "상황 변화 코드", example = "TARGET_DATE_CHANGED",
                allowableValues = {"RATE_UP", "RATE_DOWN", "STEP_SKIPPED", "BUDGET_DECREASED",
                        "TARGET_DATE_CHANGED", "TARGET_AMOUNT_CHANGED", "HOLDING_ADDED"})
        String scenarioCode,

        @Schema(description = "건너뛴 회차 번호 (STEP_SKIPPED)", example = "3")
        Integer skippedSeq,

        @Schema(description = "줄어든 회차 예산, 원 (BUDGET_DECREASED)", example = "300000")
        Long newBudgetKrw,

        @Schema(description = "새 목표 날짜 (TARGET_DATE_CHANGED)", example = "2027-03-01")
        LocalDate newTargetDate,

        @Schema(description = "새 목표 외화 금액 (TARGET_AMOUNT_CHANGED)", example = "12000")
        Double newTargetAmount,

        @Schema(description = "추가 배정할 보유 외화 (HOLDING_ADDED)", example = "500")
        Double addedHoldingAmount) {

    /** 도메인 입력으로 옮긴다. 코드 검증은 {@link ScenarioCode#from(String)} 이 맡는다. */
    public ScenarioInput toInput() {
        return new ScenarioInput(
                ScenarioCode.from(scenarioCode),
                skippedSeq,
                newBudgetKrw,
                newTargetDate,
                newTargetAmount,
                addedHoldingAmount);
    }
}
