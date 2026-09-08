package com.divurve.api.dto.forecast;

import com.divurve.domain.forecast.ForecastService;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.util.List;

/**
 * 예측 범위·변동성·내 자산 영향 응답 ({@code GET /forecast}, 명세 v2 §5.7).
 *
 * <h2>🔒 L1 / L2 경계 (FR-FC-12)</h2>
 * <ul>
 *   <li><b>L1</b>: {@code base_rate} · {@code band} · {@code volatility} — 계산 입력이 될 수 있다.</li>
 *   <li><b>L2</b>: {@code model_path} — <b>표시 전용</b>이며 다른 계산의 입력으로 넘기지 않는다.
 *       {@code RouteContext} 계약에서도 의도적으로 빠져 있다.</li>
 * </ul>
 * <b>방향 확률 필드를 두지 않는다</b> — 요구사항 §2.2 가 "사라·팔라" 형태의 지시를 금지한다.
 *
 * <p>v1 대비: {@code path} → {@code band} (음영을 "변동성"이라 부르지 않는다, FR-FC-04·05),
 * {@code realized_30d} → {@code vol_30d}, {@code percentile_5y} → {@code vol_percentile_5y},
 * {@code interval_80.vs_3y_avg} 삭제(근거 없는 상수 0 이었다),
 * {@code base_date}·{@code labels}·{@code model_info}·{@code uncertainty_note} 신설.
 *
 * <p>{@code interval_80}·{@code vol_30d}·{@code vol_percentile_5y}·{@code per_1pct_krw} 는
 * 전역 SNAKE_CASE 전략이 다음절 단어 뒤 숫자 앞에 밑줄을 넣지 않으므로 {@link JsonProperty} 로
 * 명세의 키를 그대로 고정한다(이슈 #60). {@code p50Lo}·{@code p50Hi}·{@code p80Lo}·{@code p80Hi} 는
 * 대문자 {@code L}·{@code H} 앞에서 전략이 정상 동작해 이미 {@code p50_lo} 등으로 나가지만, 같은
 * 이유로 명세 키를 명시적으로 고정해 둔다.
 */
