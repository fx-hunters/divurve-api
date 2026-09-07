package com.divurve.api.controller;

import com.divurve.api.config.auth.CurrentUser;
import com.divurve.api.dto.xray.AttributionResponse;
import com.divurve.api.dto.xray.XrayResponse;
import com.divurve.common.architecture.WebAdapter;
import com.divurve.common.response.ApiResponse;
import com.divurve.domain.xray.XrayService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * X-Ray 엔드포인트 (API 명세 v2 §5.3 · §5.4).
 *
 * <p>v1 의 {@code POST /xray/stress} 는 여기서 뺐다 — 명세 v2 는 스트레스 테스트를
 * {@code GET /stress/scenarios} · {@code POST /stress/runs} 로 옮겼다(§3, FR-ST).
 * {@code ?mode=} 파라미터도 §0.1 에서 삭제됐다: 분해 방식은 고정이며 사용자 설정으로 바뀌지 않는다.
 */
@WebAdapter
@RestController
@RequestMapping("/api/v1/xray")
@Tag(name = "X-Ray", description = "외화 비중·통화 노출·집중도·손익 4분해")
public class XrayController {

    /** 4분해 항목의 화면 표시용 이름 (명세 §5.4 {@code components[].label}). */
    private static final Map<String, String> COMPONENT_LABELS = Map.of(
            "asset", "자산 가격 효과",
            "fx", "환율 효과",
            "interaction", "상호작용",
            "cost", "비용");

    private final XrayService xrayService;

    public XrayController(XrayService xrayService) {
        this.xrayService = Objects.requireNonNull(xrayService, "xrayService is null");
    }

    @Operation(summary = "외화 비중·통화 노출·집중도·민감도",
            description = "총자산은 원화 자산(`/krw-assets`) + 외화 자산이다. 집중도 기준선은 위험성향 "
                    + "등급에서 오며, 성향 미측정이면 `threshold`가 null 이고 `status`는 `unknown` 이다.")
    @ApiResponses(value = {
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200", description = "조회 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "401", description = "인증 필요"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404", description = "사용자를 찾을 수 없음")})
    @GetMapping
    public ApiResponse<XrayResponse> getXray(@CurrentUser UUID userId) {
        XrayService.PortfolioSnapshot snapshot = xrayService.getPortfolio(userId);
        XrayService.ConcentrationView concentration = snapshot.concentration();

        List<XrayResponse.Exposure> exposure = snapshot.currencyToAssetKrw().entrySet().stream()
                .map(entry -> new XrayResponse.Exposure(
                        entry.getKey(),
                        entry.getValue(),
                        snapshot.exposure().getOrDefault(entry.getKey(), 0.0)))
                .toList();

        return ApiResponse.of(new XrayResponse(
                snapshot.totalAssetKrw(),
                snapshot.krwAssetKrw(),
                snapshot.fxAssetKrw(),
                snapshot.fxRatio(),
                exposure,
                new XrayResponse.Concentration(
                        concentration.topCurrencyCode(),
                        concentration.share(),
                        concentration.threshold(),
                        concentration.thresholdSource(),
                        concentration.status()),
                new XrayResponse.Sensitivity(
                        snapshot.sensitivity1pct().totalKrw(),
                        snapshot.sensitivity1pct().byCurrency()),
                snapshot.dayChangeKrw(),
                snapshot.sampleData()));
    }

    @Operation(summary = "손익 4분해",
            description = "요구사항 §4.6 `R_KRW = (1+R_asset)(1+R_fx) − 1` 에 거래비용을 더한 "
                    + "asset·fx·interaction·cost 네 항 고정 분해. 네 항의 합은 "
                    + "`current_krw − cost_basis_krw` 와 정확히 일치한다.")
    @ApiResponses(value = {
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200", description = "분해 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "401", description = "인증 필요"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404", description = "사용자 또는 해당 통화의 보유 종목 없음")})
    @GetMapping("/attribution")
    public ApiResponse<AttributionResponse> getAttribution(
            @CurrentUser UUID userId,
            @Parameter(description = "통화 필터. 생략하면 전체 외화", example = "USD")
            @RequestParam(name = "currency_code", required = false) String currencyCode) {

        XrayService.AttributionAnalysis analysis = xrayService.getAttribution(userId, currencyCode);

        List<AttributionResponse.Component> components = analysis.components().stream()
                .map(XrayController::toComponent)
                .toList();

        List<AttributionResponse.ByHolding> byHolding = analysis.byHolding().stream()
                .map(holding -> new AttributionResponse.ByHolding(
                        holding.ticker(),
                        holding.krw(),
                        holding.localReturn(),
                        holding.fxReturn(),
                        holding.krwReturn()))
                .toList();

        return ApiResponse.of(new AttributionResponse(
                analysis.currencyCode(),
                analysis.costBasisKrw(),
                analysis.currentKrw(),
                analysis.totalReturn(),
                components,
                byHolding));
    }

    /** 순서는 도메인이 명세 §5.4 예시(asset · fx · interaction · cost) 그대로 고정해 넘겨준다. */
    private static AttributionResponse.Component toComponent(
            XrayService.AttributionComponent component) {
        return new AttributionResponse.Component(
                component.key(),
                COMPONENT_LABELS.get(component.key()),
                component.krw(),
                component.contributionPp());
    }
}
