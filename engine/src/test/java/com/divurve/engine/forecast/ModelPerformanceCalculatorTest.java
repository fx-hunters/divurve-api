package com.divurve.engine.forecast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Constructor;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * {@link ModelPerformanceCalculator} 검증 (명세 v2 §5.8).
 *
 * <p>방향 적중률({@code calculateHitRate})은 이슈 #90 에서 제거했다 — 이 서비스의 기준 모델은
 * 드리프트 0 이라 점예측이 항상 기준값과 같고, 그 결과 방향 적중률이 모든 입력에서 예외 없이 0 이
 * 되는 구조적 결함이 있었다. 관련 테스트도 함께 제거했다.
 */
@DisplayName("ModelPerformanceCalculator")
class ModelPerformanceCalculatorTest {

    @Test
    @DisplayName("유틸리티 클래스라 인스턴스를 만들 이유가 없다")
    void privateConstructor() throws Exception {
        Constructor<ModelPerformanceCalculator> constructor =
                ModelPerformanceCalculator.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        assertNotNull(constructor.newInstance());
    }

    @Nested
    @DisplayName("상대 MAE")
    class MaeRatio {

        @Test
        @DisplayName("기준값 대비 비율로 나온다")
        void ratio() {
            List<Double> forecast = List.of(1010.0, 990.0);
            List<Double> actual = List.of(1000.0, 1000.0);

            // (10/1000 + 10/1000) / 2 = 0.01
            assertEquals(0.01, ModelPerformanceCalculator.calculateMaeRatio(forecast, actual), 1e-12);
        }

        @Test
        @DisplayName("실제값 음수도 절대값 기준으로 나눈다")
        void negativeActual() {
            assertEquals(0.5,
                    ModelPerformanceCalculator.calculateMaeRatio(List.of(-15.0), List.of(-10.0)), 1e-12);
        }

        @Test
        @DisplayName("빈 입력은 0")
        void empty() {
            assertEquals(0.0, ModelPerformanceCalculator.calculateMaeRatio(List.of(), List.of()));
        }

        @Test
        @DisplayName("실제값 0 은 예외")
        void zeroActual() {
            assertThrows(IllegalArgumentException.class,
                    () -> ModelPerformanceCalculator.calculateMaeRatio(List.of(1.0), List.of(0.0)));
        }

        @Test
        @DisplayName("null·크기 불일치")
        void nullsAndMismatch() {
            List<Double> ok = List.of(1.0);
            assertThrows(NullPointerException.class,
                    () -> ModelPerformanceCalculator.calculateMaeRatio(null, ok));
            assertThrows(NullPointerException.class,
                    () -> ModelPerformanceCalculator.calculateMaeRatio(ok, null));
            assertThrows(IllegalArgumentException.class,
                    () -> ModelPerformanceCalculator.calculateMaeRatio(ok, List.of(1.0, 2.0)));
        }
    }

    @Nested
    @DisplayName("구간 포함률")
    class Coverage80 {

        @Test
        @DisplayName("구간 안이면 포함, 경계는 포함")
        void covered() {
            List<Double> lo = List.of(900.0, 900.0, 900.0, 900.0);
            List<Double> hi = List.of(1100.0, 1100.0, 1100.0, 1100.0);
            List<Double> actual = List.of(1000.0, 900.0, 1100.0, 1200.0);

            assertEquals(0.75, ModelPerformanceCalculator.calculateCoverage80(lo, hi, actual));
        }

        @Test
        @DisplayName("하단 미만은 미포함")
        void belowLower() {
            assertEquals(0.0, ModelPerformanceCalculator.calculateCoverage80(
                    List.of(900.0), List.of(1100.0), List.of(800.0)));
        }

        @Test
        @DisplayName("빈 입력은 0")
        void empty() {
            assertEquals(0.0,
                    ModelPerformanceCalculator.calculateCoverage80(List.of(), List.of(), List.of()));
        }

