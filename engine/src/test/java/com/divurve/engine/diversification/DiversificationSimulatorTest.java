package com.divurve.engine.diversification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("DiversificationSimulator")
class DiversificationSimulatorTest {

    private DiversificationSimulator simulator;

    @BeforeEach
    void setup() {
        simulator = new DiversificationSimulator();
    }

    @Test
    @DisplayName("시뮬레이션: 단일 통화 비중 증가 (범위 초과 예외)")
    void testSimulate_IncreaseSingleCurrency() {
        Map<String, Double> shares = new HashMap<>();
        shares.put("USD", 1.0);

        Map<String, Double> volatility = new HashMap<>();
        volatility.put("USD", 0.1);

        Map<String, Double> correlation = new HashMap<>();

        // USD가 1.0에서 1.1로 증가 시도 -> 범위 초과 예외
        assertThrows(IllegalArgumentException.class, () ->
                simulator.simulate(
                        shares,
                        volatility,
                        correlation,
                        "USD",
                        0.1
                ));
    }

    @Test
    @DisplayName("시뮬레이션: 복수 통화, 한 통화 증가")
    void testSimulate_IncreaseOneCurrency() {
        Map<String, Double> shares = new HashMap<>();
        shares.put("USD", 0.5);
        shares.put("EUR", 0.5);

        Map<String, Double> volatility = new HashMap<>();
        volatility.put("USD", 0.12);
        volatility.put("EUR", 0.14);

        Map<String, Double> correlation = new HashMap<>();
        correlation.put("USD_EUR", 0.5);

        DiversificationSimulator.SimulationResult result = simulator.simulate(
                shares,
                volatility,
                correlation,
                "USD",
                0.1  // USD 비중 50% -> 60%
        );

        assertNotNull(result);
        assertTrue(result.adjustedShare().containsKey("USD"));
        assertEquals(0.6, result.adjustedShare().get("USD"), 0.0001);
        // EUR은 50% * (1 - 0.1) / (1 - 0.5) 비율로 조정
        assertTrue(result.adjustedShare().get("EUR") < 0.5, "EUR 비중이 감소해야 함");
    }

    @Test
    @DisplayName("시뮬레이션: 변동성 변화")
    void testSimulate_VolatilityChange() {
        Map<String, Double> shares = new HashMap<>();
        shares.put("USD", 0.6);
        shares.put("EUR", 0.4);

        Map<String, Double> volatility = new HashMap<>();
        volatility.put("USD", 0.12);
        volatility.put("EUR", 0.14);

        Map<String, Double> correlation = new HashMap<>();
        correlation.put("USD_EUR", 0.7);

        DiversificationSimulator.SimulationResult result = simulator.simulate(
                shares,
                volatility,
                correlation,
                "USD",
                -0.1  // USD 비중 60% -> 50%로 감소
        );

        assertNotNull(result);
        assertEquals(0.5, result.adjustedShare().get("USD"), 0.0001);
        // EUR은 40% * (1 + 0.1) / (1 - 0.6) 비율로 조정
        assertEquals(0.5, result.adjustedShare().get("EUR"), 0.0001);

        // 변동성은 대체로 감소해야 함 (더 균형 잡혀 있음)
        assertTrue(result.portfolioVolAfter() > 0, "포트폴리오 변동성은 양수");
    }

    @Test
    @DisplayName("시뮬레이션: 비중 범위 초과 예외")
    void testSimulate_OutOfBounds() {
        Map<String, Double> shares = new HashMap<>();
        shares.put("USD", 0.8);
        shares.put("EUR", 0.2);

        Map<String, Double> volatility = new HashMap<>();
        volatility.put("USD", 0.12);
        volatility.put("EUR", 0.14);

        Map<String, Double> correlation = new HashMap<>();

        // USD를 80% + 50% = 130%로 증가 시도
        assertThrows(IllegalArgumentException.class, () ->
                simulator.simulate(shares, volatility, correlation, "USD", 0.5));
    }

