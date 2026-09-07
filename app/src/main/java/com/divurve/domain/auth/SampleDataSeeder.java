package com.divurve.domain.auth;

import com.divurve.common.architecture.UseCase;
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
import com.divurve.domain.holding.entity.PurchaseFxRate;
import com.divurve.domain.settings.RiskProfileService;
import com.divurve.domain.user.entity.User;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;

/**
 * {@link DemoSampleData} 템플릿을 한 사용자의 데이터로 복제한다 — <b>템플릿은 하나, 인스턴스는 계정마다</b>.
 *
 * <p><b>왜 데모 유스케이스에서 떼어냈나</b>(이슈 #108) — 시드가 필요한 계정이 둘이 됐다.
 * <ol>
 *   <li><b>데모 계정</b>({@link AuthDemoService}) — 시연용 계정에 샘플을 채우는 것은 제품 기능이며 계속 유지된다.</li>
 *   <li><b>일반 가입 계정</b>({@link AuthService}) — 원래는 금융기관에서 <b>실제 자산을 불러와야</b> 하지만,
 *       MVP 범위에 실연동이 없다. 그동안 빈 계정으로 두면 온보딩 2단계부터 홈·X-ray·플랜까지
 *       전부 빈 화면이 되므로 <b>임시로</b> 같은 샘플을 넣는다.</li>
 * </ol>
 *
 * <p><b>2번은 한시적이다.</b> 실연동이 도착하면 {@code AuthService} 의 호출과
 * {@code app.onboarding.seed-sample-assets-on-signup} 플래그를 함께 걷어낸다 — 이 클래스 자체는
 * 데모 계정이 계속 쓰므로 남는다.
 *
 * <p>계산 로직은 없다. 위험성향도 유형을 시드하지 않고 진단 응답만 제출해
 * {@link RiskProfileService} 가 결정론적으로 산출하게 한다(CLAUDE.md 1장 — 수치는 계산 로직만 만든다).
 */
@UseCase
public class SampleDataSeeder {

    private final HoldingRepository holdingRepository;
    private final DepositRepository depositRepository;
    private final KrwAssetRepository krwAssetRepository;
    private final GoalRepository goalRepository;
    private final RiskProfileService riskProfileService;
    private final Clock clock;

    public SampleDataSeeder(
            HoldingRepository holdingRepository,
            DepositRepository depositRepository,
            KrwAssetRepository krwAssetRepository,
            GoalRepository goalRepository,
            RiskProfileService riskProfileService,
            Clock clock) {
        this.holdingRepository = holdingRepository;
        this.depositRepository = depositRepository;
        this.krwAssetRepository = krwAssetRepository;
        this.goalRepository = goalRepository;
        this.riskProfileService = riskProfileService;
        this.clock = clock;
    }

    /**
     * {@link DemoSampleData} 정의를 이 사용자의 보유 종목·외화 예금·원화 자산·목표로 복제하고,
     * 간편 진단 응답을 제출한다.
     *
     * @param owner 시드를 받을 사용자 (이미 저장돼 있어야 한다)
     */
    public void seed(User owner) {
        LocalDate today = LocalDate.now(clock);

        for (HoldingSample sample : DemoSampleData.HOLDINGS) {
            Holding holding = Holding.create(
                    owner, sample.ticker(), sample.currencyCode(), sample.quantity(), sample.avgPrice());
            holding.assignPurchaseContext(
                    sample.purchasedOn(today), purchaseFxRate(sample.purchaseFxRateKrw(), sample.purchasedOn(today)));
            holdingRepository.save(holding);
        }

        for (DepositSample sample : DemoSampleData.DEPOSITS) {
            Deposit deposit = Deposit.create(owner, sample.currencyCode(), sample.amount());
            deposit.assignPurchaseContext(
                    sample.purchasedOn(today), purchaseFxRate(sample.purchaseFxRateKrw(), sample.purchasedOn(today)));
            depositRepository.save(deposit);
        }

        for (KrwAssetSample sample : DemoSampleData.KRW_ASSETS) {
            krwAssetRepository.save(
                    KrwAsset.create(owner, sample.kind(), sample.label(), sample.amountKrw(), clock.instant()));
        }

        GoalSample goal = DemoSampleData.GOAL;
        goalRepository.save(Goal.builder(owner, goal.name(), goal.kind(), goal.purpose(), goal.currencyCode())
                .targetAmount(goal.targetAmount())
                .targetDate(goal.targetDate(today))
                .budgetAmount(goal.budgetAmount())
                .budgetCurrencyCode(goal.budgetCurrencyCode())
                .budgetPeriod(goal.budgetPeriod())
                .isSpeculative(goal.isSpeculative())
                .status(goal.status())
                .build());

        // 유형·점수·기준선은 여기서 만들지 않는다 — 응답만 제출하고 산출은 RiskProfileScorer 가 한다.
        riskProfileService.submitSimple(owner.getId(), DemoSampleData.RISK_PROFILE_ANSWERS);
    }

    private PurchaseFxRate purchaseFxRate(BigDecimal rateKrw, LocalDate purchasedOn) {
        return new PurchaseFxRate(rateKrw, DemoSampleData.PURCHASE_FX_RATE_SOURCE, purchasedOn);
    }
}
