package com.divurve.domain.config;

import com.divurve.engine.attribution.AttributionCalculator;
import com.divurve.engine.bucket.BucketAllocator;
import com.divurve.engine.concentration.ConcentrationCalculator;
import com.divurve.engine.concentration.ConcentrationThresholdTable;
import com.divurve.engine.cost.CostCalculator;
import com.divurve.engine.cost.EffectiveSpreadCalculator;
import com.divurve.engine.diversification.DiversificationSimulator;
import com.divurve.engine.fx.CrossRateDeriver;
import com.divurve.engine.fx.FxRateGapDetector;
import com.divurve.engine.planner.AdjustmentOptionSelector;
import com.divurve.engine.planner.BudgetFeasibilityEvaluator;
import com.divurve.engine.planner.BusinessDayCalendar;
import com.divurve.engine.planner.EqualSplitAllocator;
import com.divurve.engine.planner.ExchangeCostCalculator;
import com.divurve.engine.planner.RecurringAcquisitionCalculator;
import com.divurve.engine.planner.RoundScheduleGenerator;
import com.divurve.engine.planner.SkipRedistributor;
import com.divurve.engine.riskprofile.DetailDiagnosisMapper;
import com.divurve.engine.riskprofile.RiskProfileScorer;
import com.divurve.engine.simulate.MonteCarloSimulator;
import com.divurve.engine.split.SplitVarianceReducer;
import com.divurve.engine.stress.StressCalculator;
import com.divurve.engine.volatility.MarketChecks;
import com.divurve.engine.volatility.RegimeBadgeMapper;
import com.divurve.engine.volatility.RegimeClassifier;
import com.divurve.engine.weight.QuoteUnitNormalizer;
import com.divurve.engine.weight.WeightCalculator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * engine 계산 컴포넌트를 Spring 빈으로 등록한다 (이슈 #10). engine 모듈은 프레임워크에 의존하지 않으므로
 * ({@code @EngineComponent} 는 스테레오타입이 아님) 컴포넌트 스캔 대상이 아니다 — 여기서 명시적으로 빈을 만든다.
 *
 * <p>이 설정은 domain 레이어에 둔다: engine 은 domain 에서만 접근 가능하다는 아키텍처 규칙(문서 4.3)을 지키기 위함이다.
 * engine 계산 서비스는 상태가 없는 순수 함수이므로 싱글턴 빈으로 안전하게 공유된다.
 */
@Configuration
public class EngineConfig {

    @Bean
    public RiskProfileScorer riskProfileScorer() {
        return new RiskProfileScorer();
    }

    /** 상세 진단(Q4~Q6) 매핑기 — 점수를 만들지 않고 제목 수식어·재개 커서만 만든다 (명세 §5.2). */
    @Bean
    public DetailDiagnosisMapper detailDiagnosisMapper() {
        return new DetailDiagnosisMapper();
    }

    @Bean
    public EffectiveSpreadCalculator effectiveSpreadCalculator() {
        return new EffectiveSpreadCalculator();
    }

    // 이슈 #14 X-Ray/Fit 계산기 — XrayService/FitService 가 주입받는다. 등록이 없으면 컨텍스트 기동이 실패한다.

    @Bean
    public WeightCalculator weightCalculator() {
        return new WeightCalculator();
    }

    @Bean
    public AttributionCalculator attributionCalculator() {
        return new AttributionCalculator();
    }

    @Bean
    public StressCalculator stressCalculator() {
        return new StressCalculator();
    }

    @Bean
    public ConcentrationCalculator concentrationCalculator() {
        return new ConcentrationCalculator();
    }

    @Bean
    public DiversificationSimulator diversificationSimulator() {
        return new DiversificationSimulator();
    }

    // 이슈 #54(7.3) — 집중도 기준선 표·고시 단위 정규화. 하드코딩(0.35 임계값, JPY 100엔 미정규화) 대체.

    @Bean
    public ConcentrationThresholdTable concentrationThresholdTable() {
        return new ConcentrationThresholdTable();
    }

    /** 삼각 유도 환율 — ECOS 가 원화 크로스만 주므로 USDJPY·EURUSD 는 비로 만든다 (이슈 #57). */
    @Bean
    public CrossRateDeriver crossRateDeriver() {
        return new CrossRateDeriver();
    }

    @Bean
    public QuoteUnitNormalizer quoteUnitNormalizer() {
        return new QuoteUnitNormalizer();
    }

    // 이슈 #18 계획 미리보기 계산기 — PlanPreviewService 가 주입받는다.
    // 이슈 #38 이전에는 등록이 누락되어 컨텍스트 기동이 NoSuchBeanDefinitionException 으로 실패했다.

    @Bean
    public BucketAllocator bucketAllocator() {
        return new BucketAllocator();
    }

    @Bean
    public SplitVarianceReducer splitVarianceReducer() {
        return new SplitVarianceReducer();
    }

    @Bean
    public CostCalculator costCalculator() {
        return new CostCalculator();
    }

    @Bean
    public MonteCarloSimulator monteCarloSimulator() {
        return new MonteCarloSimulator();
    }

    // 상태 어휘 엔진 (API 명세 v2 §2·§5.10). v1 안전모드 평가기(SafeModeEvaluator)를 대체한다 —
    // 명세 §0.1 이 503 SAFE_MODE_ACTIVE 를 삭제하면서 "급변 상태에서도 기능을 끄지 않는다"로 바뀌었다.

    @Bean
    public RegimeClassifier regimeClassifier() {
        return new RegimeClassifier();
    }

    @Bean
    public RegimeBadgeMapper regimeBadgeMapper() {
        return new RegimeBadgeMapper();
    }

    @Bean
    public MarketChecks marketChecks() {
        return new MarketChecks();
    }

    // 이슈 #83 플래너 계산 엔진 (플래너 명세 §9·§10·§15). 안전/기회 버킷 + 몬테카를로 모델을
    // 대체한다 — 명세 §23 이 그 값들을 산출 근거 불명으로 지목했고 §24 가 균등 회차를 확정했다.

    @Bean
    public BusinessDayCalendar businessDayCalendar() {
        return new BusinessDayCalendar();
    }

    /**
     * 환율 구멍 탐지기 (이슈 #116). 영업일 판정을 {@link BusinessDayCalendar} 에 위임하므로
     * 공휴일이 달력에 들어오는 날 구멍 판정도 함께 정확해진다.
     */
    @Bean
    public FxRateGapDetector fxRateGapDetector(BusinessDayCalendar businessDayCalendar) {
        return new FxRateGapDetector(businessDayCalendar);
    }

    @Bean
    public RoundScheduleGenerator roundScheduleGenerator() {
        return new RoundScheduleGenerator();
    }

    @Bean
    public EqualSplitAllocator equalSplitAllocator() {
        return new EqualSplitAllocator();
    }

    @Bean
    public ExchangeCostCalculator exchangeCostCalculator() {
        return new ExchangeCostCalculator();
    }

    @Bean
    public BudgetFeasibilityEvaluator budgetFeasibilityEvaluator() {
        return new BudgetFeasibilityEvaluator();
    }

    @Bean
    public RecurringAcquisitionCalculator recurringAcquisitionCalculator(
            ExchangeCostCalculator exchangeCostCalculator) {
        return new RecurringAcquisitionCalculator(exchangeCostCalculator);
    }

    @Bean
    public SkipRedistributor skipRedistributor(EqualSplitAllocator equalSplitAllocator) {
        return new SkipRedistributor(equalSplitAllocator);
    }

    @Bean
    public AdjustmentOptionSelector adjustmentOptionSelector() {
        return new AdjustmentOptionSelector();
    }
}