    @Test
    @DisplayName("시뮬레이션: 음수 비중 범위 초과 예외")
    void testSimulate_NegativeShare() {
        Map<String, Double> shares = new HashMap<>();
        shares.put("USD", 0.3);
        shares.put("EUR", 0.7);

        Map<String, Double> volatility = new HashMap<>();
        volatility.put("USD", 0.12);
        volatility.put("EUR", 0.14);

        Map<String, Double> correlation = new HashMap<>();

        // USD를 30% - 50% = -20%로 감소 시도
        assertThrows(IllegalArgumentException.class, () ->
                simulator.simulate(shares, volatility, correlation, "USD", -0.5));
    }

    @Test
    @DisplayName("시뮬레이션: 존재하지 않는 통화 예외")
    void testSimulate_NonexistentCurrency() {
        Map<String, Double> shares = new HashMap<>();
        shares.put("USD", 1.0);

        Map<String, Double> volatility = new HashMap<>();
        volatility.put("USD", 0.12);

        Map<String, Double> correlation = new HashMap<>();

        assertThrows(IllegalArgumentException.class, () ->
                simulator.simulate(shares, volatility, correlation, "GBP", 0.1));
    }

    @Test
    @DisplayName("시뮬레이션: null 비중 맵 예외")
    void testSimulate_NullShareMap() {
        Map<String, Double> volatility = new HashMap<>();
        volatility.put("USD", 0.12);

        Map<String, Double> correlation = new HashMap<>();

        assertThrows(NullPointerException.class, () ->
                simulator.simulate(null, volatility, correlation, "USD", 0.1));
    }

    @Test
    @DisplayName("시뮬레이션: null 변동성 맵 예외")
    void testSimulate_NullVolatilityMap() {
        Map<String, Double> shares = new HashMap<>();
        shares.put("USD", 0.5);
        shares.put("EUR", 0.5);

        Map<String, Double> correlation = new HashMap<>();

        assertThrows(NullPointerException.class, () ->
                simulator.simulate(shares, null, correlation, "USD", 0.1));
    }

    @Test
    @DisplayName("시뮬레이션: null 상관계수 맵 예외")
    void testSimulate_NullCorrelationMap() {
        Map<String, Double> shares = new HashMap<>();
        shares.put("USD", 0.5);
        shares.put("EUR", 0.5);

        Map<String, Double> volatility = new HashMap<>();
        volatility.put("USD", 0.12);
        volatility.put("EUR", 0.14);

        assertThrows(NullPointerException.class, () ->
                simulator.simulate(shares, volatility, null, "USD", 0.1));
    }

    @Test
    @DisplayName("시뮬레이션: null 통화 예외")
    void testSimulate_NullCurrency() {
        Map<String, Double> shares = new HashMap<>();
        shares.put("USD", 0.5);

        Map<String, Double> volatility = new HashMap<>();
        volatility.put("USD", 0.12);

        Map<String, Double> correlation = new HashMap<>();

        assertThrows(NullPointerException.class, () ->
                simulator.simulate(shares, volatility, correlation, null, 0.1));
    }

    @Test
    @DisplayName("시뮬레이션: 빈 포트폴리오에서의 변동성은 0")
    void testSimulate_EmptyPortfolioVolatility() {
        Map<String, Double> shares = new HashMap<>();
        shares.put("USD", 1.0);

        Map<String, Double> volatility = new HashMap<>();

        Map<String, Double> correlation = new HashMap<>();

        DiversificationSimulator.SimulationResult result = simulator.simulate(
                shares, volatility, correlation, "USD", -0.5
        );

        assertNotNull(result);
        assertEquals(0.0, result.portfolioVolBefore(), 0.0001);
    }

