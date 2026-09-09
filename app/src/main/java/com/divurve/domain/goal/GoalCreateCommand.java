package com.divurve.domain.goal;

import java.time.LocalDate;

/**
 * 목표 생성 입력 (이슈 #195).
 *
 * <p>위치 인자 대신 이름 있는 값으로 받는다. 이전 {@code GoalService.create} 는 인자를 12 개
 * 받았고 플래너 필드 5 개를 더하면 17 개가 된다 — 대부분 {@code String}·{@code double}·
 * {@code long} 이라 순서를 잘못 넘겨도 컴파일러가 잡지 못한다. 이슈 #193 이 정확히 그런
 * 조용한 누락이었다({@code kind} 를 받고도 {@code goal_type} 을 채우지 않아 정기형이 전부
 * 마감형으로 저장됐다).
 *
 * <p>{@code ownerId} 는 여기 없다. 요청 본문이 아니라 인증에서 오는 값이라 섞지 않는다.
 *
 * @param name                   목표 이름
 * @param kind                   목표 유형 코드. {@code recurring} 만 정기형이고 나머지는 마감형이다
 * @param purpose                목적 — 안전 비율 하한과 마감 버퍼 산출에 쓴다
 * @param currencyCode           준비할 외화
 * @param targetAmount           목표 외화 총액
 * @param targetDate             마감형 목표일
 * @param recurInterval          정기형 반복 주기
 * @param budgetAmount           예산 (원)
 * @param budgetCurrencyCode     예산 통화
 * @param budgetPeriod           예산 적용 주기
 * @param isSpeculative          투기성 목표 여부
 * @param allocatedHoldingAmount 이 목표에 배정할 보유 외화. 미입력이면 0
 * @param preferredCadence       마감형 준비 주기. 미입력이면 유형별 기본값 (명세 §5.2)
 * @param priorityConstraint     우선 유지할 조건. 미입력이면 유형별 기본값 (명세 §5.1·§17)
 * @param startDate              정기형 첫 계획 시작일 (명세 §5.3)
 * @param reviewHorizonMonths    정기형 점검 기간 (명세 §5.3)
 */
public record GoalCreateCommand(
        String name,
        String kind,
        String purpose,
        String currencyCode,
        double targetAmount,
        LocalDate targetDate,
        String recurInterval,
        long budgetAmount,
        String budgetCurrencyCode,
        String budgetPeriod,
        boolean isSpeculative,
        double allocatedHoldingAmount,
        String preferredCadence,
        String priorityConstraint,
        LocalDate startDate,
        Integer reviewHorizonMonths) {

    /**
     * 계산이 읽는 유형 코드 (이슈 #193).
     *
     * <p>정기형으로 인식하는 값은 {@link GoalType#RECURRING} 하나이며 대소문자를 가리지 않는다.
     */
    public String goalType() {
        return GoalType.RECURRING.equalsIgnoreCase(kind) ? GoalType.RECURRING : GoalType.DEADLINE;
    }

    /** 정기형 목표인지 — 필수 필드와 기본값이 갈린다. */
    public boolean isRecurring() {
        return GoalType.RECURRING.equals(goalType());
    }
}
