package com.divurve.domain.fit;

import com.divurve.domain.fx.StoredFxRates;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.entry;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import com.divurve.common.exception.InvalidRequestException;
import com.divurve.common.exception.NotFoundException;
import com.divurve.domain.fx.PerUnitFxRates;
import com.divurve.domain.holding.DepositRepository;
import com.divurve.domain.holding.FxAssetValuator;
import com.divurve.domain.holding.HoldingRepository;
import com.divurve.domain.holding.entity.Deposit;
import com.divurve.domain.port.FxRateProvider;
import com.divurve.domain.port.RateSnapshot;
import com.divurve.domain.settings.RiskProfileService;
import com.divurve.domain.settings.RiskProfileView;
import com.divurve.domain.user.UserRepository;
import com.divurve.domain.user.entity.User;
import com.divurve.engine.concentration.ConcentrationCalculator;
import com.divurve.engine.concentration.ConcentrationThresholdTable;
import com.divurve.engine.diversification.DiversificationAdjustmentException;
import com.divurve.engine.diversification.DiversificationSimulator;
import com.divurve.engine.weight.QuoteUnitNormalizer;
import com.divurve.engine.weight.WeightCalculator;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * {@link FitService} — 성향과 노출의 관계(§5.5), 비중 가정 미리보기(§5.6).
 * engine 계산기는 실제 인스턴스를 써서 명세 §4 fixture 수치를 그대로 재현한다.
 */
@ExtendWith(MockitoExtension.class)
class FitServiceTest {

    private static final LocalDate AS_OF = LocalDate.of(2026, 9, 1);
    private static final Instant FETCHED_AT = Instant.parse("2026-09-01T15:30:00Z");

    @Mock
    private HoldingRepository holdingRepository;
    @Mock
    private DepositRepository depositRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private RiskProfileService riskProfileService;
    @Mock
    private FxRateProvider fxRateProvider;

    private final UUID userId = UUID.randomUUID();
    private final User user = User.create("me@divurve.com", "나", null);

    private FitService service() {
        return new FitService(
                holdingRepository,
                depositRepository,
                userRepository,
                riskProfileService,
                new FxAssetValuator(new PerUnitFxRates(StoredFxRates.NONE, fxRateProvider, new QuoteUnitNormalizer())),
                new WeightCalculator(),
                new ConcentrationCalculator(),
                new ConcentrationThresholdTable(),
                new DiversificationSimulator());
    }

    // --- GET /fit (명세 §5.5) ---

    @Test
    @DisplayName("명세 §5.5 fixture — balanced 0.60 기준선 대비 USD 0.6388 → above, gap 0.0388")
    void fixture_관계는_코드와_사실값만_낸다() {
        givenUser();
        givenFixturePortfolio();
        givenRiskGrade("balanced");

        FitService.FitDiagnosis diagnosis = service().getFit(userId);

        assertThat(diagnosis.riskProfile().riskType()).isEqualTo("balanced");
        assertThat(diagnosis.riskProfile().gradeLabel()).isEqualTo("균형항로형");
        assertThat(diagnosis.concentration().topCurrencyCode()).isEqualTo("USD");
        assertThat(diagnosis.concentration().share()).isEqualTo(0.6388);
        assertThat(diagnosis.concentration().threshold()).isEqualTo(0.60);
        assertThat(diagnosis.concentration().gapPp()).isEqualTo(0.0388);
        assertThat(diagnosis.concentration().status())
                .isEqualTo("above_threshold");
        assertThat(diagnosis.relationCode()).isEqualTo(FitService.RELATION_ABOVE);
    }

    @Test
    @DisplayName("기준선 이내면 관계 코드는 within 이다")
    void 기준선_이내면_within_이다() {
        givenUser();
        givenFixturePortfolio();
        givenRiskGrade("challenging");

        FitService.FitDiagnosis diagnosis = service().getFit(userId);

        assertThat(diagnosis.concentration().threshold()).isEqualTo(0.80);
        assertThat(diagnosis.relationCode()).isEqualTo(FitService.RELATION_WITHIN);
    }

