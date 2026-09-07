package com.divurve.api.dto.admin;

import com.divurve.domain.fx.FxRateGapService;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.util.List;

/**
 * 환율 구멍 조회 결과 (이슈 #116).
 *
 * <p>화면의 질문은 "이 구간을 믿고 계산해도 되는가" 하나다. 그래서 관측 개수가 아니라
 * <b>빠진 구간</b>과 완전 여부를 앞세운다.
 *
 * <p>남아 있는 구멍은 백필을 한 번 돌린 뒤라면 공휴일이 아니라 <b>우리가 못 받은 날</b>이다 —
 * 고시가 없는 날은 {@code fx_rate_absences} 에 부재로 확정돼 커버리지에 포함된다.
 *
 * @param pairs 통화쌍별 커버리지
 */
public record AdminFxRateCoverageResponse(List<PairCoverage> pairs) {

    public static AdminFxRateCoverageResponse from(List<FxRateGapService.PairCoverage> coverages) {
        return new AdminFxRateCoverageResponse(
                coverages.stream().map(PairCoverage::from).toList());
    }

    /**
     * 통화쌍 하나의 커버리지.
     *
     * @param pairCode             통화쌍 6자리
     * @param rateType             환율 종류 코드
     * @param from                 요청 시작일
     * @param to                   요청 끝일
     * @param expectedBusinessDays 구간 안의 영업일 수
     * @param coveredBusinessDays  값이나 부재 확정이 있는 날 수
     * @param missingBusinessDays  빠진 영업일 수
     * @param coverageRatio        커버리지 비율 (0.0~1.0)
     * @param complete             구멍이 하나도 없는가
     * @param gaps                 빠진 연속 구간
     */
    public record PairCoverage(
            String pairCode,
            String rateType,
            LocalDate from,
            LocalDate to,
            int expectedBusinessDays,
            int coveredBusinessDays,
            int missingBusinessDays,
            @Schema(description = "커버리지 비율 (0.0~1.0)") double coverageRatio,
            boolean complete,
            List<Gap> gaps) {

        static PairCoverage from(FxRateGapService.PairCoverage source) {
            return new PairCoverage(
                    source.pairCode(),
                    source.rateType(),
                    source.from(),
                    source.to(),
                    source.expectedBusinessDays(),
                    source.coveredBusinessDays(),
                    source.missingBusinessDays(),
                    source.coverageRatio(),
                    source.complete(),
                    source.gaps().stream().map(Gap::from).toList());
        }
    }

    /**
     * 빠진 연속 구간 하나.
     *
     * @param from         구간의 첫 영업일
     * @param to           구간의 마지막 영업일
     * @param businessDays 구간 안의 빠진 영업일 수
     */
    public record Gap(LocalDate from, LocalDate to, int businessDays) {

        static Gap from(FxRateGapService.Gap gap) {
            return new Gap(gap.from(), gap.to(), gap.businessDays());
        }
    }
}