    @Test
    @DisplayName("시뮬레이션: 단일 통화 100% → 비중 감소 시 나머지 비중 0인 경우")
    void testSimulate_SingleCurrencyDecreaseFromFull() {
        Map<String, Double> shares = new LinkedHashMap<>();
        shares.put("USD", 1.0);
        shares.put("EUR", 0.0);

        Map<String, Double> volatility = new HashMap<>();
        volatility.put("USD", 0.12);
        volatility.put("EUR", 0.14);

        Map<String, Double> correlation = new HashMap<>();
        correlation.put("USD_EUR", 0.5);

        DiversificationSimulator.SimulationResult result = simulator.simulate(
                shares, volatility, correlation, "USD", -0.3
        );

        assertNotNull(result);
        assertEquals(0.7, result.adjustedShare().get("USD"), 0.0001);
    }

    @Test
    @DisplayName("시뮬레이션: 대칭 상관계수 조회")
    void testSimulate_SymmetricCorrelation() {
        Map<String, Double> shares = new HashMap<>();
        shares.put("USD", 0.5);
        shares.put("EUR", 0.5);

        Map<String, Double> volatility = new HashMap<>();
        volatility.put("USD", 0.12);
        volatility.put("EUR", 0.14);

        // EUR_USD 순서로 저장 (USD_EUR이 아님)
        Map<String, Double> correlation = new HashMap<>();
        correlation.put("EUR_USD", 0.5);

        DiversificationSimulator.SimulationResult result = simulator.simulate(
                shares,
                volatility,
                correlation,
                "USD",
                0.05
        );

        assertNotNull(result);
        assertTrue(result.portfolioVolBefore() > 0);
        assertTrue(result.portfolioVolAfter() > 0);
    }

    // --- 비중 가정 재배분 (FR-FT-03, 명세 §5.6 POST /fit/preview) ---

    @Test
    @DisplayName("명세 §5.6 fixture — JPY 비중 +10%p 가정 시 USD 13,762,318 / JPY 7,942,000 / EUR 3,015,682")
    void fixture_JPY_비중_10퍼센트포인트_상향() {
        Map<String, Long> after = simulator.redistributeAmounts(fixtureExposure(), "JPY", 0.10);

        assertEquals(13_762_318L, after.get("USD"));
        assertEquals(7_942_000L, after.get("JPY"));
        assertEquals(3_015_682L, after.get("EUR"));
    }

    @Test
    @DisplayName("재배분은 외화자산 총액을 정확히 보존한다 (민감도 합계가 변하면 안 된다)")
    void 재배분은_총액을_보존한다() {
        Map<String, Long> before = fixtureExposure();
        long total = before.values().stream().mapToLong(Long::longValue).sum();

        for (double delta : new double[] {0.10, -0.10, 0.0, 0.3333}) {
            Map<String, Long> after = simulator.redistributeAmounts(before, "JPY", delta);
            assertEquals(total, after.values().stream().mapToLong(Long::longValue).sum());
        }
    }

    @Test
    @DisplayName("재배분: 반올림 잔차는 금액이 가장 큰 통화가 흡수한다")
    void 반올림_잔차는_최대_통화가_흡수한다() {
        // 3/2/2 (합 7) 에서 JPY +10%p 는 개별 반올림 합이 8 이 되어 잔차 −1 이 생긴다.
        Map<String, Long> before = new LinkedHashMap<>();
        before.put("USD", 3L);
        before.put("EUR", 2L);
        before.put("JPY", 2L);

        Map<String, Long> after = simulator.redistributeAmounts(before, "JPY", 0.10);

        assertEquals(7L, after.values().stream().mapToLong(Long::longValue).sum());
        // 잔차는 가장 큰 통화(USD 3)가 흡수한다.
        assertEquals(2L, after.get("USD"));
        assertEquals(2L, after.get("EUR"));
        assertEquals(3L, after.get("JPY"));
    }