        @Test
        @DisplayName("null·크기 불일치")
        void nullsAndMismatch() {
            List<Double> ok = List.of(1.0);
            List<Double> two = List.of(1.0, 2.0);
            assertThrows(NullPointerException.class,
                    () -> ModelPerformanceCalculator.calculateCoverage80(null, ok, ok));
            assertThrows(NullPointerException.class,
                    () -> ModelPerformanceCalculator.calculateCoverage80(ok, null, ok));
            assertThrows(NullPointerException.class,
                    () -> ModelPerformanceCalculator.calculateCoverage80(ok, ok, null));
            assertThrows(IllegalArgumentException.class,
                    () -> ModelPerformanceCalculator.calculateCoverage80(ok, two, two));
            assertThrows(IllegalArgumentException.class,
                    () -> ModelPerformanceCalculator.calculateCoverage80(two, two, ok));
        }
    }

    @Nested
    @DisplayName("평균 구간 폭")
    class AvgWidthRatio {

        @Test
        @DisplayName("기준값 대비 비율")
        void ratio() {
            List<Double> lo = List.of(950.0);
            List<Double> hi = List.of(1010.0);
            List<Double> base = List.of(1000.0);

            assertEquals(0.06,
                    ModelPerformanceCalculator.calculateAvgWidthRatio(lo, hi, base), 1e-12);
        }

        @Test
        @DisplayName("빈 입력은 0")
        void empty() {
            assertEquals(0.0,
                    ModelPerformanceCalculator.calculateAvgWidthRatio(List.of(), List.of(), List.of()));
        }

        @Test
        @DisplayName("기준값 0 은 예외")
        void zeroBase() {
            assertThrows(IllegalArgumentException.class,
                    () -> ModelPerformanceCalculator.calculateAvgWidthRatio(
                            List.of(1.0), List.of(2.0), List.of(0.0)));
        }

        @Test
        @DisplayName("null·크기 불일치")
        void nullsAndMismatch() {
            List<Double> ok = List.of(1.0);
            List<Double> two = List.of(1.0, 2.0);
            assertThrows(NullPointerException.class,
                    () -> ModelPerformanceCalculator.calculateAvgWidthRatio(null, ok, ok));
            assertThrows(NullPointerException.class,
                    () -> ModelPerformanceCalculator.calculateAvgWidthRatio(ok, null, ok));
            assertThrows(NullPointerException.class,
                    () -> ModelPerformanceCalculator.calculateAvgWidthRatio(ok, ok, null));
            assertThrows(IllegalArgumentException.class,
                    () -> ModelPerformanceCalculator.calculateAvgWidthRatio(ok, two, two));
            assertThrows(IllegalArgumentException.class,
                    () -> ModelPerformanceCalculator.calculateAvgWidthRatio(two, two, ok));
        }
    }

    @Nested
    @DisplayName("랜덤워크 벤치마크와 개선율")
    class RandomWalk {

        @Test
        @DisplayName("직전 실측값을 예측으로 써서 상대 MAE 를 낸다")
        void benchmark() {
            List<Double> base = List.of(1000.0, 1000.0);
            List<Double> actual = List.of(1000.0, 1020.0);

            ModelPerformanceCalculator.RandomWalkMetrics metrics =
                    ModelPerformanceCalculator.calculateRandomWalkBenchmark(base, actual);

            // |1000-1000|/1000 = 0, |1000-1020|/1020 = 0.019607...
            assertEquals(0.0098039, metrics.mae(), 1e-6);
        }

        @Test
        @DisplayName("null 입력은 NPE")
        void nulls() {
            List<Double> ok = List.of(1.0);
            assertThrows(NullPointerException.class,
                    () -> ModelPerformanceCalculator.calculateRandomWalkBenchmark(null, ok));
            assertThrows(NullPointerException.class,
                    () -> ModelPerformanceCalculator.calculateRandomWalkBenchmark(ok, null));
        }

        @Test
        @DisplayName("개선율: 랜덤워크 MAE 가 0 이면 0")
        void zeroRwMae() {
            assertEquals(0.0, ModelPerformanceCalculator.calculateImprovement(0.01, 0.0));
        }

        @Test
        @DisplayName("개선율은 음수여도 그대로 낸다")
        void negativeImprovement() {
            assertEquals(0.0206, ModelPerformanceCalculator.calculateImprovement(0.0190, 0.0194), 1e-4);
            assertTrue(ModelPerformanceCalculator.calculateImprovement(0.03, 0.02) < 0.0);
        }
    }
}
