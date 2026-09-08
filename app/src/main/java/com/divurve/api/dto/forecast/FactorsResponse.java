package com.divurve.api.dto.forecast;

import com.divurve.domain.forecast.ForecastService;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * 전망 동인 응답 ({@code GET /forecast/factors}, 명세 v2 §3 — 우선순위 S).
 *
 * <p>🔒 <b>L2 — 표시 전용이다.</b> 이 값은 어떤 계산의 입력도 되지 않으며 {@code /route/context}
 * 에도 포함되지 않는다(FR-FC-12). 출처가 확정될 때까지 빈 배열이다 —
 * 없는 근거를 만들어내지 않는다(FR-CM-10).
 */
@Schema(description = "L2 — 전망 동인. 표시 전용이며 계산 입력이 아니다.")
public record FactorsResponse(
        @Schema(description = "통화쌍 코드(기준+표시 통화 ISO 4217 조합). 지원 통화쌍이 늘면 값도 "
                + "늘어나므로 열거하지 않는다", example = "USDKRW")
        String pairCode,
        @Schema(description = "동인 목록. 출처 확정 전까지 빈 배열이다.") List<Factor> factors) {

    /** 도메인 뷰를 응답 DTO 로 옮긴다. */
    public static FactorsResponse from(ForecastService.FactorsView view) {
        // 서버가 보유한 동인이 없으므로 항상 빈 배열이다 — 계약만 유지한다(FR-CM-10).
        return new FactorsResponse(view.pairCode(), List.of());
    }

    /**
     * 동인 한 건.
     *
     * @param key            동인 키
     * @param label          표시 이름
     * @param contributionPp 기여도 (퍼센트포인트)
     * @param direction      기여 방향
     */
    @Schema(description = "동인 한 건")
    public record Factor(
            String key,
            String label,
            double contributionPp,
            @Schema(description = "기여 방향. 서버가 보유한 동인이 없어 factors 가 항상 빈 배열이라 "
                    + "이 필드가 채워지는 경로가 아직 없다 — 값 목록은 동인 데이터 도입 시(#57·#74) "
                    + "확정한다.")
            String direction) {
    }
}
