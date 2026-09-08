package com.divurve.api.controller;

import com.divurve.api.config.auth.CurrentUser;
import com.divurve.api.dto.xray.AttributionResponse;
import com.divurve.api.dto.xray.XrayResponse;
import com.divurve.common.architecture.WebAdapter;
import com.divurve.common.response.ApiResponse;
import com.divurve.common.response.Meta;
import com.divurve.domain.market.MarketRegimeService;
import com.divurve.domain.xray.XrayService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger log = LoggerFactory.getLogger(XrayController.class);

    /** 4분해 항목의 화면 표시용 이름 (명세 §5.4 {@code components[].label}). */
    private static final Map<String, String> COMPONENT_LABELS = Map.of(
            "asset", "자산 가격 효과",
            "fx", "환율 효과",
            "interaction", "상호작용",
            "cost", "비용");

    private final XrayService xrayService;
    private final MarketRegimeService marketRegimeService;

    public XrayController(XrayService xrayService, MarketRegimeService marketRegimeService) {
        this.xrayService = Objects.requireNonNull(xrayService, "xrayService is null");
        this.marketRegimeService =
                Objects.requireNonNull(marketRegimeService, "marketRegimeService is null");
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

        Meta meta = Meta.mock(Instant.now()).withRegime(resolveRegimeCode());

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
                snapshot.sampleData()), meta);
    }

    /**
     * 시장 국면 코드 (명세 §1.2 "시장 수치를 포함하는 응답에 동반", §5.3 예시의 {@code meta.regime}).
     *
     * <p>{@link MarketRegimeService#getRegime()} 는 5년치 환율 히스토리를 훑는 무거운 호출이다.
     * 통화쌍별 조회 실패는 그 서비스 내부에서 이미 흡수하지만(해당 통화쌍만 국면 판정에서 빠짐),
     * 예기치 못한 예외까지 이 엔드포인트의 본체인 자산 분해를 함께 죽이게 두지 않는다 —
     * {@code regime} 은 부가 정보이므로 실패하면 조용히 생략한다. 전역 {@code non_null} 설정이
     * {@code null} 이 되는 순간 meta 에서 키 자체를 뺀다.
     */
    private String resolveRegimeCode() {
        try {
            return marketRegimeService.getRegime().regime();
        } catch (RuntimeException e) {
            log.warn("시장 국면 조회에 실패해 /xray 응답에서 regime 을 생략합니다.", e);
            return null;
        }
    }

    /**
     * {@code meta.regime} 은 여기 붙이지 않는다 — 명세 §5.4 예시가 {@code meta} 에 {@code sources} 까지만
     * 싣고 {@code regime} 은 생략한다. 손익 4분해는 보유 종목의 확정된 수익률이지 시장 전반의 국면
     * 판정과 같은 값이 아니므로, §1.2 "시장 수치를 포함하는 응답"에 §5.3(현재 노출·집중도)만큼
     * 직접 해당한다고 보지 않는다.
     */
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