@Schema(description = "예측 범위·변동성·내 자산 영향. 방향 확률 필드는 두지 않는다.")
public record ForecastResponse(
        @Schema(description = "통화쌍", example = "USDKRW") String pairCode,
        @Schema(description = "지평 (일)", example = "30") int horizonDays,
        @Schema(description = "계산 기준일", example = "2026-09-01") LocalDate baseDate,
        @Schema(description = "현재 환율", example = "1382.4") double currentRate,

        @Schema(description = "L1 — 드리프트 0 기준선. 계산에 쓰이는 유일한 중앙값", example = "1382.4")
        double baseRate,

        @Schema(description = "최근 관측") List<History> history,

        @Schema(description = "L1 — 예측 범위 / 불확실성 구간. '변동성'이 아니다.")
        List<Band> band,

        @Schema(description = "L2 — 모델의 참고 중심 경로. 표시 전용이며 계산 입력이 아니다(FR-FC-12).")
        List<ModelPoint> modelPath,

        @Schema(description = "지평 끝 80퍼센트 구간") @JsonProperty("interval_80") Interval interval80,
        @Schema(description = "L1 — 변동성 지표") Volatility volatility,
        @Schema(description = "환율 1퍼센트 변동 시 내 자산 영향") UserImpact userImpact,
        @Schema(description = "음영·경로의 표시 라벨") Labels labels,
        @Schema(description = "구간 수준·가정·한계") ModelInfo modelInfo,
        @Schema(description = "불확실성 안내") String uncertaintyNote,
        @Schema(description = "고지") String disclaimer) {

    /** 도메인 뷰를 응답 DTO 로 옮긴다. */
    public static ForecastResponse from(ForecastService.ForecastView view) {
        return new ForecastResponse(
                view.pairCode(),
                view.horizonDays(),
                view.baseDate(),
                view.currentRate(),
                view.baseRate(),
                view.history().stream().map(p -> new History(p.date(), p.rate())).toList(),
                view.band().stream()
                        .map(p -> new Band(p.date(), p.p50Lo(), p.p50Hi(), p.p80Lo(), p.p80Hi()))
                        .toList(),
                view.modelPath().stream().map(p -> new ModelPoint(p.date(), p.rate())).toList(),
                new Interval(view.interval80().lo(), view.interval80().hi(), view.interval80().widthPct()),
                new Volatility(
                        view.volatility().vol30d(),
                        view.volatility().volPercentile5y(),
                        view.volatility().regime()),
                new UserImpact(view.userImpact().per1pctKrw(), view.userImpact().assetKrw()),
                new Labels(view.labels().band(), view.labels().modelPath()),
                new ModelInfo(
                        view.modelInfo().intervalLevels(),
                        view.modelInfo().assumptions(),
                        view.modelInfo().limitations()),
                view.uncertaintyNote(),
                view.disclaimer());
    }

    /**
     * 과거 환율 한 점.
     *
     * @param d    날짜
     * @param rate 환율
     */
    @Schema(description = "과거 환율 한 점")
    public record History(LocalDate d, double rate) {
    }

    /**
     * 예측 범위 한 점.
     *
     * @param d     날짜
     * @param p50Lo 50퍼센트 구간 하단
     * @param p50Hi 50퍼센트 구간 상단
     * @param p80Lo 80퍼센트 구간 하단
     * @param p80Hi 80퍼센트 구간 상단
     */
    @Schema(description = "예측 범위 한 점 (50·80퍼센트 경계)")
    public record Band(
            LocalDate d,
            @JsonProperty("p50_lo") double p50Lo,
            @JsonProperty("p50_hi") double p50Hi,
            @JsonProperty("p80_lo") double p80Lo,
            @JsonProperty("p80_hi") double p80Hi) {
    }

    /**
     * 모델 참고 중심 경로 한 점 (L2 — 표시 전용).
     *
     * @param d    날짜
     * @param rate 중심 경로 값
     */
    @Schema(description = "L2 — 모델 참고 중심 경로 한 점. 계산 입력이 아니다.")
    public record ModelPoint(LocalDate d, double rate) {
    }

    /**
     * 지평 끝 80퍼센트 구간.
     *
     * @param lo       하단
     * @param hi       상단
     * @param widthPct 기준선 대비 폭 비율
     */
    @Schema(name = "ForecastInterval",
            description = "지평 끝 80퍼센트 구간 — 하단·상단과 기준선 대비 폭. 폭 없이 하단·상단만 담는 "
                    + "RouteContextResponse.Forecast.Interval 과 구조가 달라 이름을 분리했다(이슈 #88)")
    public record Interval(
            @Schema(example = "1345.61") double lo,
            @Schema(example = "1420.19") double hi,
            @Schema(description = "기준선 대비 폭 비율", example = "0.054") double widthPct) {
    }

    /**
     * 변동성 지표.
     *
     * @param vol30d          30일 실현변동성 (연환산)
     * @param volPercentile5y 5년 변동성 백분위 (0~1 비율)
     * @param regime          국면 4종
     */
    @Schema(description = "변동성 지표. 예측 범위(band)와는 별개 지표다(FR-FC-04·05).")
    public record Volatility(
            @Schema(example = "0.061") @JsonProperty("vol_30d") double vol30d,
            @Schema(description = "5년 변동성 백분위 (0~1 비율)", example = "0.72")
            @JsonProperty("vol_percentile_5y") double volPercentile5y,
            @Schema(allowableValues = {"calm", "normal", "elevated", "stress"}, example = "elevated")
            String regime) {
    }

    /**
     * 환율 1퍼센트 변동 시 자산 영향.
     *
     * @param per1pctKrw 1퍼센트 변동 시 원화 영향
     * @param assetKrw   해당 통화 노출액 (원화 환산)
     */
    @Schema(description = "환율 1퍼센트 변동 시 내 자산 영향")
    public record UserImpact(
            @Schema(example = "157900") @JsonProperty("per_1pct_krw") long per1pctKrw,
            @Schema(example = "15790000") long assetKrw) {
    }

    /**
     * 표시 라벨.
     *
     * @param band      음영 라벨
     * @param modelPath 중심 경로 라벨
     */
    @Schema(description = "표시 라벨. 음영을 '변동성'이라 부르지 않는다.")
    public record Labels(
            @Schema(example = "예측 범위 / 불확실성 구간") String band,
            @Schema(example = "모델의 참고 중심 경로") String modelPath) {
    }

    /**
     * 모델 정보.
     *
     * @param intervalLevels 제공하는 구간 수준
     * @param assumptions    가정
     * @param limitations    한계
     */
    @Schema(description = "모델 정보 — 구간 수준·가정·한계")
    public record ModelInfo(List<Double> intervalLevels, String assumptions, String limitations) {
    }
}