    @Test
    @DisplayName("성향 미측정이면 기준선이 없고 관계 코드는 risk_profile_not_measured 다")
    void 성향_미측정이면_관계를_말하지_않는다() {
        givenUser();
        givenFixturePortfolio();
        givenRiskGrade(null);

        FitService.FitDiagnosis diagnosis = service().getFit(userId);

        assertThat(diagnosis.riskProfile().status()).isEqualTo("not_measured");
        assertThat(diagnosis.concentration().threshold()).isNull();
        assertThat(diagnosis.concentration().gapPp()).isNull();
        assertThat(diagnosis.relationCode()).isEqualTo(FitService.RELATION_UNKNOWN);
    }

    @Test
    @DisplayName("없는 사용자는 404")
    void 없는_사용자는_404() {
        when(userRepository.findById(userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().getFit(userId)).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service().preview(userId, "JPY", 0.10))
                .isInstanceOf(NotFoundException.class);
    }

    // --- POST /fit/preview (명세 §5.6) ---

    @Test
    @DisplayName("명세 §5.6 fixture — JPY +10%p 가정 시 USD 0.5567 / JPY 0.3213 / EUR 0.1220")
    void fixture_비중_가정_전후() {
        givenUser();
        givenFixturePortfolio();
        givenRiskGrade("balanced");

        FitService.FitPreview preview = service().preview(userId, "JPY", 0.10);

        assertThat(preview.fxAssetKrw()).isEqualTo(24_720_000L);
        assertThat(preview.exposureBefore()).containsExactly(
                entry("USD", 0.6388), entry("JPY", 0.2213), entry("EUR", 0.1400));
        assertThat(preview.exposureAfter()).containsExactly(
                entry("USD", 0.5567), entry("JPY", 0.3213), entry("EUR", 0.1220));
    }

    @Test
    @DisplayName("명세 §5.6 fixture — 민감도 합계는 가정 전후가 같다 (247,200원)")
    void fixture_민감도_합계는_보존된다() {
        givenUser();
        givenFixturePortfolio();
        givenRiskGrade("balanced");

        FitService.FitPreview preview = service().preview(userId, "JPY", 0.10);

        assertThat(preview.sensitivityBefore().totalKrw()).isEqualTo(247_200L);
        assertThat(preview.sensitivityBefore().byCurrency()).containsExactly(
                entry("USD", 157_900L), entry("JPY", 54_700L), entry("EUR", 34_600L));

        assertThat(preview.sensitivityAfter().totalKrw()).isEqualTo(247_200L);
        assertThat(preview.sensitivityAfter().byCurrency()).containsExactly(
                entry("USD", 137_623L), entry("JPY", 79_420L), entry("EUR", 30_157L));
    }

    @Test
    @DisplayName("명세 §5.6 fixture — 집중도는 above_threshold 에서 within_threshold 로 바뀐다")
    void fixture_집중도_전후() {
        givenUser();
        givenFixturePortfolio();
        givenRiskGrade("balanced");

        FitService.FitPreview preview = service().preview(userId, "JPY", 0.10);

        assertThat(preview.concentrationBefore().status())
                .isEqualTo("above_threshold");
        assertThat(preview.concentrationAfter().topCurrencyCode()).isEqualTo("USD");
        assertThat(preview.concentrationAfter().share()).isEqualTo(0.5567);
        assertThat(preview.concentrationAfter().status())
                .isEqualTo("within_threshold");
        assertThat(preview.threshold()).isEqualTo(0.60);
        assertThat(preview.currencyCode()).isEqualTo("JPY");
        assertThat(preview.deltaShare()).isEqualTo(0.10);
    }

    @Test
    @DisplayName("통화코드는 대소문자를 가리지 않는다")
    void 통화코드는_대소문자_무관이다() {
        givenUser();
        givenFixturePortfolio();
        givenRiskGrade("balanced");

        assertThat(service().preview(userId, "jpy", 0.10).currencyCode()).isEqualTo("JPY");
    }

    @Test
    @DisplayName("통화코드가 비면 400")
    void 통화코드가_비면_400() {
        givenUser();

        assertThatThrownBy(() -> service().preview(userId, null, 0.10))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("통화코드");
        assertThatThrownBy(() -> service().preview(userId, "  ", 0.10))
                .isInstanceOf(InvalidRequestException.class);
    }

