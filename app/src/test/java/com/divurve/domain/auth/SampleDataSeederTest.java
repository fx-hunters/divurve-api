package com.divurve.domain.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.divurve.domain.auth.DemoSampleData.DepositSample;
import com.divurve.domain.auth.DemoSampleData.GoalSample;
import com.divurve.domain.auth.DemoSampleData.HoldingSample;
import com.divurve.domain.auth.DemoSampleData.KrwAssetSample;
import com.divurve.domain.goal.GoalRepository;
import com.divurve.domain.goal.entity.Goal;
import com.divurve.domain.holding.DepositRepository;
import com.divurve.domain.holding.HoldingRepository;
import com.divurve.domain.holding.KrwAssetRepository;
import com.divurve.domain.holding.entity.Deposit;
import com.divurve.domain.holding.entity.Holding;
import com.divurve.domain.holding.entity.KrwAsset;
import com.divurve.domain.settings.RiskProfileService;
import com.divurve.domain.user.entity.User;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * {@link SampleDataSeeder} 단위 테스트 — {@link DemoSampleData} 정의가 <b>빠짐없이</b> 한 사용자의
 * 데이터로 복제되는지 검증한다.
 *
 * <p>시드 값 자체는 검증하지 않는다. 값이 시연 시나리오를 만족하는지는 {@link DemoSampleDataTest} 가,
 * 그 값이 DB 를 왕복해 X-ray 계산에 도달했을 때의 금액은 {@code DemoSeedXrayIntegrationTest} 가 본다.
 */
@ExtendWith(MockitoExtension.class)
class SampleDataSeederTest {

    /** 상대 날짜 계산의 기준일을 고정한다. */
    private static final Instant NOW = Instant.parse("2026-09-07T00:00:00Z");
    private static final LocalDate TODAY = LocalDate.ofInstant(NOW, ZoneOffset.UTC);

    @Mock
    private HoldingRepository holdingRepository;
    @Mock
    private DepositRepository depositRepository;
    @Mock
    private KrwAssetRepository krwAssetRepository;
    @Mock
    private GoalRepository goalRepository;
    @Mock
    private RiskProfileService riskProfileService;

    private final User owner = User.create("me@divurve.com", "나", "hash");

    private SampleDataSeeder seeder;

