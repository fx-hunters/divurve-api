package com.divurve.api.dto.home;

import com.divurve.api.dto.forecast.EventsResponse.Event;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.util.List;

/**
 * 홈 요약 조회 응답 (GET /home/summary, API 명세 v2 §5.11).
 * 6블록 순서는 고정이며 서버가 사용자별로 재정렬하지 않는다(FR-HM-07, NFR-UI-01).
 * 데이터가 없는 블록도 생략하지 않고 {@code state} 로만 구분한다.
 *
 * <p>{@code sensitivity_1pct_krw}·{@code interval_80} 은 전역 SNAKE_CASE 전략이 숫자 앞에
 * 밑줄을 넣지 않으므로 {@link JsonProperty} 로 명세의 키를 그대로 고정한다(이슈 #60).
 */
public record HomeSummaryResponse(
        List<BlockDto> blocks,
        TodayDto today,
        ProfileFitDto profileFit,
        FxStatusDto fxStatus,
        GoalsRouteDto goalsRoute,
        AttentionDto attention,
        ForecastDto forecast) {

    /** 블록 순서·키·상태. {@code state}: filled/empty/not_measured. */
    public record BlockDto(
            int order,
            @Schema(example = "today") String key,
            @Schema(example = "filled") String state) {
    }

    /** 오늘의 핵심. */
    public record TodayDto(
            @Schema(example = "vol_elevated_usd") String headlineCode,
            @Schema(example = "caution") String badge) {
    }

    /** 위험성향·Fit 관계. */
    public record ProfileFitDto(
            @Schema(description = "대표 유형 코드. 위험성향 미측정이면 null", example = "balanced",
                    nullable = true)
            String grade,
            @Schema(example = "above_threshold") String concentrationStatus) {
    }

    /** 외화 현황. */
    public record FxStatusDto(
            @Schema(example = "0.361") double fxRatio,
            @Schema(example = "USD") String topCurrencyCode,
            @Schema(example = "247200") @JsonProperty("sensitivity_1pct_krw") long sensitivity1pctKrw,
            @Schema(description = "전일 대비 변화(원). 스냅샷이 없으면 null", example = "84000",
                    nullable = true)
            Long dayChangeKrw) {
    }

    /** 목표 영역. {@code route_enabled} 는 이슈 #84 에서 제거했다 — 기능이 항상 열려 있다. */
    public record GoalsRouteDto(List<ActiveGoalDto> activeGoals) {
    }

    /** 활성 목표 요약. */
    public record ActiveGoalDto(
            String id, String name, String currencyCode, double targetAmount,
            LocalDate targetDate, String status) {
    }

    /** 주의 필요. */
    public record AttentionDto(
            @Schema(example = "caution") String regimeBadge,
            List<Event> upcomingEvents) {
    }

    /** Forecast 요약. 계산 불가 시 {@code null}(블록 {@code state=empty}). */
    public record ForecastDto(
            @Schema(example = "USDKRW") String pairCode,
            @Schema(example = "1382.40") double currentRate,
            @JsonProperty("interval_80") Interval80Dto interval80) {
    }

    /** 80퍼센트 예측 구간. */
    public record Interval80Dto(
            @Schema(example = "1346.0") double lo,
            @Schema(example = "1431.0") double hi) {
    }
}