    @Test
    @DisplayName("외화자산이 없으면 400")
    void 외화자산이_없으면_400() {
        givenUser();
        when(holdingRepository.findByOwner_Id(userId)).thenReturn(List.of());
        when(depositRepository.findByOwner_Id(userId)).thenReturn(List.of());

        assertThatThrownBy(() -> service().preview(userId, "JPY", 0.10))
                .isInstanceOfSatisfying(InvalidRequestException.class, ex -> {
                    assertThat(ex.getMessage()).contains("외화자산이 없어");
                    // engine 의 EMPTY_PORTFOLIO 사유도 currency_code 로 매핑된다(이슈 #89).
                    assertThat(ex.getField()).isEqualTo("currency_code");
                });
    }

    @Test
    @DisplayName("engine 계약 위반(없는 통화·범위 밖 변화량)은 400 으로 표면화된다")
    void engine_계약_위반은_400이다() {
        givenUser();
        givenFixturePortfolio();

        assertThatThrownBy(() -> service().preview(userId, "GBP", 0.10))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("GBP");
        assertThatThrownBy(() -> service().preview(userId, "JPY", 0.90))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("범위");
    }

    /**
     * 이슈 #89 — 실제 원인이 {@code currency_code}(GBP 미보유)인데 {@code field} 가
     * {@code delta_share} 로 잘못 나가던 결함. {@link DiversificationAdjustmentException.Reason}
     * 로 원인을 구분해 field 를 정확히 붙이는지 검증한다.
     */
    @Test
    @DisplayName("포트폴리오에 없는 통화를 조정하려 하면 field 는 currency_code 다")
    void 없는_통화면_field는_currency_code다() {
        givenUser();
        givenFixturePortfolio();

        assertThatThrownBy(() -> service().preview(userId, "GBP", 0.10))
                .isInstanceOfSatisfying(InvalidRequestException.class,
                        ex -> assertThat(ex.getField()).isEqualTo("currency_code"));
    }

    @Test
    @DisplayName("조정 후 비중이 범위를 벗어나면 field 는 delta_share 다")
    void 비중_범위_초과면_field는_delta_share다() {
        givenUser();
        givenFixturePortfolio();

        assertThatThrownBy(() -> service().preview(userId, "JPY", 0.90))
                .isInstanceOfSatisfying(InvalidRequestException.class,
                        ex -> assertThat(ex.getField()).isEqualTo("delta_share"));
    }

    @Test
    @DisplayName("생성자는 협력자 null 을 거부한다")
    void 생성자는_null_을_거부한다() {
        assertThatThrownBy(() -> new FitService(null, null, null, null, null, null, null, null, null))
                .isInstanceOf(NullPointerException.class);
    }

    // --- fixture helpers ---

    private void givenUser() {
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
    }

    private void givenRiskGrade(String grade) {
        lenient().when(riskProfileService.getRiskProfile(userId)).thenReturn(new RiskProfileView(
                grade == null ? "not_measured" : "simple_done",
                grade,
                grade == null ? null : "균형항로형",
                grade == null ? null : 4,
                grade == null ? null : AS_OF,
                null, null, null, null));
    }

    /** 명세 §4 fixture: USD 15,790,000 · JPY 5,470,000 · EUR 3,460,000 (합 24,720,000). */
    private void givenFixturePortfolio() {
        when(holdingRepository.findByOwner_Id(userId)).thenReturn(List.of());
        when(depositRepository.findByOwner_Id(userId)).thenReturn(List.of(
                depositWorth("USD", 15_790_000L, "1382.40"),
                depositWorth("JPY", 5_470_000L, "9.3913"),
                depositWorth("EUR", 3_460_000L, "1499.90")));
        givenRate("USD", "1382.40");
        // ECOS 는 JPY 를 원/100엔으로 고시한다.
        givenRate("JPY", "939.13");
        givenRate("EUR", "1499.90");
    }

    private Deposit depositWorth(String currencyCode, long krw, String perUnitRate) {
        BigDecimal amount = BigDecimal.valueOf(krw)
                .divide(new BigDecimal(perUnitRate), 10, RoundingMode.HALF_UP);
        return Deposit.create(user, currencyCode, amount);
    }

    private void givenRate(String currencyCode, String rate) {
        lenient().when(fxRateProvider.fetchLatest(currencyCode + "_KRW"))
                .thenReturn(new RateSnapshot(
                        currencyCode + "_KRW", new BigDecimal(rate), AS_OF, "ECOS", FETCHED_AT));
    }
}
