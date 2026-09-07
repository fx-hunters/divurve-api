package com.divurve.api.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;
import static org.mockito.Mockito.when;

import com.divurve.api.dto.xray.AttributionResponse;
import com.divurve.api.dto.xray.XrayResponse;
import com.divurve.common.response.ApiResponse;
import com.divurve.domain.xray.XrayService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * {@link XrayController} — 도메인 경계 record → 응답 DTO 변환, data/meta 래핑 (명세 §5.3 · §5.4).
 * 컨트롤러는 계산하지 않는다 — 1퍼센트 민감도 계산도 engine 으로 옮겼다(CLAUDE.md §1).
 */
@ExtendWith(MockitoExtension.class)
class XrayControllerTest {

    @Mock
    private XrayService xrayService;

    private final UUID userId = UUID.randomUUID();

    private XrayController controller() {
        return new XrayController(xrayService);
    }

    @Test
    @DisplayName("명세 §5.3 — 원화 자산·외화 비중·집중도·민감도를 data/meta 로 감싼다")
    void getXray_는_명세_5_3_모양으로_감싼다() {
        Map<String, Long> assets = new LinkedHashMap<>();
        assets.put("USD", 15_790_000L);
        assets.put("JPY", 5_470_000L);
        Map<String, Double> exposure = new LinkedHashMap<>();
        exposure.put("USD", 0.6388);
        // 비중이 빠져 있으면 0.0 으로 채운다.
        Map<String, Long> sensitivity = new LinkedHashMap<>();
        sensitivity.put("USD", 157_900L);
        sensitivity.put("JPY", 54_700L);

        when(xrayService.getPortfolio(userId)).thenReturn(new XrayService.PortfolioSnapshot(
                68_400_000L, 43_680_000L, 24_720_000L, 0.3614, assets, exposure,
                new XrayService.ConcentrationView(
                        "USD", 0.6388, 0.60, "risk_profile.balanced", "above_threshold", 0.0388),
                new XrayService.SensitivityView(247_200L, sensitivity),
                null,
                true));

        ApiResponse<XrayResponse> response = controller().getXray(userId);

        assertThat(response.meta()).isNotNull();
        XrayResponse data = response.data();
        // 체험용 데이터 배지의 근거 — meta.is_demo 로는 가입 계정의 샘플을 판정할 수 없다(이슈 #112).
        assertThat(data.isSampleData()).isTrue();
        assertThat(data.totalAssetKrw()).isEqualTo(68_400_000L);
        assertThat(data.krwAssetKrw()).isEqualTo(43_680_000L);
        assertThat(data.fxAssetKrw()).isEqualTo(24_720_000L);
        assertThat(data.fxRatio()).isEqualTo(0.3614);
        assertThat(data.exposure()).containsExactly(
                new XrayResponse.Exposure("USD", 15_790_000L, 0.6388),
                new XrayResponse.Exposure("JPY", 5_470_000L, 0.0));
        assertThat(data.concentration()).isEqualTo(new XrayResponse.Concentration(
                "USD", 0.6388, 0.60, "risk_profile.balanced", "above_threshold"));
        assertThat(data.sensitivity1pct().totalKrw()).isEqualTo(247_200L);
        assertThat(data.sensitivity1pct().byCurrency())
                .containsExactly(entry("USD", 157_900L), entry("JPY", 54_700L));
        assertThat(data.dayChangeKrw()).isNull();
    }

    @Test
    @DisplayName("성향 미측정이면 threshold·threshold_source 가 null 로 내려간다")
    void 성향_미측정이면_null_이_내려간다() {
        when(xrayService.getPortfolio(userId)).thenReturn(new XrayService.PortfolioSnapshot(
                0L, 0L, 0L, 0.0, Map.of(), Map.of(),
                new XrayService.ConcentrationView(null, null, null, null, "unknown", null),
                new XrayService.SensitivityView(0L, Map.of()),
                null,
                false));

        XrayResponse data = controller().getXray(userId).data();

        assertThat(data.exposure()).isEmpty();
        assertThat(data.concentration().topCurrencyCode()).isNull();
        assertThat(data.concentration().share()).isNull();
        assertThat(data.concentration().threshold()).isNull();
        assertThat(data.concentration().thresholdSource()).isNull();
        assertThat(data.concentration().status()).isEqualTo("unknown");
    }

    @Test
    @DisplayName("명세 §5.4 — 네 항이 label 과 함께 asset·fx·interaction·cost 순서로 고정된다")
    void getAttribution_는_네항을_고정_순서로_낸다() {
        when(xrayService.getAttribution(userId, "USD")).thenReturn(
                new XrayService.AttributionAnalysis(
                        "USD", 15_050_000L, 15_790_000L, 0.0492,
                        List.of(
                                new XrayService.AttributionComponent("asset", 1_241_307L, 0.0825),
                                new XrayService.AttributionComponent("fx", -421_400L, -0.0280),
                                new XrayService.AttributionComponent("interaction", -34_757L, -0.0023),
                                new XrayService.AttributionComponent("cost", -45_150L, -0.0030)),
                        List.of(new XrayService.HoldingAttribution(
                                "VOO", 11_240_000L, 0.091, -0.030, 0.0583))));

        ApiResponse<AttributionResponse> response = controller().getAttribution(userId, "USD");
        AttributionResponse data = response.data();

        assertThat(response.meta()).isNotNull();
        assertThat(data.currencyCode()).isEqualTo("USD");
        assertThat(data.costBasisKrw()).isEqualTo(15_050_000L);
        assertThat(data.currentKrw()).isEqualTo(15_790_000L);
        assertThat(data.totalReturn()).isEqualTo(0.0492);

        assertThat(data.components()).extracting(AttributionResponse.Component::key)
                .containsExactly("asset", "fx", "interaction", "cost");
        assertThat(data.components()).extracting(AttributionResponse.Component::label)
                .containsExactly("자산 가격 효과", "환율 효과", "상호작용", "비용");
        assertThat(data.components()).extracting(AttributionResponse.Component::krw)
                .containsExactly(1_241_307L, -421_400L, -34_757L, -45_150L);
        // 검산: 네 항의 합 = current − cost_basis = 740,000
        assertThat(data.components().stream()
                .mapToLong(AttributionResponse.Component::krw).sum())
                .isEqualTo(740_000L);
        // contribution_pp 는 퍼센트(×100)가 아니라 0~1 비율이다 (명세 §1.4)
        assertThat(data.components().get(0).contributionPp()).isEqualTo(0.0825);

        assertThat(data.byHolding()).containsExactly(new AttributionResponse.ByHolding(
                "VOO", 11_240_000L, 0.091, -0.030, 0.0583));
    }

    @Test
    @DisplayName("통화 필터를 생략하면 서비스에 null 이 전달되고 currency_code 도 null 이다")
    void 통화_필터_생략() {
        when(xrayService.getAttribution(userId, null)).thenReturn(
                new XrayService.AttributionAnalysis(
                        null, 1_000_000L, 1_000_000L, 0.0,
                        List.of(
                                new XrayService.AttributionComponent("asset", 0L, 0.0),
                                new XrayService.AttributionComponent("fx", 0L, 0.0),
                                new XrayService.AttributionComponent("interaction", 0L, 0.0),
                                new XrayService.AttributionComponent("cost", 0L, 0.0)),
                        List.of()));

        AttributionResponse data = controller().getAttribution(userId, null).data();

        assertThat(data.currencyCode()).isNull();
        assertThat(data.byHolding()).isEmpty();
        assertThat(data.components()).hasSize(4);
    }
}
