package com.divurve.api.dto.goal;

import jakarta.validation.constraints.NotBlank;
import java.time.LocalDate;

/**
 * 목표 생성 요청 (POST /goals, 명세 3.2).
 * {@code kind} 가 recurring 이면 {@code purpose} 는 invest 만 허용한다.
 * {@code held_amount} 는 받지 않는다 — 서버가 /deposits 에서 조회한다 (FR-RT-05).
 *
 * <p>이름이 비어 있는지만 여기서 {@code @Valid} 로 앞당긴다(이슈 #77). 나머지 검증은
 * {@code GoalService} 에 두고 {@code InvalidRequestException} 의 field 인자에 스네이크케이스
 * 문자열을 직접 쓴다 — 통화 화이트리스트·목적 ENUM·목표일 과거 여부는 형식이 아니라 도메인 규칙이라
 * 애초에 서비스 계층이 맞고, {@code target_amount} 의 0 이하 검사도 같은 곳에 모아 뒀다.
 *
 * <p>{@code target_amount} 도 서비스 계층에 둔 이유 — 형식 검증이라 Bean Validation 으로 옮길 수 있지만,
 * 같은 필드를 두 곳에서 검증하지 않도록 나머지 목표 규칙과 한곳에 모았다. 참고로 여러 단어 필드에
 * 제약을 달 때는 응답 {@code field} 가 카멜케이스로 새지 않는지 확인해야 한다 — 이 문제는 이슈 #77
 * 조사 중 발견돼 {@code GlobalExceptionHandler} 에서 별도로 고쳤다(이슈 #75).
 *
 * <p>상한을 두지 않은 {@code targetAmount} 는 의도적이다 — 근거 없는 임의 상한은 정책 결정이다.
 *
 * <p>뒤쪽 다섯 필드는 플래너 계산이 읽는 값이다(이슈 #195). 전부 선택이며 비우면 서버가 유형별
 * 기본값을 정한다 — 기존 클라이언트가 보내지 않아도 동작이 바뀌지 않으므로 브레이킹 체인지가
 * 아니다. 다만 정기형은 {@code startDate} 와 {@code reviewHorizonMonths} 가 없으면 계획을 계산할
 * 수 없어 {@code GoalService} 가 필수로 본다.
 */
public record GoalCreateRequest(
        @NotBlank(message = "목표 이름은 필수입니다.") String name,
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
}
