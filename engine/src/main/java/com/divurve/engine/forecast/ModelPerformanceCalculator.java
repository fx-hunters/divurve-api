package com.divurve.engine.forecast;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 모델 성적 지표 계산 (FR-FC-11, API 명세 v2 §5.8).
 *
 * <p>모델 투명성: 상대 MAE, 구간 포함률, 평균 구간 폭, 랜덤워크 대비 개선율.
 * 모든 지표는 롤링 워크포워드 검증 결과이며, 각 시점의 기준값({@code baseRates})은
 * <b>그 시점까지의 실측값</b>이라 미래 누출이 없다.
 *
 * <h2>변경 이력 (calc)</h2>
 * <ol>
 *   <li><b>MAE·구간 폭을 비율로.</b> 명세 §5.8 예시({@code mae 0.0190}, {@code avg_width 0.0580})는
 *       환율 절대값이 아니라 기준값 대비 비율이다. 절대 MAE 는 통화쌍마다 자릿수가 달라 비교가 안 된다.</li>
 *   <li><b>랜덤워크 벤치마크 정의 수정.</b> 이전 구현은 {@code initialRate} 상수를 전 구간 예측으로 써서
 *       "직전 실측값"이라는 랜덤워크 정의와 달랐다. 이제 시점별 기준값을 그대로 예측으로 쓴다.</li>
 *   <li><b>방향 적중률({@code hit_rate}) 제거 (이슈 #90).</b> 이 서비스의 기준 모델은 드리프트 0 이라
 *       점예측이 항상 기준값과 같다({@code forecastRates == baseRates}). {@code Double.compare} 로
 *       구한 방향이 실측 방향(+1/-1)과 영원히 일치할 수 없어 방향 적중률은 모든 통화쌍·모든 horizon 에서
 *       예외 없이 0 이었다 — 계산 버그가 아니라 "방향을 제시하지 않는 모델에는 방향 적중률이라는
 *       지표 자체가 성립하지 않는" 구조적 결과다. 값을 고쳐 노출할 방법이 없어 지표를 아예 없앴다.
 *       {@code calculateHitRate} 를 이 시점에 함께 제거했다 — 대체 지표는 만들지 않는다.
 *       {@code coverage_80} 이 이미 "이 모델이 얼마나 맞았나"를 정직하게 답한다.</li>
 * </ol>
 */
public class ModelPerformanceCalculator {

    private ModelPerformanceCalculator() {
    }

    /**
     * 상대 평균 절대 오차 — {@code mean(|forecast - actual| / actual)}.
     *
     * @param forecastRates 모델이 낸 값
     * @param actualRates   실제 값 (0 이면 안 된다)
     * @return 상대 MAE (예 {@code 0.019} = 1.9퍼센트). 입력이 비어 있으면 0
     * @throws IllegalArgumentException 크기가 다르거나 실제 값에 0 이 있는 경우
     */
    public static double calculateMaeRatio(List<Double> forecastRates, List<Double> actualRates) {
        Objects.requireNonNull(forecastRates, "forecastRates must not be null");
        Objects.requireNonNull(actualRates, "actualRates must not be null");
        requireSameSize(forecastRates.size(), actualRates.size());
        if (forecastRates.isEmpty()) {
            return 0.0;
        }

        double sumRelativeError = 0.0;
        for (int i = 0; i < forecastRates.size(); i++) {
            double actual = actualRates.get(i);
            if (actual == 0.0) {
                throw new IllegalArgumentException("actualRates must not contain zero");
            }
            sumRelativeError += Math.abs(forecastRates.get(i) - actual) / Math.abs(actual);
        }

        return sumRelativeError / forecastRates.size();
    }

    /**
     * 구간 포함률 — 실제값이 80퍼센트 구간 안에 든 비율.
     *
     * <p>명세 §5.8: 이 값은 <b>반드시</b> {@link #calculateAvgWidthRatio} 와 함께 노출한다.
     * 구간을 넓히면 포함률은 얼마든지 올라가므로 폭 없이는 성적이 아니다.
     *
     * @param lowerBounds 80퍼센트 구간 하단값들
     * @param upperBounds 80퍼센트 구간 상단값들
     * @param actualRates 실제 값들
     * @return 포함률 (0~1, 이상적 0.8). 입력이 비어 있으면 0
     * @throws IllegalArgumentException 세 목록의 크기가 다른 경우
     */
    public static double calculateCoverage80(
            List<Double> lowerBounds, List<Double> upperBounds, List<Double> actualRates) {
        Objects.requireNonNull(lowerBounds, "lowerBounds must not be null");
        Objects.requireNonNull(upperBounds, "upperBounds must not be null");
        Objects.requireNonNull(actualRates, "actualRates must not be null");
        requireSameSize(lowerBounds.size(), upperBounds.size());
        requireSameSize(upperBounds.size(), actualRates.size());
        if (actualRates.isEmpty()) {
            return 0.0;
        }

        int covered = 0;
        for (int i = 0; i < actualRates.size(); i++) {
            double actual = actualRates.get(i);
            if (actual >= lowerBounds.get(i) && actual <= upperBounds.get(i)) {
                covered++;
            }
        }

        return (double) covered / actualRates.size();
    }

    /**
     * 상대 평균 구간 폭 — {@code mean((upper - lower) / base)}.
     *
     * @param lowerBounds 80퍼센트 구간 하단값들
     * @param upperBounds 80퍼센트 구간 상단값들
     * @param baseRates   각 시점의 기준값 (0 이면 안 된다)
     * @return 상대 폭 (예 {@code 0.058} = 기준값의 5.8퍼센트). 입력이 비어 있으면 0
     * @throws IllegalArgumentException 크기가 다르거나 기준값에 0 이 있는 경우
     */
    public static double calculateAvgWidthRatio(
            List<Double> lowerBounds, List<Double> upperBounds, List<Double> baseRates) {
        Objects.requireNonNull(lowerBounds, "lowerBounds must not be null");
        Objects.requireNonNull(upperBounds, "upperBounds must not be null");
        Objects.requireNonNull(baseRates, "baseRates must not be null");
        requireSameSize(lowerBounds.size(), upperBounds.size());
        requireSameSize(upperBounds.size(), baseRates.size());
        if (lowerBounds.isEmpty()) {
            return 0.0;
        }

        double sumWidthRatio = 0.0;
        for (int i = 0; i < lowerBounds.size(); i++) {
            double base = baseRates.get(i);
            if (base == 0.0) {
                throw new IllegalArgumentException("baseRates must not contain zero");
            }
            sumWidthRatio += (upperBounds.get(i) - lowerBounds.get(i)) / base;
        }

        return sumWidthRatio / lowerBounds.size();
    }

    /**
     * 랜덤워크 벤치마크 — 각 시점의 <b>직전 실측값</b>을 그대로 지평 끝 값으로 쓰는 기준 모형.
     *
     * <p>환율 예측의 표준 비교 대상이다. 드리프트 0 기준선 모델은 점예측이 이 벤치마크와 같으므로
     * MAE 가 일치하고 {@link #calculateImprovement} 가 0 이 된다 — 숨기지 않고 그대로 보여준다(명세 §5.8).
     *
     * @param baseRates   각 시점의 기준값
     * @param actualRates 실제 지평 끝 값들
     * @return 랜덤워크의 상대 MAE
     * @throws IllegalArgumentException 크기가 다르거나 실제 값에 0 이 있는 경우
     */
    public static RandomWalkMetrics calculateRandomWalkBenchmark(
            List<Double> baseRates, List<Double> actualRates) {
        Objects.requireNonNull(baseRates, "baseRates must not be null");
        Objects.requireNonNull(actualRates, "actualRates must not be null");

        List<Double> randomWalkRates = new ArrayList<>(baseRates);
        double mae = calculateMaeRatio(randomWalkRates, actualRates);

        return new RandomWalkMetrics(mae);
    }

    /**
     * 랜덤워크 대비 개선율 — {@code (rwMae - modelMae) / rwMae}.
     *
     * <p>음수(악화)여도 그대로 반환한다. 명세 §5.8 이 "음수여도 그대로 보여준다"고 못박는다.
     *
     * @param modelMae 모델 상대 MAE
     * @param rwMae    랜덤워크 상대 MAE
     * @return 개선율 (예 {@code 0.1} = 10퍼센트 개선). {@code rwMae} 가 0 이면 0
     */
    public static double calculateImprovement(double modelMae, double rwMae) {
        if (rwMae == 0) {
            return 0.0;
        }
        return (rwMae - modelMae) / rwMae;
    }

    private static void requireSameSize(int left, int right) {
        if (left != right) {
            throw new IllegalArgumentException("all lists must have same size");
        }
    }

    /**
     * 랜덤워크 벤치마크 메트릭.
     *
     * @param mae 상대 MAE
     */
    public record RandomWalkMetrics(double mae) {
    }
}
