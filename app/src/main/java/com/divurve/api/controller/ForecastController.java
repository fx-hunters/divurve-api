package com.divurve.api.controller;

import com.divurve.api.config.auth.CurrentUser;
import com.divurve.api.dto.forecast.EventsResponse;
import com.divurve.api.dto.forecast.FactorsResponse;
import com.divurve.api.dto.forecast.ForecastResponse;
import com.divurve.api.dto.forecast.ModelPerformanceResponse;
import com.divurve.common.architecture.WebAdapter;
import com.divurve.common.response.ApiResponse;
import com.divurve.common.response.Meta;
import com.divurve.domain.forecast.ForecastService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 예측 범위 엔드포인트 (명세 v2 §5.7·§5.8, 요구사항 §4.5).
 *
 * <p>이 컨트롤러는 <b>조립하지 않는다</b>. v1 에서는 응답 매핑 메서드 4개가 여기 있었고 그 안에
 * {@code TODO}·상수 0·빈 목록이 섞여 있어 "계산되지 않은 값"이 컨트롤러에서 만들어졌다.
 * 이제 계산은 {@code ForecastService} 와 engine 이 하고, DTO 변환은 각 응답 레코드의
 * {@code from(...)} 팩토리가 한다.
 *
 * <p>🔒 {@code model_path} 와 {@code /forecast/factors} 는 <b>L2(표시 전용)</b> 이다.
 * 어떤 계산의 입력으로도 넘기지 않는다(FR-FC-12).
 */
@WebAdapter
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Forecast", description = "예측 범위·변동성·전망 동인·모델 성적표·경제 일정")
public class ForecastController {

    private final ForecastService forecastService;

    public ForecastController(ForecastService forecastService) {
        this.forecastService = Objects.requireNonNull(forecastService, "forecastService");
    }

    /**
     * 예측 범위·변동성·내 자산 영향.
     *
     * <p>{@code meta} 에 {@code model_version}(예측 모델 계열 응답) 과 {@code regime}(시장 수치 동반) 을 싣는다.
     */
    @Operation(
            summary = "예측 범위·변동성·내 자산 영향",
            description = "드리프트 0 기준선에 실현변동성을 적용한 예측 범위(band)와 변동성 지표, "
                    + "환율 1퍼센트 변동 시 보유 자산 영향을 반환한다. "
                    + "band 는 '예측 범위/불확실성 구간'이며 변동성(volatility)과는 별개 지표다(FR-FC-04·05). "
                    + "model_path 는 L2(표시 전용)이며 어떤 계산의 입력도 아니다(FR-FC-12). "
                    + "방향 확률 필드는 제공하지 않는다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200", description = "예측 범위. 시장이 급변해도 200 이다(FR-SF-01)."),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400",
                    description = "pair_code 표기 오류 또는 horizon_days 가 7·14·30·60·90·180 중 하나가 아님",
                    content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "401", description = "인증 없음", content = @Content)
    })
    @GetMapping("/forecast")
    public ApiResponse<ForecastResponse> getForecast(
            @CurrentUser UUID userId,
            @Parameter(description = "통화쌍 (USDKRW · USDJPY · EURUSD)", example = "USDKRW")
            @RequestParam("pair_code") String pairCode,
            @Parameter(description = "지평 (7·14·30·60·90·180 중 하나). 기본 30", example = "30")
            @RequestParam(name = "horizon_days", defaultValue = "30") int horizonDays) {

        ForecastService.ForecastView view = forecastService.getForecast(userId, pairCode, horizonDays);
        return ApiResponse.of(
                ForecastResponse.from(view),
                Meta.mock(Instant.now())
                        .withRegime(view.volatility().regime())
                        .withModelVersion(ForecastService.MODEL_VERSION));
    }

    /** 전망 동인 (L2 — 표시 전용). */
    @Operation(
            summary = "전망 동인 (L2 — 표시 전용)",
            description = "참고용 동인 목록. 이 값은 어떤 계산의 입력도 되지 않으며 /route/context 에도 "
                    + "포함되지 않는다(FR-FC-12). 출처가 확정되기 전까지 빈 배열이다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "동인 목록"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400", description = "pair_code 표기 오류", content = @Content)
    })
    @GetMapping("/forecast/factors")
    public ApiResponse<FactorsResponse> getFactors(
            @Parameter(description = "통화쌍", example = "USDKRW")
            @RequestParam("pair_code") String pairCode) {

        return ApiResponse.of(FactorsResponse.from(forecastService.getFactors(pairCode)));
    }

    /** 모델 성적표. */
    @Operation(
            summary = "모델 성적표",
            description = "롤링 워크포워드 검증 결과. 각 폴드의 기준값은 그 시점까지의 실측값이라 "
                    + "미래 누출이 없다. coverage_80 은 avg_width 와 함께 노출한다 — 구간을 넓히면 "
                    + "포함률은 쉽게 오르기 때문이다. rw_improvement 가 0 이거나 음수여도 그대로 "
                    + "보여준다(FR-FC-11).")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성적표"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400",
                    description = "pair_code 표기 오류, horizon_days 가 7·14·30·60·90·180 중 하나가 아님, "
                            + "또는 검증할 관측 부족",
                    content = @Content)
    })
    @GetMapping("/forecast/model-performance")
    public ApiResponse<ModelPerformanceResponse> getModelPerformance(
            @Parameter(description = "통화쌍", example = "USDKRW")
            @RequestParam("pair_code") String pairCode,
            @Parameter(description = "지평 (7·14·30·60·90·180 중 하나). 기본 30", example = "30")
            @RequestParam(name = "horizon_days", defaultValue = "30") int horizonDays) {

        return ApiResponse.of(
                ModelPerformanceResponse.from(forecastService.getModelPerformance(pairCode, horizonDays)),
                Meta.mock(Instant.now()).withModelVersion(ForecastService.MODEL_VERSION));
    }

    /** 경제 일정. */
    @Operation(
            summary = "경제 일정",
            description = "향후 90일 주요 정책회의·지표 발표 일정. 사실 정보이며 방향 전망이 아니다.")
    @ApiResponses(@io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200", description = "일정 목록"))
    @GetMapping("/events")
    public ApiResponse<EventsResponse> getEvents() {
        return ApiResponse.of(EventsResponse.from(forecastService.getEvents()));
    }
}