    @Test
    @DisplayName("재배분: 잔차가 없으면 그대로 둔다")
    void 잔차가_없으면_그대로_둔다() {
        Map<String, Long> before = new LinkedHashMap<>();
        before.put("USD", 1L);
        before.put("EUR", 1L);
        before.put("JPY", 1L);

        Map<String, Long> after = simulator.redistributeAmounts(before, "JPY", 0.10);

        assertEquals(3L, after.values().stream().mapToLong(Long::longValue).sum());
    }

    @Test
    @DisplayName("재배분: 대상 통화만 있으면 비중 변화량은 0 이어야 한다")
    void 단일_통화는_비중을_바꿀_수_없다() {
        Map<String, Long> single = new LinkedHashMap<>();
        single.put("USD", 1_000_000L);

        assertEquals(1_000_000L, simulator.redistributeAmounts(single, "USD", 0.0).get("USD"));
        DiversificationAdjustmentException exception = assertThrows(
                DiversificationAdjustmentException.class,
                () -> simulator.redistributeAmounts(single, "USD", 0.10));
        assertEquals(DiversificationAdjustmentException.Reason.SHARE_OUT_OF_RANGE, exception.reason());
    }

    @Test
    @DisplayName("재배분: 외화자산이 0 이면 예외 — 원인은 EMPTY_PORTFOLIO 다(이슈 #89)")
    void 외화자산_0이면_예외() {
        Map<String, Long> empty = new LinkedHashMap<>();
        empty.put("USD", 0L);

        DiversificationAdjustmentException exception = assertThrows(
                DiversificationAdjustmentException.class,
                () -> simulator.redistributeAmounts(empty, "USD", 0.10));
        assertEquals(DiversificationAdjustmentException.Reason.EMPTY_PORTFOLIO, exception.reason());
    }

    @Test
    @DisplayName("재배분: 포트폴리오에 없는 통화면 예외 — 원인은 UNKNOWN_CURRENCY 다(이슈 #89)")
    void 없는_통화면_예외() {
        DiversificationAdjustmentException exception = assertThrows(
                DiversificationAdjustmentException.class,
                () -> simulator.redistributeAmounts(fixtureExposure(), "GBP", 0.10));
        assertEquals(DiversificationAdjustmentException.Reason.UNKNOWN_CURRENCY, exception.reason());
    }

    @Test
    @DisplayName("재배분: 조정 후 비중이 0~1 을 벗어나면 예외 — 원인은 SHARE_OUT_OF_RANGE 다(이슈 #89)")
    void 조정_후_비중_범위_예외() {
        DiversificationAdjustmentException tooHigh = assertThrows(
                DiversificationAdjustmentException.class,
                () -> simulator.redistributeAmounts(fixtureExposure(), "JPY", 0.90));
        assertEquals(DiversificationAdjustmentException.Reason.SHARE_OUT_OF_RANGE, tooHigh.reason());

        DiversificationAdjustmentException tooLow = assertThrows(
                DiversificationAdjustmentException.class,
                () -> simulator.redistributeAmounts(fixtureExposure(), "JPY", -0.90));
        assertEquals(DiversificationAdjustmentException.Reason.SHARE_OUT_OF_RANGE, tooLow.reason());
    }

    @Test
    @DisplayName("재배분: null 입력은 예외")
    void 재배분_null_입력_예외() {
        assertThrows(NullPointerException.class,
                () -> simulator.redistributeAmounts(null, "USD", 0.10));
        assertThrows(NullPointerException.class,
                () -> simulator.redistributeAmounts(fixtureExposure(), null, 0.10));
    }

    /** 명세 §4 Mock fixture 의 통화별 노출 (합 24,720,000원). */
    private static Map<String, Long> fixtureExposure() {
        Map<String, Long> exposure = new LinkedHashMap<>();
        exposure.put("USD", 15_790_000L);
        exposure.put("JPY", 5_470_000L);
        exposure.put("EUR", 3_460_000L);
        return exposure;
    }
}
