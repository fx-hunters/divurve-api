package com.divurve.domain.master;

import com.divurve.common.architecture.UseCase;
import com.divurve.common.exception.InvalidRequestException;
import com.divurve.common.exception.NotFoundException;
import com.divurve.domain.master.entity.Currency;
import com.divurve.domain.master.entity.CurrencyPair;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.transaction.annotation.Transactional;

/**
 * 마스터 데이터 조회 유스케이스 (이슈 #11, 이슈 #111 로 DB 전환).
 *
 * <p>통화 표시 규칙은 {@code CurrencyMaster} 하드코딩 상수였다가 {@code currencies} 표로 옮겨졌다.
 * 은행 환전 조건({@code BankFxTermsMaster})은 아직 상수다 — ERD 의 {@code banks}·{@code bank_fx_terms}
 * 는 별도 단계이고, 함께 옮기면 플래너 비용계산까지 범위가 번진다.
 *
 * <p><b>캐시를 두는 이유</b> — 계획을 한 번 계산할 때마다 통화 표시 규칙을 읽는다
 * ({@code PlanRateContextProvider}·{@code PlanStepExecutionService}). 상수였을 때는 공짜였던 조회가
 * DB 왕복이 되면 계산 경로마다 비용이 붙는다. 이 표는 마이그레이션으로만 바뀌므로 캐시가 낡지 않는다.
 *
 * <p>여기서는 조회만 한다 — 계산 로직은 없다.
 */
@UseCase
public class MasterDataService {

    private final CurrencyRepository currencyRepository;
    private final CurrencyPairRepository currencyPairRepository;

    public MasterDataService(
            CurrencyRepository currencyRepository, CurrencyPairRepository currencyPairRepository) {
        this.currencyRepository = Objects.requireNonNull(currencyRepository, "currencyRepository");
        this.currencyPairRepository =
                Objects.requireNonNull(currencyPairRepository, "currencyPairRepository");
    }

    /**
     * 목표를 세울 수 있는 외화의 표시 규칙을 표시 순서대로 반환한다 (자국 통화 제외).
     *
     * <p>{@code is_supported=false} 인 통화도 포함한다 — {@code GET /api/v1/currencies} 의 기존 응답
     * 계약을 이 변경으로 바꾸지 않기 위해서다. 미지원 통화를 목록에서 빼는 것은 프론트에 보이는
     * 동작 변경이므로 이슈 #95 에서 따로 결정한다.
     */
    @Cacheable(cacheNames = "currency-master", key = "'foreign'")
    @Transactional(readOnly = true)
    public List<CurrencyView> listCurrencies() {
        return currencyRepository.findByHomeCurrencyFalseOrderBySortOrderAsc().stream()
                .map(MasterDataService::toView)
                .toList();
    }

    /** 자국 통화를 포함한 통화 마스터 전체 (관리자 조회용). */
    @Cacheable(cacheNames = "currency-master", key = "'all'")
    @Transactional(readOnly = true)
    public List<CurrencyView> listAllCurrencies() {
        return currencyRepository.findAllByOrderBySortOrderAsc().stream()
                .map(MasterDataService::toView)
                .toList();
    }

    /** 통화쌍 마스터 전체 (관리자 조회·통화쌍 선택지 구성용). */
    @Cacheable(cacheNames = "currency-master", key = "'pairs'")
    @Transactional(readOnly = true)
    public List<CurrencyPairView> listCurrencyPairs() {
        return currencyPairRepository.findAllByOrderByPairCodeAsc().stream()
                .map(MasterDataService::toView)
                .toList();
    }

    /**
     * 통화 하나의 표시 규칙을 찾는다. 계산 경로가 호가 단위·소수 자릿수를 얻는 통로다.
     *
     * @throws InvalidRequestException 마스터에 없는 통화코드인 경우
     */
    public CurrencyView requireCurrency(String currencyCode) {
        return findCurrency(currencyCode)
                .orElseThrow(() -> new InvalidRequestException(
                        "지원하지 않는 통화입니다: " + currencyCode, "currency_code"));
    }

