package com.divurve.domain.xray;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.entry;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import com.divurve.common.exception.NotFoundException;
import com.divurve.domain.fx.PerUnitFxRates;
import com.divurve.domain.holding.DepositRepository;
import com.divurve.domain.holding.FxAssetValuator;
import com.divurve.domain.holding.HoldingRepository;
import com.divurve.domain.holding.KrwAssetRepository;
import com.divurve.domain.holding.entity.Deposit;
import com.divurve.domain.holding.entity.Holding;
import com.divurve.domain.holding.entity.KrwAsset;
import com.divurve.domain.holding.entity.PurchaseFxRate;
import com.divurve.domain.port.FxRateProvider;
import com.divurve.domain.port.RateSnapshot;
import com.divurve.domain.settings.RiskProfileService;
import com.divurve.domain.settings.RiskProfileView;
import com.divurve.domain.user.UserRepository;
import com.divurve.domain.user.entity.User;
import com.divurve.engine.attribution.AttributionCalculator;
import com.divurve.engine.concentration.ConcentrationCalculator;
import com.divurve.engine.concentration.ConcentrationThresholdTable;
import com.divurve.engine.weight.QuoteUnitNormalizer;
import com.divurve.engine.weight.WeightCalculator;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * {@link XrayService} — 자산 조회 → engine 위임 → 도메인 경계 record 변환 (명세 §5.3 · §5.4).
 *
 * <p>engine 계산기는 순수 함수이므로 목이 아닌 실제 인스턴스를 쓴다: 서비스가 넘긴 입력이 실제 계산
 * 결과로 이어지는지를 <b>수치로</b> 단언하기 위함이다. 외부 의존(FxRateProvider)과 리포지토리만 목이다.
 */
@ExtendWith(MockitoExtension.class)
class XrayServiceTest {

    private static final LocalDate AS_OF = LocalDate.of(2026, 9, 1);
    private static final Instant FETCHED_AT = Instant.parse("2026-09-01T15:30:00Z");

    @Mock
    private HoldingRepository holdingRepository;
    @Mock
    private DepositRepository depositRepository;
    @Mock
    private KrwAssetRepository krwAssetRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private RiskProfileService riskProfileService;
    @Mock
    private FxRateProvider fxRateProvider;

    private final UUID userId = UUID.randomUUID();
    private final User user = User.create("me@divurve.com", "나", null);

    private XrayService service() {
        return new XrayService(
                holdingRepository,
                depositRepository,
                krwAssetRepository,
                userRepository,
                riskProfileService,
                new FxAssetValuator(new PerUnitFxRates(fxRateProvider, new QuoteUnitNormalizer())),
                new WeightCalculator(),
                new AttributionCalculator(),
                new ConcentrationCalculator(),
                new ConcentrationThresholdTable());
    }

    // --- GET /xray (명세 §5.3) ---

    @Test
    @DisplayName("샘플 시드를 받은 사용자는 sample_data=true 로 나간다 — is_demo 와 다른 사실이다")
    void 샘플_시드_여부가_스냅샷에_실린다() {
        User seeded = User.create("seeded@divurve.com", "샘플", null);
        seeded.markSampleDataSeeded();
        when(userRepository.findById(userId)).thenReturn(Optional.of(seeded));
        givenEmptyPortfolio();
        givenRiskGrade("balanced");

        assertThat(service().getPortfolio(userId).sampleData()).isTrue();
    }

    @Test
    @DisplayName("직접 입력한 사용자는 sample_data=false 다")
    void 시드를_받지_않았으면_샘플이_아니다() {
        givenUser();
        givenEmptyPortfolio();
        givenRiskGrade("balanced");

        assertThat(service().getPortfolio(userId).sampleData()).isFalse();
    }


