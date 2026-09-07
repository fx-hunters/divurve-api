package com.divurve.domain.xray;

import com.divurve.common.architecture.UseCase;
import com.divurve.common.exception.NotFoundException;
import com.divurve.domain.holding.DepositRepository;
import com.divurve.domain.holding.FxAssetValuator;
import com.divurve.domain.holding.HoldingRepository;
import com.divurve.domain.holding.KrwAssetRepository;
import com.divurve.domain.holding.entity.Deposit;
import com.divurve.domain.holding.entity.Holding;
import com.divurve.domain.holding.entity.KrwAsset;
import com.divurve.domain.settings.RiskProfileService;
import com.divurve.domain.settings.RiskProfileView;
import com.divurve.domain.user.UserRepository;
import com.divurve.domain.user.entity.User;
import com.divurve.engine.attribution.AttributionCalculator;
import com.divurve.engine.concentration.ConcentrationCalculator;
import com.divurve.engine.concentration.ConcentrationThresholdTable;
import com.divurve.engine.weight.WeightCalculator;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

/**
 * X-Ray 진단 유스케이스 (FR-XR-01~08, API 명세 v2 §5.3 · §5.4).
 * 보유 자산을 읽어 engine 계산기에 넘기고 결과만 평탄화한다 — 수치는 engine 이 만든다(CLAUDE.md §1).
 *
 * <p>이슈 #54(7.3)에서 고친 것:
 * <ul>
 *   <li>{@code krwAssetKrw = 0L} 하드코딩 → {@code krw_assets} 실조회. 이전에는 총자산 = 외화자산이라
 *       {@code fx_ratio} 가 <b>항상 1.0</b> 이었다.</li>
 *   <li>집중도 기준선 {@code 0.35} 하드코딩 → 위험성향 등급 기반 {@link ConcentrationThresholdTable}.
 *       미측정이면 기준선 없음({@code null}) → 상태 {@code unknown}.</li>
 *   <li>손익 4분해가 시작값=종료값·비용 0·<b>첫 종목만</b>으로 호출돼 응답이 전부 0 이던 것을,
 *       현재 환율 조회 + 전 종목 합산으로 바꿨다. {@code mode}(three_way/shapley) 분기는 삭제됐다.</li>
 *   <li>1퍼센트 민감도 계산이 컨트롤러 안에 있던 것을 {@link WeightCalculator} 로 옮겼다.</li>
 *   <li>JPY 를 원/100엔 고시 그대로 곱해 100배로 잡던 환산을 {@link FxAssetValuator} 로 모았다.</li>
 * </ul>
 *
 * <p>{@code POST /xray/stress} 는 명세 v2 가 {@code POST /stress/runs} 로 옮겼으므로 여기서 뺐다.
 */
@UseCase
public class XrayService {

    /** {@code threshold_source} 접두사 — 기준선의 출처가 위험성향 등급임을 밝힌다(명세 §5.3). */
    public static final String THRESHOLD_SOURCE_PREFIX = "risk_profile.";

    private final HoldingRepository holdingRepository;
    private final DepositRepository depositRepository;
    private final KrwAssetRepository krwAssetRepository;
    private final UserRepository userRepository;
    private final RiskProfileService riskProfileService;
    private final FxAssetValuator fxAssetValuator;
    private final WeightCalculator weightCalculator;
    private final AttributionCalculator attributionCalculator;
    private final ConcentrationCalculator concentrationCalculator;
    private final ConcentrationThresholdTable concentrationThresholdTable;

    public XrayService(
            HoldingRepository holdingRepository,
            DepositRepository depositRepository,
            KrwAssetRepository krwAssetRepository,
            UserRepository userRepository,
            RiskProfileService riskProfileService,
            FxAssetValuator fxAssetValuator,
            WeightCalculator weightCalculator,
            AttributionCalculator attributionCalculator,
            ConcentrationCalculator concentrationCalculator,
            ConcentrationThresholdTable concentrationThresholdTable) {
        this.holdingRepository = Objects.requireNonNull(holdingRepository, "holdingRepository is null");
        this.depositRepository = Objects.requireNonNull(depositRepository, "depositRepository is null");
        this.krwAssetRepository = Objects.requireNonNull(krwAssetRepository, "krwAssetRepository is null");
        this.userRepository = Objects.requireNonNull(userRepository, "userRepository is null");
        this.riskProfileService = Objects.requireNonNull(riskProfileService, "riskProfileService is null");
        this.fxAssetValuator = Objects.requireNonNull(fxAssetValuator, "fxAssetValuator is null");
        this.weightCalculator = Objects.requireNonNull(weightCalculator, "weightCalculator is null");
        this.attributionCalculator =
                Objects.requireNonNull(attributionCalculator, "attributionCalculator is null");
        this.concentrationCalculator =
                Objects.requireNonNull(concentrationCalculator, "concentrationCalculator is null");
        this.concentrationThresholdTable =
                Objects.requireNonNull(concentrationThresholdTable, "concentrationThresholdTable is null");
    }

