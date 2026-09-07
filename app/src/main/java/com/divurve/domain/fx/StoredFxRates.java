package com.divurve.domain.fx;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * 계산 경로가 저장된 환율을 읽는 창구 (이슈 #116 2단계).
 *
 * <p>돌려주는 값은 <b>언제나 1통화 단위당 원화</b>다 — {@code fx_rates} 에 이미 그 단위로
 * 적재돼 있으므로 읽는 쪽은 정규화하지 않는다. 여기서 다시 {@code QuoteUnitNormalizer} 를 태우면
 * JPY 가 100분의 1이 된다.
 *
 * <p><b>빈 값은 "없다" 가 아니라 "믿을 수 없다" 다.</b> 구간에 구멍이 있으면 부분 데이터로
 * 계산하게 두지 않고 빈 값을 돌려 호출자가 ECOS 로 가게 한다. 부분 데이터로 5년 변동성 백분위를
 * 계산하면 값이 조용히 틀리고, 그것이 이슈 #116 이 구멍 탐지를 읽기 경로 전환보다 먼저 요구한
 * 이유다.
 */
public interface StoredFxRates {

    /**
     * 저장분을 쓰지 않는 구현 — 언제나 ECOS 로 간다.
     *
     * <p>이슈 #116 이전의 읽기 경로가 정확히 이 동작이었다. ECOS 경로 자체를 고정하는 테스트가
     * DB 를 끼우지 않고도 그 경로를 그대로 검사할 수 있게 남겨 둔다.
     */
    StoredFxRates NONE = new StoredFxRates() {

        @Override
        public Optional<BigDecimal> latestPerUnitRate(String currencyCode) {
            return Optional.empty();
        }

        @Override
        public Optional<List<Point>> perUnitSeries(
                String currencyCode, LocalDate endDate, int lookbackCalendarDays) {
            return Optional.empty();
        }
    };

    /**
     * 통화의 최신 1단위당 원화 환율.
     *
     * @param currencyCode 통화코드 (예 {@code USD})
     * @return 신뢰할 수 있을 때만 값. 저장 대상이 아니거나 최근 구간에 구멍이 있으면 빈 값
     */
    Optional<BigDecimal> latestPerUnitRate(String currencyCode);

    /**
     * 통화의 과거 1단위당 원화 환율 시계열.
     *
     * @param currencyCode         통화코드
     * @param endDate              조회 끝 날짜 (포함)
     * @param lookbackCalendarDays 거슬러 올라갈 달력일 수 (영업일이 아니다)
     * @return 구간이 완전할 때만 값 (날짜 오름차순). 하나라도 구멍이 있으면 빈 값
     */
    Optional<List<Point>> perUnitSeries(
            String currencyCode, LocalDate endDate, int lookbackCalendarDays);

    /**
     * 관측 하나.
     *
     * @param date        고시일
     * @param perUnitRate 1 외화당 원화
     */
    record Point(LocalDate date, BigDecimal perUnitRate) {
    }
}