    @Test
    @DisplayName("명세 §4 fixture — 총자산 68,400,000 / 외화 24,720,000 / 비중 0.3614 / 민감도 합 247,200")
    void fixture_외화비중과_민감도를_재현한다() {
        givenUser();
        givenFixturePortfolio();
        givenRiskGrade("balanced");

        XrayService.PortfolioSnapshot snapshot = service().getPortfolio(userId);

        assertThat(snapshot.krwAssetKrw()).isEqualTo(43_680_000L);
        assertThat(snapshot.fxAssetKrw()).isEqualTo(24_720_000L);
        assertThat(snapshot.totalAssetKrw()).isEqualTo(68_400_000L);
        assertThat(snapshot.fxRatio()).isEqualTo(0.3614);
        assertThat(snapshot.currencyToAssetKrw()).containsExactly(
                entry("USD", 15_790_000L), entry("JPY", 5_470_000L), entry("EUR", 3_460_000L));
        assertThat(snapshot.exposure()).containsExactly(
                entry("USD", 0.6388), entry("JPY", 0.2213), entry("EUR", 0.1400));
        assertThat(snapshot.sensitivity1pct().totalKrw()).isEqualTo(247_200L);
        assertThat(snapshot.sensitivity1pct().byCurrency()).containsExactly(
                entry("USD", 157_900L), entry("JPY", 54_700L), entry("EUR", 34_600L));
        // portfolio_snapshots 가 없으므로 전일 대비는 null 이다.
        assertThat(snapshot.dayChangeKrw()).isNull();
    }

    @Test
    @DisplayName("명세 §5.3 — 집중도 기준선은 성향 등급에서 온다 (balanced 0.60 → above_threshold)")
    void 집중도_기준선은_성향_등급에서_온다() {
        givenUser();
        givenFixturePortfolio();
        givenRiskGrade("balanced");

        XrayService.PortfolioSnapshot snapshot = service().getPortfolio(userId);

        assertThat(snapshot.concentration().topCurrencyCode()).isEqualTo("USD");
        assertThat(snapshot.concentration().share()).isEqualTo(0.6388);
        assertThat(snapshot.concentration().threshold()).isEqualTo(0.60);
        assertThat(snapshot.concentration().status()).isEqualTo("above_threshold");
        assertThat(snapshot.concentration().thresholdSource()).isEqualTo("risk_profile.balanced");
    }

    @Test
    @DisplayName("성향 미측정이면 기준선도 출처도 없고 상태는 unknown 이다 (v1 의 0.35 기본값 제거)")
    void 성향_미측정이면_unknown_이다() {
        givenUser();
        givenFixturePortfolio();
        givenRiskGrade(null);

        XrayService.PortfolioSnapshot snapshot = service().getPortfolio(userId);

        assertThat(snapshot.concentration().threshold()).isNull();
        assertThat(snapshot.concentration().thresholdSource()).isNull();
        assertThat(snapshot.concentration().status()).isEqualTo("unknown");
    }

    @Test
    @DisplayName("JPY 는 원/100엔 고시를 1엔 기준으로 접어 환산한다 (v1 은 100배로 잡았다)")
    void JPY_는_100엔_고시를_정규화한다() {
        givenUser();
        when(holdingRepository.findByOwner_Id(userId)).thenReturn(List.of());
        when(depositRepository.findByOwner_Id(userId)).thenReturn(
                List.of(deposit("JPY", new BigDecimal("500000"))));
        when(krwAssetRepository.findByOwner_Id(userId)).thenReturn(List.of());
        givenRate("JPY", "939.13");
        givenRiskGrade("balanced");

        XrayService.PortfolioSnapshot snapshot = service().getPortfolio(userId);

        // 500,000엔 × 9.3913원 = 4,695,650원. 정규화가 없으면 469,565,000원이 된다.
        assertThat(snapshot.fxAssetKrw()).isEqualTo(4_695_650L);
    }

    @Test
    @DisplayName("자산이 없으면 비중 0 · 빈 노출 · unknown (FR-CM-09)")
    void 자산이_없으면_빈_상태다() {
        givenUser();
        when(holdingRepository.findByOwner_Id(userId)).thenReturn(List.of());
        when(depositRepository.findByOwner_Id(userId)).thenReturn(List.of());
        when(krwAssetRepository.findByOwner_Id(userId)).thenReturn(List.of());
        givenRiskGrade("balanced");

        XrayService.PortfolioSnapshot snapshot = service().getPortfolio(userId);

        assertThat(snapshot.totalAssetKrw()).isZero();
        assertThat(snapshot.fxRatio()).isZero();
        assertThat(snapshot.exposure()).isEmpty();
        assertThat(snapshot.concentration().status()).isEqualTo("unknown");
        assertThat(snapshot.sensitivity1pct().totalKrw()).isZero();
    }