    /**
     * 외화 비중·통화 노출·집중도·민감도를 조회한다 (명세 §5.3).
     *
     * @param userId 사용자 ID (NFR-SE-03)
     * @return 포트폴리오 스냅샷
     * @throws NotFoundException 사용자를 찾을 수 없는 경우
     */
    @Transactional(readOnly = true)
    public PortfolioSnapshot getPortfolio(UUID userId) {
        User user = requireUser(userId);

        List<Holding> holdings = holdingRepository.findByOwner_Id(userId);
        List<Deposit> deposits = depositRepository.findByOwner_Id(userId);

        FxAssetValuator.FxValuation valuation = fxAssetValuator.valuate(holdings, deposits);
        Map<String, Long> currencyToAssetKrw = valuation.currencyToAssetKrw();

        long fxAssetKrw = valuation.fxAssetKrw();
        long krwAssetKrw = krwAssetRepository.findByOwner_Id(userId).stream()
                .mapToLong(KrwAsset::getAmountKrw)
                .sum();
        long totalAssetKrw = krwAssetKrw + fxAssetKrw;

        Threshold threshold = resolveThreshold(userId);
        ConcentrationCalculator.ConcentrationResult concentration =
                concentrationCalculator.diagnose(currencyToAssetKrw, threshold.value());

        WeightCalculator.Sensitivity sensitivity =
                weightCalculator.calculateSensitivity1pct(currencyToAssetKrw);

        return new PortfolioSnapshot(
                totalAssetKrw,
                krwAssetKrw,
                fxAssetKrw,
                weightCalculator.calculateFxRatio(totalAssetKrw, fxAssetKrw),
                currencyToAssetKrw,
                weightCalculator.calculateExposureMap(currencyToAssetKrw, fxAssetKrw),
                new ConcentrationView(
                        concentration.topCurrency(),
                        concentration.topShare(),
                        concentration.threshold(),
                        threshold.source(),
                        concentration.status(),
                        concentration.gapPp()),
                new SensitivityView(sensitivity.totalKrw(), sensitivity.byCurrency()),
                // portfolio_snapshots 가 아직 없으므로 전일 대비는 "모름"이다(명세 §5.3: 스냅샷 없으면 null).
                null,
                user.isSampleDataSeeded());
    }

    /**
     * 원화 손익을 자산·환율·상호작용·비용 네 항으로 분해한다 (명세 §5.4, 요구사항 §4.6).
     *
     * <p>분해 방식은 고정이며 사용자 설정으로 바뀌지 않는다 — v1 의 {@code mode} 파라미터는 삭제됐다.
     *
     * @param userId       사용자 ID
     * @param currencyCode 통화 필터. {@code null} 이면 전체 외화
     * @return 4분해 결과. 네 항의 합은 {@code current_krw − cost_basis_krw} 와 정확히 같다
     * @throws NotFoundException 사용자가 없거나 해당 통화의 보유 종목이 없는 경우
     */
    @Transactional(readOnly = true)
    public AttributionAnalysis getAttribution(UUID userId, String currencyCode) {
        requireUser(userId);

        List<Holding> holdings = holdingRepository.findByOwner_Id(userId).stream()
                .filter(holding -> currencyCode == null
                        || holding.getCurrencyCode().equalsIgnoreCase(currencyCode))
                .toList();

        if (holdings.isEmpty()) {
            throw new NotFoundException("해당 통화의 보유 종목을 찾을 수 없습니다.");
        }

        Map<String, BigDecimal> currentRates = fxAssetValuator.fetchPerUnitRates(holdings, List.of());

        List<AttributionCalculator.AttributionResult> perHolding = new ArrayList<>();
        List<HoldingAttribution> byHolding = new ArrayList<>();

        for (Holding holding : holdings) {
            BigDecimal rateEnd = currentRates.get(holding.getCurrencyCode());
            if (rateEnd == null) {
                continue;
            }
            // 매입 환율 근거가 없는 종목은 환율 효과를 0 으로 둔다 — 없는 근거를 지어내지 않는다(NFR-DT-01).
            BigDecimal rateStart = holding.getPurchaseFxRateKrw() != null
                    ? holding.getPurchaseFxRateKrw()
                    : rateEnd;

            double assetLocal = holding.getQuantity() * holding.getAvgPrice();
            AttributionCalculator.AttributionResult result = attributionCalculator.decompose(
                    assetLocal,
                    // 현재가 피드가 없다 — 매입가를 현재가로 쓰므로 자산 가격 효과는 0 이다.
                    // 시세 포트가 생기면 이 인자만 바꾸면 된다.
                    assetLocal,
                    rateStart,
                    rateEnd,
                    // 거래비용 기록이 없다. 0 은 "비용 없음"이 아니라 "아직 반영하지 않음"이다.
                    0.0);

            perHolding.add(result);
            byHolding.add(new HoldingAttribution(
                    holding.getTicker(),
                    result.currentKrw(),
                    result.assetReturn(),
                    result.fxReturn(),
                    attributionCalculator.krwReturn(result.assetReturn(), result.fxReturn())));
        }

        if (perHolding.isEmpty()) {
            throw new NotFoundException("해당 통화의 환율을 조회할 수 없어 손익을 분해할 수 없습니다.");
        }

        AttributionCalculator.AttributionResult total = attributionCalculator.aggregate(perHolding);

        return new AttributionAnalysis(
                currencyCode != null ? currencyCode.toUpperCase() : null,
                total.costBasisKrw(),
                total.currentKrw(),
                total.totalReturn(),
                List.of(
                        component(total.asset()),
                        component(total.fx()),
                        component(total.interaction()),
                        component(total.cost())),
                byHolding);
    }

