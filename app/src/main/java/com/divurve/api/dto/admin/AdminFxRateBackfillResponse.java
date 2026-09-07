package com.divurve.api.dto.admin;

import com.divurve.domain.fx.FxRateGapService;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;

/**
 * 환율 백필 결과 (이슈 #116).
 *
 * <p>"몇 건 넣었다" 가 아니라 <b>무엇이 좋아졌는가</b>를 보여 준다 — 백필 전후 커버리지를 나란히
 * 담는다. 백필이 돌았는데도 구멍이 남았다면 그 구간은 ECOS 도 답하지 못한 구간이다.
 *
 * @param pairs               쌍별 결과
 * @param totalFilled         값으로 채운 날 수 합계
 * @param totalConfirmedAbsent 고시가 없다고 확정한 날 수 합계
 * @param hasFailure          실패한 쌍이 하나라도 있는가
 * @param complete            모든 쌍이 완전해졌는가
 * @param backfilledAt        수행 시각
 */
public record AdminFxRateBackfillResponse(
        List<PairBackfill> pairs,
        int totalFilled,
        @Schema(description = "고시가 없다고 확정한 날 수 — 공휴일이 여기로 들어간다")
        int totalConfirmedAbsent,
        boolean hasFailure,
        boolean complete,
        Instant backfilledAt) {

    public static AdminFxRateBackfillResponse from(FxRateGapService.BackfillReport report) {
        return new AdminFxRateBackfillResponse(
                report.pairs().stream().map(PairBackfill::from).toList(),
                report.totalFilled(),
                report.totalConfirmedAbsent(),
                report.hasFailure(),
                report.complete(),
                report.backfilledAt());
    }

    /**
     * 통화쌍 하나의 백필 결과.
     *
     * @param pairCode             통화쌍 6자리
     * @param filled               값으로 채운 날 수
     * @param confirmedAbsent      고시가 없다고 확정한 날 수
     * @param missingBefore        백필 전 빠진 영업일 수
     * @param missingAfter         백필 후 빠진 영업일 수
     * @param complete             백필 후 완전해졌는가
     * @param remainingGaps        백필 후에도 남은 구간
     * @param failureReason        실패 사유. 성공이면 null
     */
    public record PairBackfill(
            String pairCode,
            int filled,
            int confirmedAbsent,
            int missingBefore,
            int missingAfter,
            boolean complete,
            List<AdminFxRateCoverageResponse.Gap> remainingGaps,
            String failureReason) {

        static PairBackfill from(FxRateGapService.PairBackfill source) {
            return new PairBackfill(
                    source.pairCode(),
                    source.filled(),
                    source.confirmedAbsent(),
                    source.missingBefore(),
                    source.missingAfter(),
                    source.complete(),
                    source.remainingGaps().stream()
                            .map(AdminFxRateCoverageResponse.Gap::from)
                            .toList(),
                    source.failureReason());
        }
    }
}
