package com.divurve.domain.goal;

import java.time.LocalDate;

/**
 * 목표 수정 입력 (이슈 #197).
 *
 * <p><b>부분 갱신 계약이다.</b> 모든 필드가 {@code null} 을 "값 변경 없음"으로 쓴다 — 원시 타입을
 * 두면 "0 으로 바꿈"과 "변경 없음"이 같아져 사용자의 의도가 사라진다. 그래서
 * {@code allocatedHoldingAmount} 가 {@link Double} 이고 {@code isSpeculative} 가 {@link Boolean} 이다.
 * 생성 입력({@link GoalCreateCommand})은 반대로 원시 타입이 맞다 — 거기서는 미입력이 곧 0 이다.
 *
 * <p>위치 인자 대신 이름 있는 값으로 받는 이유는 {@link GoalCreateCommand} 와 같다. 인자 6 개에
 * 플래너 필드 5 개를 더하면 11 개가 되고 같은 타입이 줄줄이 늘어선다.
 *
 * <p>목표 유형은 여기 없다. 유형이 바뀌면 이미 만든 계획의 전제가 무너지므로 수정 대상이 아니다.
 *
 * @param name                   목표 이름
 * @param targetAmount           목표 외화 총액
 * @param targetDate             마감형 목표일
 * @param budgetAmount           예산 (원)
 * @param budgetPeriod           예산 적용 주기
 * @param isSpeculative          투기성 목표 여부
 * @param allocatedHoldingAmount 이 목표에 배정한 보유 외화
 * @param preferredCadence       마감형 준비 주기 (명세 §5.2)
 * @param priorityConstraint     우선 유지할 조건 (명세 §5.1·§17)
 * @param startDate              정기형 첫 계획 시작일 (명세 §5.3)
 * @param reviewHorizonMonths    정기형 점검 기간 (명세 §5.3)
 */
public record GoalUpdateCommand(
        String name,
        Double targetAmount,
        LocalDate targetDate,
        Long budgetAmount,
        String budgetPeriod,
        Boolean isSpeculative,
        Double allocatedHoldingAmount,
        String preferredCadence,
        String priorityConstraint,
        LocalDate startDate,
        Integer reviewHorizonMonths) {
}