    @Test
    @DisplayName("없는 사용자는 404")
    void 없는_사용자는_404() {
        when(userRepository.findById(userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().getPortfolio(userId))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service().getAttribution(userId, "USD"))
                .isInstanceOf(NotFoundException.class);
    }

    // --- GET /xray/attribution (명세 §5.4) ---

    @Test
    @DisplayName("환율이 오르면 fx 항이 실제 값으로 나온다 (v1 은 시작값=종료값이라 전부 0 이었다)")
    void 손익_4분해가_실제_값을_낸다() {
        givenUser();
        when(holdingRepository.findByOwner_Id(userId)).thenReturn(
                List.of(holding("VOO", "USD", 10, 1_000, new BigDecimal("1300.00"))));
        givenRate("USD", "1382.40");

        XrayService.AttributionAnalysis analysis = service().getAttribution(userId, "USD");

        // 매입원가 10,000 USD × 1,300 = 13,000,000원. 환율 +6.3385% → fx 824,000원.
        assertThat(analysis.costBasisKrw()).isEqualTo(13_000_000L);
        assertThat(analysis.components()).extracting(XrayService.AttributionComponent::key)
                .containsExactly("asset", "fx", "interaction", "cost");
        assertThat(analysis.components()).extracting(XrayService.AttributionComponent::krw)
                .containsExactly(0L, 824_000L, 0L, 0L);
        assertThat(analysis.currentKrw()).isEqualTo(13_824_000L);
        // 검산 항등식: 네 항의 합 = current − cost_basis
        assertThat(analysis.components().stream()
                .mapToLong(XrayService.AttributionComponent::krw).sum())
                .isEqualTo(analysis.currentKrw() - analysis.costBasisKrw());
        assertThat(analysis.currencyCode()).isEqualTo("USD");
    }

    @Test
    @DisplayName("첫 종목만이 아니라 전 종목을 합산한다 (v1 은 holdings.get(0) 만 봤다)")
    void 전_종목을_합산한다() {
        givenUser();
        when(holdingRepository.findByOwner_Id(userId)).thenReturn(List.of(
                holding("VOO", "USD", 10, 1_000, new BigDecimal("1300.00")),
                holding("QQQ", "USD", 5, 1_000, new BigDecimal("1300.00"))));
        givenRate("USD", "1382.40");

        XrayService.AttributionAnalysis analysis = service().getAttribution(userId, "USD");

        assertThat(analysis.byHolding()).hasSize(2);
        assertThat(analysis.costBasisKrw()).isEqualTo(19_500_000L);
        assertThat(analysis.byHolding()).extracting(XrayService.HoldingAttribution::ticker)
                .containsExactly("VOO", "QQQ");
        assertThat(analysis.byHolding().get(0).fxReturn()).isEqualTo(0.0634);
        assertThat(analysis.byHolding().get(0).krwReturn()).isEqualTo(0.0634);
    }

    @Test
    @DisplayName("매입 환율 근거가 없는 종목은 환율 효과 0 으로 둔다 (없는 근거를 만들지 않는다)")
    void 매입_환율이_없으면_환율효과는_0이다() {
        givenUser();
        when(holdingRepository.findByOwner_Id(userId)).thenReturn(
                List.of(holding("VOO", "USD", 10, 1_000, null)));
        givenRate("USD", "1382.40");

        XrayService.AttributionAnalysis analysis = service().getAttribution(userId, null);

        assertThat(analysis.currencyCode()).isNull();
        assertThat(analysis.components().get(1).krw()).isZero();
        assertThat(analysis.costBasisKrw()).isEqualTo(13_824_000L);
    }

    @Test
    @DisplayName("통화 필터는 대소문자를 가리지 않는다")
    void 통화_필터는_대소문자_무관이다() {
        givenUser();
        when(holdingRepository.findByOwner_Id(userId)).thenReturn(
                List.of(holding("VOO", "USD", 10, 1_000, new BigDecimal("1300.00"))));
        givenRate("USD", "1382.40");

        assertThat(service().getAttribution(userId, "usd").currencyCode()).isEqualTo("USD");
    }

    @Test
    @DisplayName("해당 통화의 보유 종목이 없으면 404")
    void 해당_통화_종목이_없으면_404() {
        givenUser();
        when(holdingRepository.findByOwner_Id(userId)).thenReturn(
                List.of(holding("VOO", "USD", 10, 1_000, new BigDecimal("1300.00"))));

        assertThatThrownBy(() -> service().getAttribution(userId, "EUR"))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("보유 종목");
    }

