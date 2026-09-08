package com.divurve.engine.diversification;

import com.divurve.engine.EngineComponent;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 분산효과 시뮬레이터 (이슈 #14, FR-FT-02).
 * 통화 비중 조정 시 포트폴리오 변동성 변화를 계산한다.
 * 로직: 개별 통화 변동성과 상관계수를 조합하여 포트폴리오 변동성을 계산.
 */
@EngineComponent
public class DiversificationSimulator {

    private static final int PRECISION_SCALE = 4;
    private static final double EPSILON = 1e-10;

    /**
     * 통화 비중 조정 시 포트폴리오 변동성 변화를 시뮬레이션한다.
     *
     * @param currencyToShare        현재 통화별 비중(0~1)의 맵
     * @param currencyToVolatility   통화별 변동성(annualized)의 맵
     * @param currencyPairCorrelation 통화쌍별 상관계수의 맵 (예: "USD_KRW-EUR_KRW" → 0.5)
     * @param targetCurrency         조정 대상 통화
     * @param deltaShare             비중 변화량 (양수: 증가, 음수: 감소)
     * @return 시뮬레이션 결과
     * @throws DiversificationAdjustmentException 대상 통화가 없거나 조정 후 비중이 0~1 범위를
     *                                             벗어나는 경우 (이슈 #89, {@code reason()} 참고)
     */
    public SimulationResult simulate(
            Map<String, Double> currencyToShare,
            Map<String, Double> currencyToVolatility,
            Map<String, Double> currencyPairCorrelation,
            String targetCurrency,
            double deltaShare) {
        Objects.requireNonNull(currencyToShare, "통화별 비중 맵은 null일 수 없습니다.");
        Objects.requireNonNull(currencyToVolatility, "통화별 변동성 맵은 null일 수 없습니다.");
        Objects.requireNonNull(currencyPairCorrelation, "상관계수 맵은 null일 수 없습니다.");
        Objects.requireNonNull(targetCurrency, "조정 대상 통화는 null일 수 없습니다.");

        // 조정 전 포트폴리오 변동성
        double volatilityBefore = calculatePortfolioVolatility(
                currencyToShare,
                currencyToVolatility,
                currencyPairCorrelation
        );

        // 비중 조정
        Map<String, Double> adjustedShare = adjustShare(currencyToShare, targetCurrency, deltaShare);

        // 조정 후 포트폴리오 변동성
        double volatilityAfter = calculatePortfolioVolatility(
                adjustedShare,
                currencyToVolatility,
                currencyPairCorrelation
        );

        return new SimulationResult(
                volatilityBefore,
                volatilityAfter,
                adjustedShare
        );
    }

    /**
     * 포트폴리오 변동성을 계산한다. (이전/이후 공용)
     * σ_p = sqrt(Σ w_i^2 σ_i^2 + 2 × Σ w_i × w_j × σ_i × σ_j × ρ_ij)
     */
    private double calculatePortfolioVolatility(
            Map<String, Double> currencyToShare,
            Map<String, Double> currencyToVolatility,
            Map<String, Double> correlations) {
        double varianceSum = 0.0;

        // 개별 항: Σ w_i^2 σ_i^2
        for (Map.Entry<String, Double> entry : currencyToShare.entrySet()) {
            String currency = entry.getKey();
            double weight = entry.getValue();
            double volatility = currencyToVolatility.getOrDefault(currency, 0.0);

            varianceSum += weight * weight * volatility * volatility;
        }

        // 상관항: 2 × Σ w_i × w_j × σ_i × σ_j × ρ_ij
        String[] currencies = currencyToShare.keySet().toArray(new String[0]);
        for (int i = 0; i < currencies.length; i++) {
            for (int j = i + 1; j < currencies.length; j++) {
                String curr_i = currencies[i];
                String curr_j = currencies[j];

                double weight_i = currencyToShare.get(curr_i);
                double weight_j = currencyToShare.get(curr_j);
                double vol_i = currencyToVolatility.getOrDefault(curr_i, 0.0);
                double vol_j = currencyToVolatility.getOrDefault(curr_j, 0.0);

                // 상관계수 조회 (순서 무관)
                double correlation = getCorrelation(correlations, curr_i, curr_j);

                varianceSum += 2 * weight_i * weight_j * vol_i * vol_j * correlation;
            }
        }

        return varianceSum > EPSILON ? Math.sqrt(varianceSum) : 0.0;
    }

    /**
     * 통화 비중을 가정해서 바꿨을 때의 <b>통화별 원화 금액표</b>를 만든다
     * (FR-FT-03, 명세 §5.6 {@code POST /fit/preview}).
     *
     * <p>대상 통화를 {@code deltaShare} 만큼 올리고(내리고) 나머지 통화는 <b>비례 재배분</b>한다.
     * 외화자산 총액은 고정 — 저장하지 않는 "가정"이다. 변동성·상관계수는 쓰지 않으므로
     * 임의 상수(0.12/0.14/0.10, ρ=0.5)에 의존하던 경로를 대체한다.
     *
     * <p>비중이 아니라 금액을 돌려주는 이유: 비중을 4자리로 반올림한 뒤 금액으로 되돌리면
     * 합이 외화자산 총액과 어긋나 {@code sensitivity_1pct.total_krw} 가 변한다(명세 §5.6 은
     * 가정 전후의 합계가 같음을 보인다). 반올림 잔차는 <b>가장 큰 통화</b>에 몰아 총액을 정확히 보존한다.
     *
     * @param currencyToAssetKrw 통화코드 → 금액(원화)
     * @param targetCurrency     가정을 적용할 통화
     * @param deltaShare         비중 변화량 (양수 증가, 음수 감소)
     * @return 재배분된 통화별 원화 금액. 합계는 입력 합계와 정확히 같다
     * @throws DiversificationAdjustmentException 외화자산이 0이거나, 대상 통화가 없거나,
     *                                  조정 후 비중이 0~1 범위를 벗어나는 경우 — {@code reason()} 으로
     *                                  구분한다(이슈 #89)
     */
    public Map<String, Long> redistributeAmounts(
            Map<String, Long> currencyToAssetKrw,
            String targetCurrency,
            double deltaShare) {
        Objects.requireNonNull(currencyToAssetKrw, "통화별 자산 맵은 null일 수 없습니다.");
        Objects.requireNonNull(targetCurrency, "조정 대상 통화는 null일 수 없습니다.");

        long total = currencyToAssetKrw.values().stream().mapToLong(Long::longValue).sum();
        if (total <= 0L) {
            throw new DiversificationAdjustmentException(
                    "외화자산이 없어 비중 가정을 적용할 수 없습니다.",
                    DiversificationAdjustmentException.Reason.EMPTY_PORTFOLIO);
        }

        Map<String, Double> shares = new LinkedHashMap<>();
        currencyToAssetKrw.forEach((currency, krw) -> shares.put(currency, (double) krw / total));

        Map<String, Double> adjusted = adjustShare(shares, targetCurrency, deltaShare);

        Map<String, Long> amounts = new LinkedHashMap<>();
        adjusted.forEach((currency, share) -> amounts.put(currency, Math.round(share * total)));

        return absorbRoundingDrift(amounts, total);
    }

    /** 반올림 잔차를 금액이 가장 큰 통화에 흡수시켜 합계를 정확히 보존한다. */
    private Map<String, Long> absorbRoundingDrift(Map<String, Long> amounts, long total) {
        long drift = total - amounts.values().stream().mapToLong(Long::longValue).sum();
        if (drift == 0L) {
            return amounts;
        }

        String largest = amounts.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .orElseThrow()
                .getKey();
        amounts.put(largest, amounts.get(largest) + drift);
        return amounts;
    }

    /**
     * 비중을 조정한다. 다른 통화의 비중은 비례적으로 조정하여 합이 1이 되도록 한다.
     */
    private Map<String, Double> adjustShare(
            Map<String, Double> currencyToShare,
            String targetCurrency,
            double deltaShare) {
        if (!currencyToShare.containsKey(targetCurrency)) {
            throw new DiversificationAdjustmentException(
                    "조정 대상 통화 " + targetCurrency + "가 포트폴리오에 없습니다.",
                    DiversificationAdjustmentException.Reason.UNKNOWN_CURRENCY);
        }

        Map<String, Double> adjusted = new LinkedHashMap<>(currencyToShare);
        double currentTargetShare = adjusted.get(targetCurrency);
        double newTargetShare = currentTargetShare + deltaShare;

        if (newTargetShare < 0 || newTargetShare > 1) {
            throw new DiversificationAdjustmentException(
                    "조정 후 비중이 범위를 벗어났습니다 (조정 대상: " + targetCurrency +
                            ", 현재: " + currentTargetShare + ", 조정량: " + deltaShare + ").",
                    DiversificationAdjustmentException.Reason.SHARE_OUT_OF_RANGE);
        }

        // 조정 대상 통화의 비중 변경
        adjusted.put(targetCurrency, newTargetShare);

        // 나머지 통화들의 비중을 비례적으로 조정
        double remainingShareBefore = 1.0 - currentTargetShare;
        double remainingShareAfter = 1.0 - newTargetShare;

        if (remainingShareBefore > EPSILON) {
            double ratio = remainingShareAfter / remainingShareBefore;

            for (String currency : adjusted.keySet()) {
                if (!currency.equals(targetCurrency)) {
                    adjusted.put(currency, adjusted.get(currency) * ratio);
                }
            }
        }

        return adjusted;
    }

    /**
     * 상관계수 맵에서 통화쌍의 상관계수를 조회한다. (순서 무관)
     */
    private double getCorrelation(
            Map<String, Double> correlations,
            String curr1,
            String curr2) {
        String key1 = curr1 + "_" + curr2;
        String key2 = curr2 + "_" + curr1;

        if (correlations.containsKey(key1)) {
            return correlations.get(key1);
        }
        if (correlations.containsKey(key2)) {
            return correlations.get(key2);
        }

        // 기본값: 독립 (상관계수 0)
        return 0.0;
    }

    /** 시뮬레이션 결과 DTO. */
    public record SimulationResult(
            double portfolioVolBefore,
            double portfolioVolAfter,
            Map<String, Double> adjustedShare
    ) {
    }
}