    /** 통화 하나의 표시 규칙. 없으면 비어 있다. 자국 통화도 찾을 수 있다. */
    public Optional<CurrencyView> findCurrency(String currencyCode) {
        return listAllCurrencies().stream()
                .filter(currency -> currency.currencyCode().equals(currencyCode))
                .findFirst();
    }

    /**
     * 은행의 통화·채널별 환전 조건을 반환한다.
     *
     * @throws NotFoundException 마스터에 등록되지 않은 은행 코드인 경우
     */
    public FxTerms getFxTerms(String bankCode) {
        if (!BankFxTermsMaster.contains(bankCode)) {
            throw new NotFoundException("환전 조건을 제공하지 않는 은행 코드입니다: " + bankCode);
        }
        return new FxTerms(bankCode, BankFxTermsMaster.termsOf(bankCode));
    }

    private static CurrencyView toView(Currency currency) {
        return new CurrencyView(
                currency.getCurrencyCode(),
                currency.getNameKo(),
                currency.getSymbol(),
                currency.getMinorUnits(),
                currency.getQuoteUnit(),
                currency.getUsdSide(),
                currency.isHomeCurrency(),
                currency.isSupported(),
                currency.getSupportNote(),
                currency.getColorToken(),
                currency.getSortOrder());
    }

    private static CurrencyPairView toView(CurrencyPair pair) {
        return new CurrencyPairView(
                pair.getPairCode(),
                pair.getBaseCurrencyCode(),
                pair.getQuoteCurrencyCode(),
                pair.isStored(),
                pair.getDeriveViaPairCode());
    }

    /**
     * 통화 마스터 조회 결과. JPA 엔티티를 그대로 캐시·반환하지 않기 위한 불변 스냅샷이다 —
     * 엔티티를 캐시에 담으면 영속성 컨텍스트 밖에서 살아 있는 관리 대상 객체가 공유된다.
     *
     * @param currencyCode   ISO 4217 통화 코드
     * @param nameKo         한국어 표기
     * @param symbol         통화 기호
     * @param minorUnits     소수 자릿수 (JPY·KRW 0, 대부분 2)
     * @param quoteUnit      호가 단위 (JPY 는 100)
     * @param usdSide        삼각환산 방향 — {@code self}/{@code base}/{@code quote}/{@code none}
     * @param homeCurrency   자국 통화인가 (KRW 하나뿐)
     * @param supported      환율을 실제로 조달할 수 있는가
     * @param supportNote    미지원 사유. 지원 통화면 보통 {@code null}
     * @param colorToken     프론트 색상 디자인 토큰
     * @param sortOrder      표시 순서
     */
    public record CurrencyView(
            String currencyCode,
            String nameKo,
            String symbol,
            short minorUnits,
            short quoteUnit,
            String usdSide,
            boolean homeCurrency,
            boolean supported,
            String supportNote,
            String colorToken,
            short sortOrder) {
    }

    /**
     * 통화쌍 마스터 조회 결과.
     *
     * @param pairCode           통화쌍 6자리 (예 {@code USDKRW})
     * @param baseCurrencyCode   기준 통화
     * @param quoteCurrencyCode  표시 통화
     * @param stored             환율을 직접 적재하는 쌍인가
     * @param deriveViaPairCode  유도 경로. 저장 쌍이면 {@code null}
     */
    public record CurrencyPairView(
            String pairCode,
            String baseCurrencyCode,
            String quoteCurrencyCode,
            boolean stored,
            String deriveViaPairCode) {
    }

    /**
     * 은행별 환전 조건 조회 결과.
     *
     * @param bankCode 금융기관 표준코드
     * @param terms    통화·채널별 조건
     */
    public record FxTerms(String bankCode, List<BankFxTermsMaster.Term> terms) {
    }
}