    @Test
    @DisplayName("환율을 못 구하면 404 — 지어낸 수치로 분해하지 않는다")
    void 환율을_못_구하면_404() {
        givenUser();
        when(holdingRepository.findByOwner_Id(userId)).thenReturn(
                List.of(holding("VOO", "USD", 10, 1_000, new BigDecimal("1300.00"))));
        when(fxRateProvider.fetchLatest("USD_KRW")).thenReturn(null);

        assertThatThrownBy(() -> service().getAttribution(userId, "USD"))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("환율");
    }

    // --- fixture helpers ---

    private void givenUser() {
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
    }

    private void givenRiskGrade(String grade) {
        when(riskProfileService.getRiskProfile(userId)).thenReturn(new RiskProfileView(
                grade == null ? "not_measured" : "simple_done",
                grade,
                grade == null ? null : "균형항로형",
                grade == null ? null : 4,
                grade == null ? null : AS_OF,
                null, null, null, null));
    }

    private void givenRate(String currencyCode, String rate) {
        lenient().when(fxRateProvider.fetchLatest(currencyCode + "_KRW"))
                .thenReturn(new RateSnapshot(
                        currencyCode + "_KRW", new BigDecimal(rate), AS_OF, "ECOS", FETCHED_AT));
    }

    /**
     * 명세 §4 fixture 를 그대로 재현한다: 원화 43,680,000 / USD 15,790,000 · JPY 5,470,000 ·
     * EUR 3,460,000. 원화 환산액이 fixture 와 정확히 맞도록 예금 금액을 환율에서 역산했다.
     */
    /** 자산이 하나도 없는 상태 — 샘플 여부처럼 자산과 무관한 사실만 볼 때 쓴다. */
    private void givenEmptyPortfolio() {
        when(holdingRepository.findByOwner_Id(userId)).thenReturn(List.of());
        when(depositRepository.findByOwner_Id(userId)).thenReturn(List.of());
        when(krwAssetRepository.findByOwner_Id(userId)).thenReturn(List.of());
    }

    private void givenFixturePortfolio() {
        when(holdingRepository.findByOwner_Id(userId)).thenReturn(List.of());
        when(depositRepository.findByOwner_Id(userId)).thenReturn(List.of(
                deposit("USD", new BigDecimal("15790000").divide(new BigDecimal("1382.40"), 10,
                        java.math.RoundingMode.HALF_UP)),
                deposit("JPY", new BigDecimal("5470000").divide(new BigDecimal("9.3913"), 10,
                        java.math.RoundingMode.HALF_UP)),
                deposit("EUR", new BigDecimal("3460000").divide(new BigDecimal("1499.90"), 10,
                        java.math.RoundingMode.HALF_UP))));
        when(krwAssetRepository.findByOwner_Id(userId)).thenReturn(List.of(
                KrwAsset.create(user, "cash", "주거래 통장", 43_680_000L, FETCHED_AT)));
        givenRate("USD", "1382.40");
        // ECOS 는 JPY 를 원/100엔으로 고시한다 — 정규화 후 9.3913원/엔.
        givenRate("JPY", "939.13");
        givenRate("EUR", "1499.90");
    }

    private Holding holding(
            String ticker, String currencyCode, double quantity, double avgPrice,
            BigDecimal purchaseFxRateKrw) {
        Holding holding = Holding.create(user, ticker, currencyCode, quantity, avgPrice);
        if (purchaseFxRateKrw != null) {
            holding.assignPurchaseContext(
                    AS_OF, new PurchaseFxRate(purchaseFxRateKrw, "ECOS", AS_OF));
        }
        return holding;
    }

    private Deposit deposit(String currencyCode, BigDecimal amount) {
        return Deposit.create(user, currencyCode, amount);
    }

    @Test
    @DisplayName("생성자는 협력자 null 을 거부한다")
    void 생성자는_null_을_거부한다() {
        assertThatThrownBy(() -> new XrayService(
                null, null, null, null, null, null, null, null, null, null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new FxAssetValuator(null))
                .isInstanceOf(NullPointerException.class);
    }
}
