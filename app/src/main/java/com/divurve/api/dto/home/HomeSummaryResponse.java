package com.divurve.api.dto.home;

import com.divurve.api.dto.forecast.EventsResponse.Event;
import com.divurve.api.dto.xray.XrayResponse;
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

    /**
     * 블록 순서·키·상태 ({@code state}: {@link com.divurve.domain.home.HomeSummaryService#STATE_FILLED}
     * / {@link com.divurve.domain.home.HomeSummaryService#STATE_EMPTY} /
     * {@link com.divurve.domain.home.HomeSummaryService#STATE_NOT_MEASURED}).
     *
     * <p>{@code route_pending} 는 목표 Route 기능 플래그 제거(이슈 #84)로 없어졌다 — 계산 로직이
     * 확정돼 "아직 준비 중"이라는 상태가 더는 발생하지 않는다({@code HomeSummaryService.resolveGoalsRoute}
     * Javadoc 참고, 이슈 #91).
     */
    public record BlockDto(
            int order,
            @Schema(example = "today") String key,
            @Schema(description = "블록 상태", example = "filled",
                    allowableValues = {"filled", "empty", "not_measured"})
            String state) {
    }

    /** 오늘의 핵심. */
    public record TodayDto(
            @Schema(description = "동적 조합 코드라 열거형이 아니다(값 목록을 늘어놓지 않는다). "
                    + "{@code HomeSummaryService.resolveToday} 가 생성 규칙에 따라 조합한다: "
                    + "주력 외화가 있으면 vol_{regime}_{currency_code}, 없으면 regime_{regime}. "
                    + "regime 은 calm/normal/elevated/stress 중 하나(engine.volatility.Regime), "
                    + "currency_code 는 사용자의 주력 외화 보유 통화(ISO 4217)를 소문자로 쓴 값이라 "
                    + "통화가 늘어나면 값도 함께 늘어난다.",
                    example = "vol_elevated_usd")
            String headlineCode,

            @Schema(description = "시장 국면 배지 — engine.volatility.RegimeBadgeMapper.Badge",
                    example = "caution", allowableValues = {"normal", "caution", "turbulent"})
            String badge) {
    }

    /** 위험성향·Fit 관계. */
    public record ProfileFitDto(
            @Schema(description = "대표 유형 코드(engine.riskprofile.RiskProfileScorer). "
                    + "위험성향 미측정이면 null", example = "balanced",
                    allowableValues = {"stable", "balanced", "aggressive", "challenging"},
                    nullable = true)
            String grade,

            @Schema(description = "집중도 판정 상태 — engine.concentration.ConcentrationCalculator",
                    example = "above_threshold",
                    allowableValues = {"above_threshold", "within_threshold", "unknown"})
            String concentrationStatus) {
    }

    /**
     * 외화 현황.
     *
     * <p>{@code exposure} 는 {@code GET /xray} 의 {@code exposure[]} 와 완전히 같은 스키마·산출물이다
     * (이슈 #94) — {@link XrayResponse.Exposure} 타입을 그대로 재사용해 홈 도넛과 X-Ray 도넛이 같은
     * 컴포넌트를 쓸 수 있게 한다.
     */
    public record FxStatusDto(
            @Schema(example = "0.361") double fxRatio,
            @Schema(description = "주력 통화(ISO 4217). 사용자 보유에 따라 동적으로 정해지므로 "
                    + "열거하지 않는다. 외화자산이 없으면 null", example = "USD", nullable = true)
            String topCurrencyCode,
            @Schema(example = "247200") @JsonProperty("sensitivity_1pct_krw") long sensitivity1pctKrw,
            @Schema(description = "전일 대비 변화(원). 스냅샷이 없으면 null", example = "84000",
                    nullable = true)
            Long dayChangeKrw,
            @Schema(description = "통화별 노출. GET /xray 의 exposure 와 같은 값(이슈 #94). "
                    + "원화 평가액 내림차순, 외화자산이 없으면 빈 배열(FR-CM-09)")
            List<XrayResponse.Exposure> exposure) {
    }

    /** 목표 영역. {@code route_enabled} 는 이슈 #84 에서 제거했다 — 기능이 항상 열려 있다. */
    public record GoalsRouteDto(List<ActiveGoalDto> activeGoals) {
    }

    /**
     * 활성 목표 요약.
     *
     * <p>{@code status} 는 여기서 값 규칙을 문서화하지 않는다 — {@code Goal} 목표 상태 어휘는
     * 이슈 #84·#85 가 재정의할 예정이라, 지금 확정하면 그 이슈가 끝나기 전에 틀린 문서가 된다
     * (이슈 #91 소유 파일 범위 안이지만 값이 확정되지 않아 의도적으로 비워둔다).
     */
    public record ActiveGoalDto(
            String id, String name,
            @Schema(description = "목표 통화(ISO 4217). 지원 통화 마스터데이터에 따라 늘어날 수 있어 "
                    + "열거하지 않는다", example = "USD")
            String currencyCode,
            double targetAmount, LocalDate targetDate, String status) {
    }

    /** 주의 필요. */
    public record AttentionDto(
            @Schema(description = "시장 국면 배지 — engine.volatility.RegimeBadgeMapper.Badge",
                    example = "caution", allowableValues = {"normal", "caution", "turbulent"})
            String regimeBadge,
            List<Event> upcomingEvents) {
    }

    /** Forecast 요약. 계산 불가 시 {@code null}(블록 {@code state=empty}). */
    public record ForecastDto(
            @Schema(description = "통화쌍 코드(기준+표시 통화 ISO 4217 조합). 현재는 "
                    + "HomeSummaryService.DEFAULT_PAIR_CODE 하나만 쓰지만 지원 통화쌍이 늘면 "
                    + "값도 늘어나므로 열거하지 않는다", example = "USDKRW")
            String pairCode,
            @Schema(example = "1382.40") double currentRate,
            @JsonProperty("interval_80") Interval80Dto interval80,
            @Schema(description = "스파크라인용 최근 30영업일 시계열(시간순). GET /forecast 의 전체 "
                    + "history 와는 다른 부분집합이니 혼동하지 말 것(이슈 #94)")
            List<HistoryPointDto> history) {
    }

    /** 80퍼센트 예측 구간. */
    public record Interval80Dto(
            @Schema(example = "1346.0") double lo,
            @Schema(example = "1431.0") double hi) {
    }

    /** Forecast 스파크라인 한 점 (이슈 #94). */
    public record HistoryPointDto(
            @Schema(example = "2026-08-01") LocalDate date,
            @Schema(example = "1382.40") double rate) {
    }
}
