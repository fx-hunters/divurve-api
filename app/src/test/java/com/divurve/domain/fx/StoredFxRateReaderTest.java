package com.divurve.domain.fx;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.divurve.domain.master.CurrencyPairRepository;
import com.divurve.engine.fx.FxRateCoverage;
import com.divurve.support.FxRateFixtures;
import com.divurve.support.MasterFixtures;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link StoredFxRateReader} — 계산 경로의 저장분 우선 조회 (이슈 #116 2단계).
 *
 * <p>고정하는 것은 하나로 압축된다: <b>완전할 때만 값을 준다</b>. 구멍이 있는데 값을 주면
 * 부분 데이터로 5년 백분위를 계산하게 되고, 그 오류는 예외도 화면도 남기지 않는다.
 *
 * <p>기준 시각은 2026-09-07(월), 직전 영업일은 2026-09-04(금)이다.
 */
@DisplayName("StoredFxRateReader")
class StoredFxRateReaderTest {

    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-09-07T00:30:00Z"), ZoneOffset.UTC);
    private static final String PAIR = "USDKRW";
    private static final LocalDate SETTLED_THROUGH = LocalDate.of(2026, 9, 4);

    private CurrencyPairRepository currencyPairRepository;
    private FxRateRepository fxRateRepository;
    private FxRateGapService gapService;
    private StoredFxRateReader reader;

    @BeforeEach
    void setUp() {
        currencyPairRepository = mock(CurrencyPairRepository.class);
        fxRateRepository = mock(FxRateRepository.class);
        gapService = mock(FxRateGapService.class);
        when(gapService.settledThrough()).thenReturn(SETTLED_THROUGH);
        reader = new StoredFxRateReader(
                currencyPairRepository, fxRateRepository, gapService, CLOCK);
    }

    private static LocalDate d(int day) {
        return LocalDate.of(2026, 9, day);
    }

    private void storedPair() {
        when(currencyPairRepository.findById(PAIR))
                .thenReturn(Optional.of(MasterFixtures.pair(PAIR, "USD", "KRW", true, null)));
    }

    private void coverage(boolean complete) {
        List<FxRateCoverage.Gap> gaps = complete
                ? List.of()
                : List.of(new FxRateCoverage.Gap(d(2), d(3), 2));
        when(gapService.coverageOf(anyString(), any(), any()))
                .thenReturn(new FxRateCoverage(d(1), SETTLED_THROUGH, 4, complete ? 4 : 2, gaps));
    }

    // ── 최신값 ────────────────────────────────────

    @Test
    @DisplayName("최근 구간이 완전하면 저장된 최신값을 준다 — 정규화 없이 그대로")
    void 완전하면_최신값을_준다() {
        storedPair();
        coverage(true);
        when(fxRateRepository
                .findTopByIdPairCodeAndIdRateTypeAndIdQuoteDateLessThanEqualOrderByIdQuoteDateDesc(
                        PAIR, "mid", d(7)))
                .thenReturn(Optional.of(FxRateFixtures.rate(PAIR, d(4), "1380.500000")));

        assertThat(reader.latestPerUnitRate("USD"))
                .contains(new java.math.BigDecimal("1380.500000"));
    }

    @Test
    @DisplayName("최근 구간에 구멍이 있으면 빈 값 — 호출자는 ECOS 로 간다")
    void 구멍이_있으면_비운다() {
        storedPair();
        coverage(false);

        assertThat(reader.latestPerUnitRate("USD")).isEmpty();
        verifyNoInteractions(fxRateRepository);
    }

    @Test
    @DisplayName("완전해도 행이 없으면 빈 값이다")
    void 행이_없으면_비운다() {
        storedPair();
        coverage(true);
        when(fxRateRepository
                .findTopByIdPairCodeAndIdRateTypeAndIdQuoteDateLessThanEqualOrderByIdQuoteDateDesc(
                        anyString(), anyString(), any()))
                .thenReturn(Optional.empty());

        assertThat(reader.latestPerUnitRate("USD")).isEmpty();
    }

    @Test
    @DisplayName("최신값 판정 구간은 직전 영업일 기준 한 달이다")
    void 최신값_판정_구간() {
        storedPair();
        coverage(true);
        when(fxRateRepository
                .findTopByIdPairCodeAndIdRateTypeAndIdQuoteDateLessThanEqualOrderByIdQuoteDateDesc(
                        anyString(), anyString(), any()))
                .thenReturn(Optional.empty());

        reader.latestPerUnitRate("USD");

        org.mockito.Mockito.verify(gapService).coverageOf(
                PAIR,
                SETTLED_THROUGH.minusDays(StoredFxRateReader.LATEST_CHECK_CALENDAR_DAYS),
                SETTLED_THROUGH);
    }

    // ── 시계열 ────────────────────────────────────

    @Test
    @DisplayName("구간이 완전하면 시계열을 준다 — 오늘 값이 있으면 함께 담는다")
    void 완전하면_시계열을_준다() {
        storedPair();
        coverage(true);
        when(fxRateRepository
                .findByIdPairCodeAndIdRateTypeAndIdQuoteDateBetweenOrderByIdQuoteDateAsc(
                        eq(PAIR), eq("mid"), eq(d(1)), eq(d(7))))
                .thenReturn(List.of(
                        FxRateFixtures.rate(PAIR, d(4), "1380.500000"),
                        FxRateFixtures.rate(PAIR, d(7), "1381.250000")));

        assertThat(reader.perUnitSeries("USD", d(7), 6)).hasValueSatisfying(points ->
                assertThat(points).containsExactly(
                        new StoredFxRates.Point(d(4), new java.math.BigDecimal("1380.500000")),
                        new StoredFxRates.Point(d(7), new java.math.BigDecimal("1381.250000"))));
    }

    @Test
    @DisplayName("완전성은 직전 영업일까지만 본다 — 오늘 값이 아직 없는 것은 구멍이 아니다")
    void 완전성은_직전_영업일까지_본다() {
        storedPair();
        coverage(true);
        when(fxRateRepository
                .findByIdPairCodeAndIdRateTypeAndIdQuoteDateBetweenOrderByIdQuoteDateAsc(
                        anyString(), anyString(), any(), any()))
                .thenReturn(List.of());

        reader.perUnitSeries("USD", d(7), 6);

        org.mockito.Mockito.verify(gapService).coverageOf(PAIR, d(1), SETTLED_THROUGH);
    }

    @Test
    @DisplayName("구간에 구멍이 있으면 빈 값 — 부분 데이터를 이어 붙이지 않는다")
    void 시계열에_구멍이_있으면_비운다() {
        storedPair();
        coverage(false);

        assertThat(reader.perUnitSeries("USD", d(7), 6)).isEmpty();
    }

    @Test
    @DisplayName("구간 전체가 미확정이면 판정할 근거가 없어 신뢰하지 않는다")
    void 전부_미확정이면_비운다() {
        storedPair();

        // 9/7 하루만 요청 — 확정 가능한 마지막 날(9/4)보다 뒤라 볼 것이 없다.
        assertThat(reader.perUnitSeries("USD", d(7), 1)).isEmpty();
        verifyNoInteractions(fxRateRepository);
    }

    @Test
    @DisplayName("요청 끝일이 직전 영업일보다 앞서면 요청 끝일까지만 본다 — 없는 미래를 판정하지 않는다")
    void 요청_끝일이_더_이르면_그때까지_본다() {
        storedPair();
        coverage(true);
        when(fxRateRepository
                .findByIdPairCodeAndIdRateTypeAndIdQuoteDateBetweenOrderByIdQuoteDateAsc(
                        anyString(), anyString(), any(), any()))
                .thenReturn(List.of());

        reader.perUnitSeries("USD", d(3), 2);

        org.mockito.Mockito.verify(gapService).coverageOf(PAIR, d(1), d(3));
    }

    // ── 대상 판정 ────────────────────────────────────

    @Test
    @DisplayName("저장 대상이 아닌 통화는 저장분을 보지 않는다 — 유도 쌍은 fx_rates 에 행이 없다")
    void 저장_대상이_아니면_비운다() {
        when(currencyPairRepository.findById("GBPKRW")).thenReturn(Optional.empty());
        assertThat(reader.latestPerUnitRate("GBP")).isEmpty();

        when(currencyPairRepository.findById("USDKRW")).thenReturn(
                Optional.of(MasterFixtures.pair(PAIR, "USD", "KRW", false, "EURKRW")));
        assertThat(reader.latestPerUnitRate("USD")).isEmpty();
        assertThat(reader.perUnitSeries("USD", d(7), 6)).isEmpty();
    }

    @Test
    @DisplayName("잘못된 인자를 거부한다")
    void 잘못된_인자를_거부한다() {
        assertThatThrownBy(() -> reader.latestPerUnitRate(null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> reader.perUnitSeries(null, d(7), 6))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> reader.perUnitSeries("USD", null, 6))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> reader.perUnitSeries("USD", d(7), 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("lookbackCalendarDays");
    }

    @Test
    @DisplayName("null 협력자를 거부한다")
    void null_협력자를_거부한다() {
        assertThatThrownBy(() ->
                new StoredFxRateReader(null, fxRateRepository, gapService, CLOCK))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() ->
                new StoredFxRateReader(currencyPairRepository, null, gapService, CLOCK))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() ->
                new StoredFxRateReader(currencyPairRepository, fxRateRepository, null, CLOCK))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() ->
                new StoredFxRateReader(currencyPairRepository, fxRateRepository, gapService, null))
                .isInstanceOf(NullPointerException.class);
    }
}
