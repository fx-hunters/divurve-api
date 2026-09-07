package com.divurve.domain.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.divurve.domain.RepositoryTestBase;
import com.divurve.domain.fx.PerUnitFxRates;
import com.divurve.domain.goal.GoalRepository;
import com.divurve.domain.holding.DepositRepository;
import com.divurve.domain.holding.FxAssetValuator;
import com.divurve.domain.holding.HoldingRepository;
import com.divurve.domain.holding.KrwAssetRepository;
import com.divurve.domain.port.AuthTokens;
import com.divurve.domain.port.FxRateProvider;
import com.divurve.domain.port.RateSnapshot;
import com.divurve.domain.port.TokenProvider;
import com.divurve.domain.settings.RiskProfileRepository;
import com.divurve.domain.settings.RiskProfileService;
import com.divurve.domain.settings.UserSettingsRepository;
import com.divurve.domain.settings.UserSettingsService;
import com.divurve.domain.user.UserRepository;
import com.divurve.domain.xray.XrayService;
import com.divurve.engine.attribution.AttributionCalculator;
import com.divurve.engine.concentration.ConcentrationCalculator;
import com.divurve.engine.concentration.ConcentrationThresholdTable;
import com.divurve.engine.cost.EffectiveSpreadCalculator;
import com.divurve.engine.riskprofile.DetailDiagnosisMapper;
import com.divurve.engine.riskprofile.RiskProfileScorer;
import com.divurve.engine.weight.QuoteUnitNormalizer;
import com.divurve.engine.weight.WeightCalculator;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * {@code POST /auth/demo} 시드 → {@code GET /xray} 조회를 실제 Postgres 로 관통한다 (이슈 #108).
 *
 * <p><b>이 테스트가 지키는 것</b> — 온보딩 2단계 "보유 자산을 불러올까요?" 는 하드코딩된 수치를
 * 그리지 않고 데모 시드의 실제 값을 {@code /xray} 응답으로 받아 표시한다. 그러므로 시드가 화면에
 * 그대로 보이며, <b>시드를 손대면 화면 금액이 즉시 바뀐다</b>. 단위 테스트({@code DemoSampleDataTest})는
 * 시드 상수만 보므로 "시드가 DB 를 왕복해 X-ray 계산까지 도달했을 때 어떤 금액이 되는지"는 여기서만 잡힌다.
 *
 * <p>환율만 목으로 고정한다 — ECOS 실연동에 기대면 금액이 매일 달라져 회귀를 구분할 수 없다.
 * 고정 환율에서의 금액은 <b>규모의 근거</b>이고, 실제 화면 금액은 조회 시점 환율로 움직인다.
 */
class DemoSeedXrayIntegrationTest extends RepositoryTestBase {

    private static final LocalDate TODAY = LocalDate.of(2026, 9, 7);
    private static final Clock CLOCK =
            Clock.fixed(TODAY.atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC);

    /** 고정 환율 — JPY 는 ECOS 와 같은 <b>원/100엔</b> 고시로 넣어 정규화까지 함께 검증한다. */
    private static final Map<String, BigDecimal> QUOTED_RATES = Map.of(
            "USD_KRW", new BigDecimal("1390.00"),
            "JPY_KRW", new BigDecimal("950.00"),
            "EUR_KRW", new BigDecimal("1600.00"));

    @Autowired
    private UserRepository userRepository;
    @Autowired
    private HoldingRepository holdingRepository;
    @Autowired
    private DepositRepository depositRepository;
    @Autowired
    private KrwAssetRepository krwAssetRepository;
    @Autowired
    private GoalRepository goalRepository;
    @Autowired
    private RiskProfileRepository riskProfileRepository;
    @Autowired
    private UserSettingsRepository userSettingsRepository;
    @Autowired
    private EntityManager entityManager;

    @Test
    @DisplayName("데모 시드가 온보딩 화면 규모로 조회된다 — 외화 64,058,000 / 원화 36,000,000 / 통화 3종")
    void 데모_시드가_온보딩_화면_규모로_조회된다() {
        UUID demoUserId = createDemoSession();

        XrayService.PortfolioSnapshot snapshot = xrayService().getPortfolio(demoUserId);

        // AAPL 40,032,000 + VOO 11,676,000 + USD 예금 6,950,000 + JPY 3,800,000 + EUR 1,600,000
        assertThat(snapshot.fxAssetKrw()).isEqualTo(64_058_000L);
        assertThat(snapshot.krwAssetKrw()).isEqualTo(36_000_000L);
        assertThat(snapshot.totalAssetKrw()).isEqualTo(100_058_000L);
        assertThat(snapshot.currencyToAssetKrw()).containsExactly(
                entry("USD", 58_658_000L), entry("JPY", 3_800_000L), entry("EUR", 1_600_000L));
    }

    @Test
    @DisplayName("시드된 자산은 is_sample_data=true 로 조회된다 — 체험용 데이터 배지의 근거")
    void 시드된_자산은_샘플로_표시된다() {
        UUID demoUserId = createDemoSession();

        assertThat(xrayService().getPortfolio(demoUserId).sampleData()).isTrue();
    }

    @Test
    @DisplayName("JPY 는 원/100엔 고시를 접어 반영한다 — 접지 않으면 380,000,000 원으로 100배가 된다")
    void JPY_고시_단위가_정규화된다() {
        UUID demoUserId = createDemoSession();

        XrayService.PortfolioSnapshot snapshot = xrayService().getPortfolio(demoUserId);

        assertThat(snapshot.currencyToAssetKrw()).containsEntry("JPY", 3_800_000L);
    }

    @Test
    @DisplayName("시드가 집중도 경고를 실제로 띄운다 — USD 비중이 균형항로형 기준선 0.60 을 넘는다")
    void 시드가_집중도_경고를_띄운다() {
        UUID demoUserId = createDemoSession();

        XrayService.ConcentrationView concentration = xrayService().getPortfolio(demoUserId).concentration();

        assertThat(concentration.topCurrencyCode()).isEqualTo("USD");
        assertThat(concentration.share()).isGreaterThan(concentration.threshold());
        assertThat(concentration.status()).isNotEqualTo("unknown");
    }

    /** 데모 세션을 만들고 영속성 컨텍스트를 비워 실제 조회가 DB 를 읽게 한다. */
    private UUID createDemoSession() {
        TokenProvider tokenProvider = mock(TokenProvider.class);
        when(tokenProvider.issue(any(UUID.class), anyBoolean()))
                .thenReturn(new AuthTokens("access", "refresh", 1800L));

        SampleDataSeeder seeder = new SampleDataSeeder(
                holdingRepository,
                depositRepository,
                krwAssetRepository,
                goalRepository,
                userRepository,
                riskProfileService(),
                CLOCK);
        new AuthDemoService(userRepository, seeder, tokenProvider).createDemoSession();

        entityManager.flush();
        entityManager.clear();

        return userRepository.findAll().stream()
                .filter(user -> user.isDemo())
                .findFirst()
                .orElseThrow()
                .getId();
    }

    private RiskProfileService riskProfileService() {
        return new RiskProfileService(
                riskProfileRepository,
                userRepository,
                new UserSettingsService(userSettingsRepository, userRepository, new EffectiveSpreadCalculator()),
                new RiskProfileScorer(),
                new DetailDiagnosisMapper(),
                CLOCK);
    }

    private XrayService xrayService() {
        return new XrayService(
                holdingRepository,
                depositRepository,
                krwAssetRepository,
                userRepository,
                riskProfileService(),
                new FxAssetValuator(new PerUnitFxRates(stubFxRates(), new QuoteUnitNormalizer())),
                new WeightCalculator(),
                new AttributionCalculator(),
                new ConcentrationCalculator(),
                new ConcentrationThresholdTable());
    }

    /** 고정 환율 어댑터 — 목록에 없는 통화쌍은 조회 실패와 같게 예외로 돌려준다. */
    private static FxRateProvider stubFxRates() {
        return pairCode -> {
            BigDecimal rate = QUOTED_RATES.get(pairCode);
            if (rate == null) {
                throw new IllegalStateException("환율 없음: " + pairCode);
            }
            return new RateSnapshot(pairCode, rate, TODAY, "test", Instant.parse("2026-09-07T00:00:00Z"));
        };
    }
}
