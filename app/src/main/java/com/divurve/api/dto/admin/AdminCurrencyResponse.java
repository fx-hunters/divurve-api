package com.divurve.api.dto.admin;

import com.divurve.domain.master.MasterDataService;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * 관리자 통화 마스터 응답 (이슈 #111) — {@code currencies}·{@code currency_pairs} 전 컬럼.
 *
 * <p>공개 {@code GET /api/v1/currencies} 와 다른 응답이다. 그쪽은 프론트가 표시에 쓰는 5필드만
 * 담고 계약이 고정돼 있다. 여기는 DB 에 실제로 무엇이 들어 있는지를 보는 것이 목적이다.
 */
public record AdminCurrencyResponse(
        List<AdminCurrency> currencies, List<AdminCurrencyPair> currencyPairs) {

    public static AdminCurrencyResponse of(
            List<MasterDataService.CurrencyView> currencies,
            List<MasterDataService.CurrencyPairView> pairs) {
        return new AdminCurrencyResponse(
                currencies.stream().map(AdminCurrency::from).toList(),
                pairs.stream().map(AdminCurrencyPair::from).toList());
    }

    /** {@code currencies} 전 컬럼. */
    public record AdminCurrency(
            @Schema(example = "USD") String currencyCode,
            String nameKo,
            String symbol,
            @Schema(example = "2") short minorUnits,
            @Schema(example = "1", allowableValues = {"1", "100"}) short quoteUnit,
            @Schema(example = "self", allowableValues = {"self", "base", "quote", "none"})
            String usdSide,
            boolean isHomeCurrency,
            boolean isSupported,
            @Schema(description = "미지원 사유. 지원 통화면 보통 null") String supportNote,
            String colorToken,
            short sortOrder) {

        static AdminCurrency from(MasterDataService.CurrencyView c) {
            return new AdminCurrency(
                    c.currencyCode(), c.nameKo(), c.symbol(), c.minorUnits(), c.quoteUnit(),
                    c.usdSide(), c.homeCurrency(), c.supported(), c.supportNote(),
                    c.colorToken(), c.sortOrder());
        }
    }

    /** {@code currency_pairs} 전 컬럼. */
    public record AdminCurrencyPair(
            @Schema(example = "USDKRW") String pairCode,
            String baseCurrencyCode,
            String quoteCurrencyCode,
            @Schema(description = "환율을 직접 적재하는 쌍인가. false 면 유도 쌍이다")
            boolean isStored,
            @Schema(description = "유도 경로. 저장 쌍이면 null") String deriveViaPairCode) {

        static AdminCurrencyPair from(MasterDataService.CurrencyPairView p) {
            return new AdminCurrencyPair(
                    p.pairCode(), p.baseCurrencyCode(), p.quoteCurrencyCode(),
                    p.stored(), p.deriveViaPairCode());
        }
    }
}
