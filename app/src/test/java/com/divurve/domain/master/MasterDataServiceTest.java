package com.divurve.domain.master;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.divurve.common.exception.InvalidRequestException;
import com.divurve.common.exception.NotFoundException;
import com.divurve.domain.master.entity.Currency;
import com.divurve.support.MasterFixtures;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * {@link MasterDataService} 단위 테스트 — 통화·통화쌍 마스터 조회와 은행 환전 조건 조회.
 *
 * <p>엔티티는 마이그레이션 시드로만 채워지므로 공개 생성자가 없다. 테스트에서는 리플렉션으로
 * 필드를 채운다 — 테스트만을 위해 프로덕션 코드에 setter 를 여는 것보다 낫다. 시드 값 자체가
 * 맞는지는 실 DB 를 쓰는 {@code CurrencyMasterSchemaTest} 가 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class MasterDataServiceTest {

    @Mock
    private CurrencyRepository currencyRepository;

    @Mock
    private CurrencyPairRepository currencyPairRepository;

    private MasterDataService service() {
        return new MasterDataService(currencyRepository, currencyPairRepository);
    }

    private static Currency usd() {
        return MasterFixtures.currency("USD", "미국 달러", "$", 2, 1, "self", false, true, null, "currency-usd", 1);
    }

    private static Currency jpy() {
        return MasterFixtures.currency(
                "JPY", "일본 엔", "¥", 0, 100, "base", false, true, "100엔 고시", "currency-jpy", 3);
    }

    private static Currency krw() {
        return MasterFixtures.currency("KRW", "대한민국 원", "₩", 0, 1, "none", true, true, null, "currency-krw", 0);
    }

    @Test
    void listCurrencies_는_자국통화를_제외한_외화를_표시순서대로_반환한다() {
        when(currencyRepository.findByHomeCurrencyFalseOrderBySortOrderAsc())
                .thenReturn(List.of(usd(), jpy()));

        List<MasterDataService.CurrencyView> result = service().listCurrencies();

        assertThat(result).extracting(MasterDataService.CurrencyView::currencyCode)
                .containsExactly("USD", "JPY");
    }

    @Test
    void listCurrencies_는_엔티티의_모든_표시규칙을_뷰로_옮긴다() {
        when(currencyRepository.findByHomeCurrencyFalseOrderBySortOrderAsc())
                .thenReturn(List.of(jpy()));

        MasterDataService.CurrencyView view = service().listCurrencies().get(0);

        assertThat(view.currencyCode()).isEqualTo("JPY");
        assertThat(view.nameKo()).isEqualTo("일본 엔");
        assertThat(view.symbol()).isEqualTo("¥");
        assertThat(view.minorUnits()).isZero();
        assertThat(view.quoteUnit()).isEqualTo((short) 100);
        assertThat(view.usdSide()).isEqualTo("base");
        assertThat(view.homeCurrency()).isFalse();
        assertThat(view.supported()).isTrue();
        assertThat(view.supportNote()).isEqualTo("100엔 고시");
        assertThat(view.colorToken()).isEqualTo("currency-jpy");
        assertThat(view.sortOrder()).isEqualTo((short) 3);
    }

    @Test
    void listAllCurrencies_는_자국통화를_포함한다() {
        when(currencyRepository.findAllByOrderBySortOrderAsc())
                .thenReturn(List.of(krw(), usd()));

        assertThat(service().listAllCurrencies())
                .extracting(MasterDataService.CurrencyView::currencyCode)
                .containsExactly("KRW", "USD");
    }

    @Test
    void listCurrencyPairs_는_통화쌍의_모든_필드를_뷰로_옮긴다() {
        when(currencyPairRepository.findAllByOrderByPairCodeAsc())
                .thenReturn(List.of(MasterFixtures.pair("JPYKRW", "JPY", "KRW", false, "USDJPY")));

        MasterDataService.CurrencyPairView view = service().listCurrencyPairs().get(0);

        assertThat(view.pairCode()).isEqualTo("JPYKRW");
        assertThat(view.baseCurrencyCode()).isEqualTo("JPY");
        assertThat(view.quoteCurrencyCode()).isEqualTo("KRW");
        assertThat(view.stored()).isFalse();
        assertThat(view.deriveViaPairCode()).isEqualTo("USDJPY");
    }

    @Test
    void findCurrency_는_자국통화도_찾는다() {
        when(currencyRepository.findAllByOrderBySortOrderAsc())
                .thenReturn(List.of(krw(), usd()));

        assertThat(service().findCurrency("KRW")).isPresent();
    }

    @Test
    void findCurrency_는_마스터에_없으면_비어있다() {
        when(currencyRepository.findAllByOrderBySortOrderAsc()).thenReturn(List.of(usd()));

        assertThat(service().findCurrency("XXX")).isEmpty();
    }

    @Test
    void requireCurrency_는_마스터에_있으면_표시규칙을_돌려준다() {
        when(currencyRepository.findAllByOrderBySortOrderAsc()).thenReturn(List.of(usd()));

        assertThat(service().requireCurrency("USD").quoteUnit()).isEqualTo((short) 1);
    }

    @Test
    void requireCurrency_는_마스터에_없는_통화면_400_을_던진다() {
        when(currencyRepository.findAllByOrderBySortOrderAsc()).thenReturn(List.of(usd()));

        assertThatThrownBy(() -> service().requireCurrency("XXX"))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("XXX");
    }

    @Test
    void 조회는_호출할_때마다_리포지토리를_친다_캐시는_프록시가_담당한다() {
        when(currencyRepository.findAllByOrderBySortOrderAsc()).thenReturn(List.of(usd()));

        MasterDataService service = service();
        service.listAllCurrencies();
        service.listAllCurrencies();

        // @Cacheable 은 Spring 프록시가 거는 것이라 순수 단위 테스트에서는 동작하지 않는다.
        // 여기서 검증하는 것은 "서비스 자신은 상태를 들고 있지 않다" 는 사실이다 —
        // 자체 캐시를 들면 프록시 캐시와 이중이 되어 무효화 지점이 둘로 갈린다.
        verify(currencyRepository, times(2)).findAllByOrderBySortOrderAsc();
    }

    @Test
    void getFxTerms_는_은행코드와_조건목록을_담아_반환한다() {
        MasterDataService.FxTerms result = service().getFxTerms("081");

        assertThat(result.bankCode()).isEqualTo("081");
        assertThat(result.terms()).hasSize(6);
    }

    @Test
    void getFxTerms_는_미등록_은행이면_NotFound_를_던진다() {
        assertThatThrownBy(() -> service().getFxTerms("999"))
                .isInstanceOf(NotFoundException.class);
    }
}
