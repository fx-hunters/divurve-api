package com.divurve.api.dto.plan;

import com.divurve.domain.plan.PlanDraft;
import com.divurve.domain.plan.ScenarioPreview;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 상황 변화 미리보기 응답 (플래너 명세 §16·§17).
 *
 * <p><b>활성 계획은 바뀌지 않았다</b> (§21-9). {@code draft_plan_id} 로
 * {@code POST /plans/{id}/apply} 를 호출해야 실제로 적용된다.
 *
 * @param basePlanId         비교 기준이 된 현재 계획
 * @param baseVersion        현재 계획 버전
 * @param draftPlanId        적용 대상 임시 계획 id (§18)
 * @param draftVersion       적용하면 붙을 버전. 적용 시점에 다시 확정된다 (§21-10)
 * @param changeReasonCode   변경 이유
 * @param priorityConstraint 재계산 기준이 된 사용자의 우선 조건 (§17)
 * @param before             변경 전 요약
 * @param after              변경 후 요약
 * @param changedSteps       달라진 회차만
 * @param keptConstraints    유지된 조건
 * @param brokenConstraints  유지하지 못한 조건 — 우선 조건이 깨졌다면 맨 앞에 온다
 * @param budgetState        재계산 후 예산 가능 상태 (§9.6)
 * @param adjustmentOptions  예산으로 감당되지 않을 때의 선택지 (§15). 감당되면 빈 배열
 * @param warnings           경고·가정 (§20)
 */
@Schema(description = "상황 변화 미리보기 (플래너 명세 §16). 활성 계획은 변경되지 않는다")
public record ScenarioPreviewResponse(
        String basePlanId,
        int baseVersion,
        String draftPlanId,
        int draftVersion,
        String changeReasonCode,
        String priorityConstraint,
        SideResponse before,
        SideResponse after,
        List<StepChangeResponse> changedSteps,
        List<String> keptConstraints,
        List<String> brokenConstraints,
        String budgetState,
        List<String> adjustmentOptions,
        List<String> warnings) {

    /** 도메인 미리보기를 응답으로 옮긴다. */
    public static ScenarioPreviewResponse from(ScenarioPreview preview) {
        return new ScenarioPreviewResponse(
                preview.basePlanId().toString(),
                preview.baseVersion(),
                preview.draftPlanId().toString(),
                preview.draftVersion(),
                preview.changeReasonCode(),
                preview.priorityConstraint(),
                SideResponse.from(preview.before()),
                SideResponse.from(preview.after()),
                preview.changedSteps().stream().map(StepChangeResponse::from).toList(),
                preview.keptConstraints(),
                preview.brokenConstraints(),
                preview.budgetState(),
                preview.adjustmentOptions(),
                preview.warnings());
    }

    /**
     * 한쪽 계획의 요약. 변경 전후가 같은 모양이라 프론트가 나란히 놓을 수 있다.
     *
     * @param remainingAmount 앞으로 준비할 외화
     * @param targetDate      목표 날짜 / 점검 종료일
     * @param totalRounds     전체 회차 수
     * @param openRounds      아직 실행하지 않은 회차 수
     * @param perRoundAmount  회차당 외화 금액. 회차가 없으면 {@code null}
     * @param roundBudgetKrw  정기형 회차 예산. 마감형은 {@code null}
     * @param costRange       예상 원화 비용 범위
     */
    @Schema(description = "변경 전/후 요약")
    public record SideResponse(
            BigDecimal remainingAmount,
            LocalDate targetDate,
            int totalRounds,
            int openRounds,
            BigDecimal perRoundAmount,
            Long roundBudgetKrw,
            CostRangeResponse costRange) {

        static SideResponse from(ScenarioPreview.Side side) {
            return new SideResponse(
                    side.remainingAmount(),
                    side.targetDate(),
                    side.totalRounds(),
                    side.openRounds(),
                    side.perRoundAmount(),
                    side.roundBudgetKrw(),
                    CostRangeResponse.from(side.costRange()));
        }
    }

    /**
     * 예상 원화 비용 범위 (명세 §9.3).
     *
     * @param lowKrw  환율 하단 기준
     * @param baseKrw 기준 환율
     * @param highKrw 환율 상단 기준
     */
    @Schema(description = "예상 원화 비용 범위")
    public record CostRangeResponse(long lowKrw, long baseKrw, long highKrw) {

        /** 비용 요약이 없는 옛 계획도 있다 — 그때는 범위를 만들어내지 않고 {@code null} 을 낸다. */
        static CostRangeResponse from(PlanDraft.CostRange range) {
            return range == null
                    ? null
                    : new CostRangeResponse(range.lowKrw(), range.baseKrw(), range.highKrw());
        }
    }

    /**
     * 달라진 회차 하나. {@code null} 인 쪽은 그 회차가 한쪽에만 있다는 뜻이다.
     *
     * @param seq          회차 번호
     * @param changeType   {@code ADDED} · {@code REMOVED} · {@code MODIFIED}
     * @param dateBefore   변경 전 예정일
     * @param dateAfter    변경 후 예정일
     * @param amountBefore 변경 전 외화 금액
     * @param amountAfter  변경 후 외화 금액
     */
    @Schema(description = "달라진 회차")
    public record StepChangeResponse(
            int seq,
            String changeType,
            LocalDate dateBefore,
            LocalDate dateAfter,
            BigDecimal amountBefore,
            BigDecimal amountAfter) {

        static StepChangeResponse from(ScenarioPreview.StepChange change) {
            return new StepChangeResponse(
                    change.seq(),
                    change.changeType(),
                    change.dateBefore(),
                    change.dateAfter(),
                    change.amountBefore(),
                    change.amountAfter());
        }
    }
}
