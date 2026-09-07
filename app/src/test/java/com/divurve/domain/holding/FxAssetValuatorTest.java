package com.divurve.domain.holding;

import com.divurve.domain.fx.StoredFxRates;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.entry;
import static org.mockito.Mockito.when;

import com.divurve.domain.fx.PerUnitFxRates;
import com.divurve.domain.holding.entity.Deposit;
import com.divurve.domain.holding.entity.Holding;
import com.divurve.domain.port.FxRateProvider;
import com.divurve.domain.port.RateSnapshot;
import com.divurve.domain.user.entity.User;
import com.divurve.engine.weight.QuoteUnitNormalizer;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * {@link FxAssetValuator} — 보유 종목·외화 예금 → 통화별 원화 평가액 (FR-XR-01 · FR-XR-02).
 * X-Ray 와 Fit 이 각자 갖고 있던 환산을 한 곳으로 모으면서 JPY 100엔 고시 문제를 함께 해소했다.
 */
@ExtendWith(MockitoExtension.class)
class FxAssetValuatorTest {

    private static final LocalDate AS_OF = LocalDate.of(2026, 9, 1);
    private static final Instant FETCHED_AT = Instant.parse("2026-09-01T15:30:00Z");

    @Mock
    private FxRateProvider fxRateProvider;

    private final User user = User.createDemo("me@divurve.com", "나");

    private FxAssetValuator valuator() {
        return new FxAssetValuator(new PerUnitFxRates(StoredFxRates.NONE, fxRateProvider, new QuoteUnitNormalizer()));
    }

    @Test
    @DisplayName("보유 종목과 예금을 통화별로 합산한다")
    void 통화별로_합산한다() {
        givenRate("USD", "1382.40");
        Holding holding = Holding.create(user, "VOO", "USD", 10, 1_000);
        Deposit deposit = Deposit.create(user, "USD", new BigDecimal("1000"));

        FxAssetValuator.FxValuation valuation = valuator().valuate(List.of(holding), List.of(deposit));

        // (10 × 1,000 + 1,000) × 1,382.40 = 15,206,400원
        assertThat(valuation.currencyToAssetKrw()).containsExactly(entry("USD", 15_206_400L));
        assertThat(valuation.fxAssetKrw()).isEqualTo(15_206_400L);
        assertThat(valuation.currencyToRateKrw()).containsEntry("USD", new BigDecimal("1382.40"));
    }

    @Test
    @DisplayName("JPY 는 원/100엔 고시를 1엔 기준으로 접는다 (ERD §4.1 quote_unit)")
    void JPY_는_100엔_고시를_접는다() {
        givenRate("JPY", "939.13");
        Deposit deposit = Deposit.create(user, "JPY", new BigDecimal("500000"));

        FxAssetValuator.FxValuation valuation = valuator().valuate(List.of(), List.of(deposit));

        assertThat(valuation.fxAssetKrw()).isEqualTo(4_695_650L);
        assertThat(valuation.currencyToRateKrw().get("JPY"))
                .isEqualByComparingTo(new BigDecimal("9.3913"));
    }

    @Test
    @DisplayName("결과는 원화 평가액 내림차순이다 (명세 §5.3 exposure 순서)")
    void 평가액_내림차순으로_정렬한다() {
        givenRate("USD", "1382.40");
        givenRate("JPY", "939.13");
        givenRate("EUR", "1499.90");

        FxAssetValuator.FxValuation valuation = valuator().valuate(List.of(), List.of(
                Deposit.create(user, "EUR", new BigDecimal("2000")),
                Deposit.create(user, "JPY", new BigDecimal("500000")),
                Deposit.create(user, "USD", new BigDecimal("10000"))));

        assertThat(valuation.currencyToAssetKrw().keySet())
                .containsExactly("USD", "JPY", "EUR");
    }

    @Test
    @DisplayName("환율을 못 구한 통화는 종목·예금 모두 평가에서 빠진다 — 0원으로 지어내지 않는다")
    void 환율이_없으면_제외한다() {
        when(fxRateProvider.fetchLatest("GBP_KRW")).thenReturn(null);

        FxAssetValuator.FxValuation valuation = valuator().valuate(
                List.of(Holding.create(user, "VOD", "GBP", 10, 100)),
                List.of(Deposit.create(user, "GBP", new BigDecimal("100"))));

        assertThat(valuation.currencyToAssetKrw()).isEmpty();
        assertThat(valuation.fxAssetKrw()).isZero();
    }

    @Test
    @DisplayName("어댑터가 지원하지 않는 통화는 그 통화만 빠지고 나머지는 그대로 계산된다 (이슈 #57)")
    void 지원하지_않는_통화가_있어도_나머지는_계산된다() {
        // CurrencyMaster 는 GBP 를 노출하지만 ECOS item-code 에는 없다 —
        // 예외를 그대로 올리면 GBP 보유 하나 때문에 /xray 전체가 400 이 됐다.
        givenRate("USD", "1382.40");
        when(fxRateProvider.fetchLatest("GBP_KRW"))
                .thenThrow(new IllegalArgumentException("Unsupported pairCode for ECOS: GBP_KRW"));

        FxAssetValuator.FxValuation valuation = valuator().valuate(
                List.of(Holding.create(user, "VOD", "GBP", 10, 100)),
                List.of(Deposit.create(user, "USD", new BigDecimal("1000"))));

        assertThat(valuation.currencyToAssetKrw()).containsOnlyKeys("USD");
        assertThat(valuation.currencyToAssetKrw().get("USD")).isEqualTo(1_382_400L);
    }

    @Test
    @DisplayName("자산이 없으면 빈 결과 (FR-CM-09)")
    void 자산이_없으면_빈_결과다() {
        FxAssetValuator.FxValuation valuation = valuator().valuate(List.of(), List.of());

        assertThat(valuation.currencyToAssetKrw()).isEmpty();
        assertThat(valuation.fxAssetKrw()).isZero();
    }

    @Test
    @DisplayName("환율 조회는 통화당 한 번만 한다")
    void 환율은_통화당_한번만_조회한다() {
        givenRate("USD", "1382.40");

        valuator().valuate(
                List.of(Holding.create(user, "VOO", "USD", 1, 1),
                        Holding.create(user, "QQQ", "USD", 1, 1)),
                List.of(Deposit.create(user, "USD", BigDecimal.ONE)));

        org.mockito.Mockito.verify(fxRateProvider, org.mockito.Mockito.times(1))
                .fetchLatest("USD_KRW");
    }

    @Test
    @DisplayName("null 입력은 예외")
    void null_입력은_예외다() {
        assertThatThrownBy(() -> valuator().valuate(null, List.of()))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> valuator().valuate(List.of(), null))
                .isInstanceOf(NullPointerException.class);
    }

    private void givenRate(String currencyCode, String rate) {
        when(fxRateProvider.fetchLatest(currencyCode + "_KRW")).thenReturn(new RateSnapshot(
                currencyCode + "_KRW", new BigDecimal(rate), AS_OF, "ECOS", FETCHED_AT));
    }
}