    @BeforeEach
    void setUp() {
        seeder = new SampleDataSeeder(
                holdingRepository,
                depositRepository,
                krwAssetRepository,
                goalRepository,
                riskProfileService,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void 보유_종목은_정의대로_매입_컨텍스트까지_복제된다() {
        seeder.seed(owner);

        ArgumentCaptor<Holding> captor = ArgumentCaptor.forClass(Holding.class);
        verify(holdingRepository, times(DemoSampleData.HOLDINGS.size())).save(captor.capture());
        List<Holding> saved = captor.getAllValues();

        for (int i = 0; i < DemoSampleData.HOLDINGS.size(); i++) {
            HoldingSample sample = DemoSampleData.HOLDINGS.get(i);
            Holding holding = saved.get(i);

            assertThat(holding.getTicker()).isEqualTo(sample.ticker());
            assertThat(holding.getCurrencyCode()).isEqualTo(sample.currencyCode());
            assertThat(holding.getQuantity()).isEqualTo(sample.quantity());
            assertThat(holding.getAvgPrice()).isEqualTo(sample.avgPrice());
            assertThat(holding.getPurchasedAt()).isEqualTo(sample.purchasedOn(TODAY));
            assertThat(holding.getPurchaseFxRateKrw()).isEqualByComparingTo(sample.purchaseFxRateKrw());
            assertThat(holding.getPurchaseFxRateSource()).isEqualTo(DemoSampleData.PURCHASE_FX_RATE_SOURCE);
            assertThat(holding.getPurchaseFxRateAsOf()).isEqualTo(sample.purchasedOn(TODAY));
        }
    }

    @Test
    void 외화_예금은_정의대로_매입_컨텍스트까지_복제된다() {
        seeder.seed(owner);

        ArgumentCaptor<Deposit> captor = ArgumentCaptor.forClass(Deposit.class);
        verify(depositRepository, times(DemoSampleData.DEPOSITS.size())).save(captor.capture());
        List<Deposit> saved = captor.getAllValues();

        for (int i = 0; i < DemoSampleData.DEPOSITS.size(); i++) {
            DepositSample sample = DemoSampleData.DEPOSITS.get(i);
            Deposit deposit = saved.get(i);

            assertThat(deposit.getCurrencyCode()).isEqualTo(sample.currencyCode());
            assertThat(deposit.getAmount()).isEqualByComparingTo(sample.amount());
            assertThat(deposit.getPurchasedAt()).isEqualTo(sample.purchasedOn(TODAY));
            assertThat(deposit.getPurchaseFxRateKrw()).isEqualByComparingTo(sample.purchaseFxRateKrw());
            assertThat(deposit.getPurchaseFxRateSource()).isEqualTo(DemoSampleData.PURCHASE_FX_RATE_SOURCE);
            assertThat(deposit.getPurchaseFxRateAsOf()).isEqualTo(sample.purchasedOn(TODAY));
        }
    }

    @Test
    void 원화_자산은_정의대로_복제된다() {
        seeder.seed(owner);

        ArgumentCaptor<KrwAsset> captor = ArgumentCaptor.forClass(KrwAsset.class);
        verify(krwAssetRepository, times(DemoSampleData.KRW_ASSETS.size())).save(captor.capture());
        List<KrwAsset> saved = captor.getAllValues();

        for (int i = 0; i < DemoSampleData.KRW_ASSETS.size(); i++) {
            KrwAssetSample sample = DemoSampleData.KRW_ASSETS.get(i);
            KrwAsset krwAsset = saved.get(i);

            assertThat(krwAsset.getKind()).isEqualTo(sample.kind());
            assertThat(krwAsset.getLabel()).isEqualTo(sample.label());
            assertThat(krwAsset.getAmountKrw()).isEqualTo(sample.amountKrw());
            assertThat(krwAsset.getUpdatedAt()).isEqualTo(NOW);
        }
    }

    @Test
    void 목표는_정의대로_복제되고_목표일은_기준일_기준_상대값이다() {
        seeder.seed(owner);

        ArgumentCaptor<Goal> captor = ArgumentCaptor.forClass(Goal.class);
        verify(goalRepository).save(captor.capture());
        Goal saved = captor.getValue();
        GoalSample sample = DemoSampleData.GOAL;

        assertThat(saved.getName()).isEqualTo(sample.name());
        assertThat(saved.getKind()).isEqualTo(sample.kind());
        assertThat(saved.getPurpose()).isEqualTo(sample.purpose());
        assertThat(saved.getCurrencyCode()).isEqualTo(sample.currencyCode());
        assertThat(saved.getTargetAmount()).isEqualTo(sample.targetAmount());
        assertThat(saved.getTargetDate()).isEqualTo(sample.targetDate(TODAY));
        assertThat(saved.getBudgetAmount()).isEqualTo(sample.budgetAmount());
        assertThat(saved.getBudgetCurrencyCode()).isEqualTo(sample.budgetCurrencyCode());
        assertThat(saved.getBudgetPeriod()).isEqualTo(sample.budgetPeriod());
        assertThat(saved.isSpeculative()).isEqualTo(sample.isSpeculative());
        assertThat(saved.getStatus()).isEqualTo(sample.status());
    }

    @Test
    void 위험성향은_유형이_아니라_진단_응답을_제출해_산출하게_한다() {
        seeder.seed(owner);

        // 유형·점수·기준선을 직접 박지 않는다 — 산출은 RiskProfileScorer 의 몫이다(CLAUDE.md 1장).
        verify(riskProfileService).submitSimple(any(), eq(DemoSampleData.RISK_PROFILE_ANSWERS));
    }

}
