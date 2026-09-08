package com.divurve.api.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.divurve.api.dto.forecast.EventsResponse;
import com.divurve.api.dto.forecast.FactorsResponse;
import com.divurve.api.dto.forecast.ForecastResponse;
import com.divurve.api.dto.forecast.ModelPerformanceResponse;
import com.divurve.common.response.ApiResponse;
import com.divurve.domain.forecast.ForecastService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link ForecastController} 검증.
 *
 * <p>이전 컨트롤러는 응답을 직접 조립하면서 {@code TODO} 와 상수 0 을 채워 넣었다. 이제 컨트롤러는
 * 서비스 결과를 DTO 로 옮기고 {@code meta} 를 붙이는 일만 한다 — 그 일만 하는지를 확인한다.
 */
@DisplayName("ForecastController")
class ForecastControllerTest {

    private static final UUID USER_ID = UUID.randomUUID();
    private static final LocalDate BASE_DATE = LocalDate.of(2026, 9, 1);

    private ForecastService service;
    private ForecastController controller;

    @BeforeEach
    void setUp() {
        service = mock(ForecastService.class);
        controller = new ForecastController(service);
    }

    @Test
    @DisplayName("meta 에 model_version 과 regime 을 함께 싣는다")
    void forecastMeta() {
        when(service.getForecast(USER_ID, "USDKRW", 30)).thenReturn(forecastView());

        ApiResponse<ForecastResponse> response = controller.getForecast(USER_ID, "USDKRW", 30);

        assertEquals(ForecastService.MODEL_VERSION, response.meta().modelVersion());
        assertEquals("elevated", response.meta().regime());
        assertEquals("USDKRW", response.data().pairCode());
        assertEquals(BASE_DATE, response.data().baseDate());
        assertEquals(1, response.data().band().size());
        assertEquals(BASE_DATE.plusDays(30), response.data().band().get(0).d());
        assertEquals("elevated", response.data().volatility().regime());
        assertEquals(157_900L, response.data().userImpact().per1pctKrw());
        assertEquals("예측 범위 / 불확실성 구간", response.data().labels().band());
        assertEquals(1, response.data().modelPath().size());
        assertEquals(1, response.data().history().size());
    }

    @Test
    @DisplayName("성적표 응답에는 model_version 만 싣는다 — 시장 국면 수치를 동반하지 않는다")
    void modelPerformanceMeta() {
        when(service.getModelPerformance("USDKRW", 90)).thenReturn(performanceView());

        ApiResponse<ModelPerformanceResponse> response =
                controller.getModelPerformance("USDKRW", 90);

        assertEquals(ForecastService.MODEL_VERSION, response.meta().modelVersion());
        assertNull(response.meta().regime());
        assertEquals(0.0, response.data().rwImprovement());
        assertEquals(24, response.data().validation().folds());
        assertTrue(response.data().validation().leakageGuard());
        assertEquals(0.81, response.data().model().coverage80());
        assertEquals(0.058, response.data().model().avgWidth());
        assertEquals(Instant.parse("2026-08-31T00:00:00Z"), response.data().evaluatedAt());
    }

    /**
     * 응답 키에서 {@code hit_rate} 가 완전히 사라졌는지 직렬화 수준에서 확인한다 (이슈 #90).
     *
     * <p>운영과 같은 SNAKE_CASE 전략으로 실제 JSON 을 만들어 필드명을 못 박는다
     * ({@code NotificationControllerTest} 의 선례를 따른다) — DTO 레코드에서 필드를 지워도
     * 직렬화 결과에 우연히 남는 경로(예: 커스텀 getter)가 없다는 것까지 확인한다.
     */
    @Test
    @DisplayName("성적표 응답 JSON 에는 hit_rate 키가 없다")
    void modelPerformanceResponseHasNoHitRateKey() {
        when(service.getModelPerformance("USDKRW", 90)).thenReturn(performanceView());
        ObjectMapper objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);

        ApiResponse<ModelPerformanceResponse> response =
                controller.getModelPerformance("USDKRW", 90);
        JsonNode json = objectMapper.valueToTree(response.data());

        JsonNode model = json.get("model");
        JsonNode randomWalk = json.get("random_walk");
        assertThat(model.fieldNames()).toIterable()
                .containsExactlyInAnyOrder("mae", "coverage_80", "avg_width");
        assertThat(randomWalk.fieldNames()).toIterable().containsExactlyInAnyOrder("mae");
        assertThat(json.toString()).doesNotContain("hit_rate");
    }

    @Test
    @DisplayName("전망 동인은 그대로 옮긴다 (L2)")
    void factors() {
        when(service.getFactors("USDKRW")).thenReturn(new ForecastService.FactorsView("USDKRW"));

        ApiResponse<FactorsResponse> response = controller.getFactors("USDKRW");

        assertEquals("USDKRW", response.data().pairCode());
        assertTrue(response.data().factors().isEmpty());
    }

    @Test
    @DisplayName("경제 일정은 그대로 옮긴다")
    void events() {
        when(service.getEvents()).thenReturn(List.of(new ForecastService.EconomicEventView(
                LocalDate.of(2026, 9, 17), "FOMC", "USD", "high")));

        ApiResponse<EventsResponse> response = controller.getEvents();

        assertEquals(1, response.data().events().size());
        assertEquals("FOMC", response.data().events().get(0).title());
    }

    private static ForecastService.ForecastView forecastView() {
        return new ForecastService.ForecastView(
                "USDKRW",
                30,
                BASE_DATE,
                1382.40,
                1382.40,
                List.of(new ForecastService.HistoryPoint(LocalDate.of(2026, 8, 5), 1361.20)),
                List.of(new ForecastService.BandPoint(
                        BASE_DATE.plusDays(30), 1371.0, 1395.2, 1345.61, 1420.19)),
                List.of(new ForecastService.ModelPathPoint(BASE_DATE.plusDays(30), 1382.40)),
                new ForecastService.IntervalView(1345.61, 1420.19, 0.054),
                new ForecastService.VolatilityView(0.061, 0.72, "elevated"),
                new ForecastService.UserImpactView(157_900L, 15_790_000L),
                new ForecastService.LabelsView("예측 범위 / 불확실성 구간", "모델의 참고 중심 경로"),
                new ForecastService.ModelInfoView(List.of(0.50, 0.80), "가정", "한계"),
                "불확실성 안내",
                ForecastService.DISCLAIMER);
    }

    private static ForecastService.ModelPerformanceView performanceView() {
        return new ForecastService.ModelPerformanceView(
                "USDKRW",
                90,
                new ForecastService.ModelMetricsView(0.019, 0.81, 0.058),
                new ForecastService.RandomWalkMetricsView(0.019),
                0.0,
                new ForecastService.ValidationView("rolling_walk_forward", 24, true),
                ForecastService.PERFORMANCE_NOTE,
                Instant.parse("2026-08-31T00:00:00Z"));
    }
}
