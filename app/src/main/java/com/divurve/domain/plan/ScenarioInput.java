package com.divurve.domain.plan;

import com.divurve.common.exception.InvalidRequestException;
import java.time.LocalDate;

/**
 * 시나리오 재계산 요청 (플래너 명세 §16).
 *
 * <p>{@link ScenarioCode} 마다 필요한 값이 다르다. 코드가 요구하는 값이 비어 있으면 계산하지 않고
 * 400 을 낸다 — 빠진 값을 현재 목표 값으로 대신 채우면 "아무것도 바뀌지 않은 변경안"이 나오고,
 * 사용자는 자기 입력이 반영됐다고 믿는다.
 *
 * @param code                   상황 변화 코드
 * @param skippedSeq             {@link ScenarioCode#STEP_SKIPPED} — 건너뛴 회차 번호
 * @param newBudgetKrw           {@link ScenarioCode#BUDGET_DECREASED} — 줄어든 회차 예산 (원)
 * @param newTargetDate          {@link ScenarioCode#TARGET_DATE_CHANGED} — 새 목표 날짜
 * @param newTargetAmount        {@link ScenarioCode#TARGET_AMOUNT_CHANGED} — 새 목표 외화 금액
 * @param addedHoldingAmount     {@link ScenarioCode#HOLDING_ADDED} — 추가 배정할 보유 외화
 */
public record ScenarioInput(
        ScenarioCode code,
        Integer skippedSeq,
        Long newBudgetKrw,
        LocalDate newTargetDate,
        Double newTargetAmount,
        Double addedHoldingAmount) {

    public ScenarioInput {
        if (code == null) {
            throw new InvalidRequestException("scenario_code 는 필수입니다.", "scenario_code");
        }
    }

    /**
     * 코드가 요구하는 값이 채워졌는지 확인한다 (명세 §16).
     *
     * @throws InvalidRequestException 필수 값이 없거나 값의 방향이 코드와 어긋나는 경우
     */
    public void validate() {
        switch (code) {
            case STEP_SKIPPED -> requirePositive(skippedSeq, "skipped_seq");
            case BUDGET_DECREASED -> requirePositive(newBudgetKrw, "new_budget_krw");
            case TARGET_DATE_CHANGED -> requirePresent(newTargetDate, "new_target_date");
            case TARGET_AMOUNT_CHANGED -> requirePositive(newTargetAmount, "new_target_amount");
            case HOLDING_ADDED -> requirePositive(addedHoldingAmount, "added_holding_amount");
            // 환율 시나리오는 추가 입력을 받지 않는다 — 조회 시점 환율로 다시 계산한다.
            case RATE_UP, RATE_DOWN -> { }
        }
    }

    private static void requirePresent(Object value, String field) {
        if (value == null) {
            throw new InvalidRequestException(field + " 는 필수입니다.", field);
        }
    }

    private static void requirePositive(Number value, String field) {
        requirePresent(value, field);
        if (value.doubleValue() <= 0) {
            throw new InvalidRequestException(field + " 는 0보다 커야 합니다.", field);
        }
    }
}
