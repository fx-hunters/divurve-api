package com.divurve.api.dto.admin;

import com.divurve.domain.fx.FxRateStatusService;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * 환율·거시지표 적재 현황 (이슈 #128).
 *
 * <p>관리자 콘솔의 수동 갱신 카드가 화면 진입 시점에 "마지막 갱신" 을 표기하는 근거다.
 *
 * <p><b>{@code fetched_at} 과 {@code quote_date} 는 다른 값이다.</b> 앞은 우리가 언제
 * 가져왔는가, 뒤는 어느 날짜까지 채워져 있는가다. 오늘 적재가 돌아도 ECOS 가 어제까지만
 * 고시했다면 뒤엣값은 어제다 — 화면에서 섞지 마라.
 *
 * <p><b>{@code macro.last_refreshed_at} 은 항상 {@code null} 이다.</b> 거시지표 갱신은
 * 받아온 값을 저장하지 않아(ALFRED vintage 문제) 서버 기준 마지막 갱신이 존재하지 않는다.
 * 자리를 비워 두는 이유는 프론트가 두 카드를 같은 코드로 그리기 때문이다 — 실행 이력을
 * 남기기로 판단이 바뀌면 이 자리에 값이 실린다.
 *
 * <p>전역 {@code non_null} 직렬화 때문에 {@code null} 필드는 키 자체가 빠진다. 프론트는
 * 키 부재를 {@code null} 로 읽는다.
 */
@Schema(description = "환율·거시지표 적재 현황")
public record AdminFxRateStatusResponse(Fx fx, Macro macro, Instant checkedAt) {

    public static AdminFxRateStatusResponse from(FxRateStatusService.StatusResult result) {
        return new AdminFxRateStatusResponse(
                Fx.from(result), Macro.unavailable(), result.checkedAt());
    }

    /**
     * 환율 적재 현황.
     *
     * @param lastFetchedAt 전체 통화쌍 중 마지막 적재 시각. 적재가 없으면 {@code null}
     * @param lastQuoteDate 전체 통화쌍 중 가장 최근 고시일. 적재가 없으면 {@code null}
     * @param pairs         통화쌍별 현황. 행이 하나도 없는 쌍은 나오지 않는다
     */
    @Schema(description = "환율 적재 현황")
    public record Fx(Instant lastFetchedAt, LocalDate lastQuoteDate, List<PairStatus> pairs) {

        static Fx from(FxRateStatusService.StatusResult result) {
            return new Fx(
                    result.lastFetchedAt(),
                    result.lastQuoteDate(),
                    result.pairs().stream().map(PairStatus::from).toList());
        }
    }

    /**
     * 통화쌍 하나의 현황.
     *
     * @param pairCode      통화쌍 코드
     * @param lastFetchedAt 마지막 적재 시각
     * @param lastQuoteDate 마지막 고시일
     */
    @Schema(description = "통화쌍별 적재 현황")
    public record PairStatus(
            @Schema(description = "통화쌍 6자리. 고정 목록이 아니라 통화쌍 마스터"
                    + "(GET /api/v1/admin/currencies)가 정하는 값이다")
            String pairCode,
            Instant lastFetchedAt,
            LocalDate lastQuoteDate) {

        static PairStatus from(FxRateStatusService.PairStatus pair) {
            return new PairStatus(pair.pairCode(), pair.lastFetchedAt(), pair.lastQuoteDate());
        }
    }

    /**
     * 거시지표 적재 현황.
     *
     * @param lastRefreshedAt 마지막 갱신 시각. 저장하지 않으므로 현재는 항상 {@code null}
     */
    @Schema(description = "거시지표 적재 현황 — 저장하지 않으므로 현재는 비어 있다")
    public record Macro(Instant lastRefreshedAt) {

        static Macro unavailable() {
            return new Macro(null);
        }
    }
}
