package com.divurve.domain.master;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.divurve.domain.RepositoryTestBase;
import com.divurve.domain.master.entity.Currency;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 통화·통화쌍 마스터 스키마와 시드 검증 (이슈 #111, 마이그레이션 V20·V21).
 *
 * <p>실제 Postgres 에 Flyway 를 적용해 확인한다 — 시드 값과 CHECK 제약은 단위 테스트로는
 * 검증할 수 없다. 특히 {@code CurrencyMaster} 하드코딩 상수에서 옮겨 온 값들이 그대로인지가
 * 플래너 계산 회귀와 직결된다.
 */
@DisplayName("통화 마스터 스키마·시드")
class CurrencyMasterSchemaTest extends RepositoryTestBase {

    @Autowired
    private CurrencyRepository currencyRepository;

    @Autowired
    private CurrencyPairRepository currencyPairRepository;

    @Autowired
    private EntityManager entityManager;

    @Test
    @DisplayName("외화 목록은 기존 CurrencyMaster 와 같은 순서로 나온다 — /currencies 응답 순서다")
    void foreignCurrencies_KeepDisplayOrder() {
        assertThat(currencyRepository.findByHomeCurrencyFalseOrderBySortOrderAsc())
                .extracting(Currency::getCurrencyCode)
                .containsExactly("USD", "EUR", "JPY", "GBP", "CNY");
    }

    @Test
    @DisplayName("자국 통화는 KRW 하나뿐이고 외화 목록에서 빠진다")
    void homeCurrency_IsKrwOnly() {
        assertThat(currencyRepository.findAll())
                .filteredOn(Currency::isHomeCurrency)
                .extracting(Currency::getCurrencyCode)
                .containsExactly("KRW");
    }

    @Test
    @DisplayName("JPY 는 소수 자릿수 0, 호가 단위 100 이다 — ECOS 100엔 고시")
    void jpy_HasHundredQuoteUnit() {
        Currency jpy = currencyRepository.findById("JPY").orElseThrow();

        assertThat(jpy.getMinorUnits()).isZero();
        assertThat(jpy.getQuoteUnit()).isEqualTo((short) 100);
        assertThat(jpy.getUsdSide()).isEqualTo("base");
    }

    @Test
    @DisplayName("GBP 만 미지원이고 사유가 남아 있다 — ECOS 미고시(이슈 #95)")
    void gbp_IsUnsupportedWithReason() {
        Currency gbp = currencyRepository.findById("GBP").orElseThrow();

        assertThat(gbp.isSupported()).isFalse();
        assertThat(gbp.getSupportNote()).contains("ECOS");

        assertThat(currencyRepository.findAll())
                .filteredOn(currency -> !currency.isSupported())
                .extracting(Currency::getCurrencyCode)
                .containsExactly("GBP");
    }

    @Test
    @DisplayName("색상 토큰은 기존 상수 값을 그대로 옮겼다 — 프론트 디자인 토큰이 깨지지 않는다")
    void colorTokens_MatchPreviousConstants() {
        assertThat(currencyRepository.findByHomeCurrencyFalseOrderBySortOrderAsc())
                .extracting(Currency::getColorToken)
                .containsExactly("currency-usd", "currency-eur", "currency-jpy",
                        "currency-gbp", "currency-cny");
    }

    @Test
    @DisplayName("저장 쌍은 ECOS 가 실제로 고시하는 4쌍이다")
    void storedPairs_MatchEcosItemCodes() {
        assertThat(currencyPairRepository.findByStoredTrueOrderByPairCodeAsc())
                .extracting(pair -> pair.getPairCode().trim())
                .containsExactly("CNYKRW", "EURKRW", "JPYKRW", "USDKRW");
    }

    @Test
    @DisplayName("GBPKRW 행은 없다 — 환율을 조달할 수 없는 쌍은 마스터에 넣지 않는다")
    void gbpPair_DoesNotExist() {
        assertThat(currencyPairRepository.findById("GBPKRW")).isEmpty();
    }

    @Test
    @DisplayName("자국 통화를 두 개 만들 수 없다 — 부분 유니크 인덱스가 막는다")
    void secondHomeCurrency_Rejected() {
        assertThatThrownBy(() -> {
            entityManager.createNativeQuery(
                            "insert into currencies (currency_code, name_ko, symbol, minor_units,"
                                    + " quote_unit, usd_side, is_home_currency, is_supported,"
                                    + " sort_order) values"
                                    + " ('XXX', '테스트', 'X', 2, 1, 'none', true, false, 99)")
                    .executeUpdate();
            entityManager.flush();
        }).isInstanceOf(Exception.class);
    }

    @Test
    @DisplayName("허용되지 않는 usd_side 는 CHECK 가 막는다")
    void invalidUsdSide_Rejected() {
        assertThatThrownBy(() -> {
            entityManager.createNativeQuery(
                            "insert into currencies (currency_code, name_ko, symbol, minor_units,"
                                    + " quote_unit, usd_side, is_home_currency, is_supported,"
                                    + " sort_order) values"
                                    + " ('YYY', '테스트', 'Y', 2, 1, 'sideways', false, false, 99)")
                    .executeUpdate();
            entityManager.flush();
        }).isInstanceOf(Exception.class);
    }

    @Test
    @DisplayName("저장 쌍에 유도 경로를 넣을 수 없다 — ck_currency_pairs_derive")
    void storedPairWithDerivePath_Rejected() {
        assertThatThrownBy(() -> {
            entityManager.createNativeQuery(
                            "insert into currency_pairs (pair_code, base_currency_code,"
                                    + " quote_currency_code, is_stored, derive_via_pair_code)"
                                    + " values ('USDJPY', 'USD', 'JPY', true, 'USDKRW')")
                    .executeUpdate();
            entityManager.flush();
        }).isInstanceOf(Exception.class);
    }

    @Test
    @DisplayName("마스터에 없는 통화로 통화쌍을 만들 수 없다 — FK 가 오타를 막는다")
    void pairWithUnknownCurrency_Rejected() {
        assertThatThrownBy(() -> {
            entityManager.createNativeQuery(
                            "insert into currency_pairs (pair_code, base_currency_code,"
                                    + " quote_currency_code, is_stored) values"
                                    + " ('CADKRW', 'CAD', 'KRW', true)")
                    .executeUpdate();
            entityManager.flush();
        }).isInstanceOf(Exception.class);
    }
}
