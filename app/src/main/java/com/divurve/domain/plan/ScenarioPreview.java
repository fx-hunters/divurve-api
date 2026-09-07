package com.divurve.domain.plan;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 상황 변화로 다시 계산한 계획과 그 차이 (플래너 명세 §16·§17).
 *
 * <p><b>활성 계획은 바뀌지 않았다</b> (§21-9). 새 계획은 {@code draft} 로 저장돼 있고,
 * {@link #draftPlanId} 가 그 임시 식별자다 — 사용자가 적용을 누르면 이 id 로
 * {@code POST /plans/{id}/apply} 를 호출한다. 적용 시점에 다시 계산하지 않는 이유는 그
 * 사이 환율이 움직이면 사용자가 승인한 것과 다른 계획이 활성화되기 때문이다 (§18).
 *
 * @param basePlanId        비교 기준이 된 현재 계획
 * @param baseVersion       현재 계획의 버전
 * @param draftPlanId       적용 대상 임시 계획 id (§18)
 * @param draftVersion      적용하면 붙을 버전 — 적용 시점에 다시 확정된다 (§21-10)
 * @param changeReasonCode  변경 이유 (명세 §16)
 * @param priorityConstraint 재계산의 기준이 된 사용자의 우선 조건 (명세 §17)
 * @param before            변경 전 요약
 * @param after             변경 후 요약
 * @param changedSteps      달라진 회차만 (같은 회차는 싣지 않는다 — 무엇이 바뀌었는지가 요점이다)
 * @param keptConstraints   유지된 조건
 * @param brokenConstraints 유지하지 못한 조건 — 숨기지 않는다 (§21-8)
 * @param budgetState       재계산 후 예산 가능 상태 (명세 §9.6)
 * @param adjustmentOptions 예산으로 감당되지 않을 때의 선택지 (명세 §15). 감당되면 빈 목록
 * @param warnings          경고·가정 (명세 §20)
 */
public record ScenarioPreview(
        UUID basePlanId,
        int baseVersion,
        UUID draftPlanId,
        int draftVersion,
        String changeReasonCode,
        String priorityConstraint,
        Side before,
        Side after,
        List<StepChange> changedSteps,
        List<String> keptConstraints,
        List<String> brokenConstraints,
        String budgetState,
        List<String> adjustmentOptions,
        List<String> warnings) {

    public ScenarioPreview {
        changedSteps = List.copyOf(Objects.requireNonNull(changedSteps, "changedSteps"));
        keptConstraints = List.copyOf(Objects.requireNonNull(keptConstraints, "keptConstraints"));
        brokenConstraints = List.copyOf(Objects.requireNonNull(brokenConstraints, "brokenConstraints"));
        adjustmentOptions = List.copyOf(Objects.requireNonNull(adjustmentOptions, "adjustmentOptions"));
        warnings = List.copyOf(Objects.requireNonNull(warnings, "warnings"));
    }

    /**
     * 한쪽 계획의 요약 — 변경 전후를 같은 모양으로 담아 프론트가 나란히 놓을 수 있게 한다.
     *
     * @param remainingAmount 앞으로 준비할 외화
     * @param targetDate      목표 날짜 (마감형) / 점검 종료일 (정기형)
     * @param totalRounds     전체 회차 수
     * @param openRounds      아직 실행하지 않은 회차 수
     * @param perRoundAmount  회차당 외화 금액. 회차가 없으면 {@code null}
     * @param roundBudgetKrw  정기형 회차 예산. 마감형은 {@code null}
     * @param costRange       예상 원화 비용 범위
     */
    public record Side(
            BigDecimal remainingAmount,
            LocalDate targetDate,
            int totalRounds,
            int openRounds,
            BigDecimal perRoundAmount,
            Long roundBudgetKrw,
            PlanDraft.CostRange costRange) {
    }

    /**
     * 달라진 회차 하나.
     *
     * <p>{@code null} 인 쪽은 그 회차가 한쪽에만 있다는 뜻이다 — {@code before} 가 {@code null}
     * 이면 새로 생긴 회차, {@code after} 가 {@code null} 이면 사라진 회차다.
     *
     * @param seq           회차 번호
     * @param changeType    {@link #ADDED} · {@link #REMOVED} · {@link #MODIFIED}
     * @param dateBefore    변경 전 예정일
     * @param dateAfter     변경 후 예정일
     * @param amountBefore  변경 전 외화 금액
     * @param amountAfter   변경 후 외화 금액
     */
    public record StepChange(
            int seq,
            String changeType,
            LocalDate dateBefore,
            LocalDate dateAfter,
            BigDecimal amountBefore,
            BigDecimal amountAfter) {
    }

    /** 변경 후에만 있는 회차. */
    public static final String ADDED = "ADDED";

    /** 변경 전에만 있는 회차. */
    public static final String REMOVED = "REMOVED";

    /** 양쪽에 있으나 날짜나 금액이 다른 회차. */
    public static final String MODIFIED = "MODIFIED";
}