    private User requireUser(UUID userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new NotFoundException("사용자를 찾을 수 없습니다."));
    }

    /**
     * 위험성향 등급에서 집중도 기준선을 얻는다. 미측정이면 기준선도 출처도 없다 —
     * 임의의 기본값(v1 의 0.35)을 채우지 않는다(FR-DG-02, FR-IS-06).
     */
    private Threshold resolveThreshold(UUID userId) {
        RiskProfileView profile = riskProfileService.getRiskProfile(userId);
        String grade = profile.riskType();
        Double threshold = concentrationThresholdTable.thresholdFor(grade);
        return new Threshold(threshold, threshold == null ? null : THRESHOLD_SOURCE_PREFIX + grade);
    }

    /** 집중도 기준선과 그 출처. */
    private record Threshold(Double value, String source) {
    }

    private static AttributionComponent component(
            AttributionCalculator.AttributionComponent source) {
        return new AttributionComponent(source.key(), source.krw(), source.contributionPp());
    }

    /**
     * {@code GET /xray} 응답 재료 (명세 §5.3).
     *
     * <p>engine 타입을 그대로 싣지 않는다 — engine 은 domain 에서만 접근할 수 있고(문서 4.3),
     * api 는 이 도메인 경계 record 만 본다.
     *
     * @param dayChangeKrw 전일 대비 변화. 스냅샷이 없으면 {@code null}
     * @param sampleData   이 자산이 시드된 샘플인지 (이슈 #112). 계정이 데모인지({@code is_demo})와는 다른 사실이다
     */
    public record PortfolioSnapshot(
            long totalAssetKrw,
            long krwAssetKrw,
            long fxAssetKrw,
            double fxRatio,
            Map<String, Long> currencyToAssetKrw,
            Map<String, Double> exposure,
            ConcentrationView concentration,
            SensitivityView sensitivity1pct,
            Long dayChangeKrw,
            boolean sampleData) {
    }

    /**
     * 집중도 진단 (명세 §5.3 {@code concentration}).
     *
     * @param topCurrencyCode 주력 통화. 외화자산이 없으면 {@code null}
     * @param share           주력 통화 비중. 외화자산이 없으면 {@code null}
     * @param threshold       성향별 기준선. 미측정이면 {@code null}
     * @param thresholdSource 기준선 출처 (예: {@code risk_profile.balanced}). 미측정이면 {@code null}
     * @param status          {@code above_threshold} / {@code within_threshold} / {@code unknown}
     * @param gapPp           비중 − 기준선. 기준선이 없으면 {@code null}
     */
    public record ConcentrationView(
            String topCurrencyCode,
            Double share,
            Double threshold,
            String thresholdSource,
            String status,
            Double gapPp) {
    }

    /** 환율 1퍼센트 민감도 (명세 §5.3 {@code sensitivity_1pct}). */
    public record SensitivityView(long totalKrw, Map<String, Long> byCurrency) {
    }

    /**
     * {@code GET /xray/attribution} 응답 재료 (명세 §5.4).
     *
     * @param currencyCode 필터에 쓴 통화. 전체 조회면 {@code null}
     * @param components   asset · fx · interaction · cost 순서 고정. 합은 {@code current − cost_basis}
     */
    public record AttributionAnalysis(
            String currencyCode,
            long costBasisKrw,
            long currentKrw,
            double totalReturn,
            List<AttributionComponent> components,
            List<HoldingAttribution> byHolding) {
    }

    /** 4분해 구성요소 (명세 §5.4 {@code components[]}). */
    public record AttributionComponent(String key, long krw, double contributionPp) {
    }

    /** 종목별 손익 분해 (명세 §5.4 {@code by_holding}). */
    public record HoldingAttribution(
            String ticker,
            long krw,
            double localReturn,
            double fxReturn,
            double krwReturn) {
    }
}
