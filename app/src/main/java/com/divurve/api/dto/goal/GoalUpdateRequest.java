package com.divurve.api.dto.goal;

import java.time.LocalDate;

/**
 * 목표 수정 요청 (PUT /goals/{id}). 변경할 필드만 담는다 — 부분 갱신 계약이므로 모든 필드가
 * {@code null} 을 "값 변경 없음"으로 허용해야 한다({@code @NotNull} 을 달면 정상 요청이 막힌다).
 *
 * <p>{@code targetAmount}(0 이하 금지)·{@code targetDate}(과거 금지)·{@code name}(공백 금지)은
 * 값이 있을 때만 검증한다. 세 가지 모두 {@code GoalService.update} 에서 null 이 아닐 때만 확인하고,
 * {@code field} 에 스네이크케이스 문자열을 직접 써서 응답한다 — 생성 경로와 같은 규칙을 한곳에서
 * 관리하기 위해서다({@link GoalCreateRequest} 주석 참고).
 *
 * <p>뒤쪽 다섯 필드는 플래너 계산이 읽는 값이다(이슈 #197). {@code allocatedHoldingAmount} 가
 * {@link Double} 인 것이 핵심이다 — 원시 {@code double} 로 받으면 <b>"0 으로 바꿈"과 "변경 없음"이
 * 같아져</b> 배정을 되돌리려는 의도가 사라진다. 생성 요청은 반대로 원시 타입이 맞다.
 *
 * <p>목표 유형은 받지 않는다. 유형이 바뀌면 이미 만든 계획의 전제가 무너진다.
 */
public record GoalUpdateRequest(
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
