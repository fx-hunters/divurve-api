package com.divurve.domain.fx;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.divurve.common.exception.InvalidRequestException;
import com.divurve.domain.master.CurrencyPairRepository;
import com.divurve.domain.port.FxRateHistoryProvider;
import com.divurve.engine.fx.FxRateGapDetector;
import com.divurve.engine.planner.BusinessDayCalendar;
import com.divurve.engine.weight.QuoteUnitNormalizer;
import com.divurve.support.MasterFixtures;
import java.math.BigDecimal;
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
 * {@link FxRateGapService} — 구멍 탐지와 구간 단위 백필 (이슈 #116 1단계).
 *
 * <p>고정하는 것은 넷이다.
 * <ul>
 *   <li>값과 부재 확정을 <b>합쳐서</b> 아는 날짜로 보는가 — 공휴일이 구멍으로 잡히면 안 된다</li>
 *   <li>재조회가 <b>구멍 구간만</b> 대상으로 하는가</li>
 *   <li>ECOS 도 답하지 못한 날을 부재로 확정하되 <b>오늘 이후는 건드리지 않는가</b></li>
 *   <li>조회 실패를 "고시 없음" 으로 굳히지 않는가 — 되돌릴 수 없는 실수다</li>
 * </ul>
 *
 * <p>기준 시각은 2026-09-07(월). 직전 영업일은 2026-09-04(금)이다.
 */
@DisplayName("FxRateGapService")
class FxRateGapServiceTest {

    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-09-07T00:30:00Z"), ZoneOffset.UTC);
    private static final String PAIR = "USDKRW";

    private CurrencyPairRepository currencyPairRepository;
    private FxRateRepository fxRateRepository;
    private FxRateAbsenceRepository fxRateAbsenceRepository;
    private FxRateHistoryProvider historyProvider;
    private FxRateGapService service;

    @BeforeEach
    void setUp() {
        currencyPairRepository = mock(CurrencyPairRepository.class);
        fxRateRepository = mock(FxRateRepository.class);
        fxRateAbsenceRepository = mock(FxRateAbsenceRepository.class);
        historyProvider = mock(FxRateHistoryProvider.class);
        service = new FxRateGapService(
                currencyPairRepository,
                fxRateRepository,
                fxRateAbsenceRepository,
                historyProvider,
                new FxRateGapDetector(new BusinessDayCalendar()),
                new BusinessDayCalendar(),
                new QuoteUnitNormalizer(),
                CLOCK);
    }

    private static LocalDate d(int day) {
        return LocalDate.of(2026, 9, day);
    }

    private void storedPair(String pairCode, String base) {
        when(currencyPairRepository.findById(pairCode))
                .thenReturn(Optional.of(MasterFixtures.pair(pairCode, base, "KRW", true, null)));
    }

    private void known(List<LocalDate> rates, List<LocalDate> absences) {
        when(fxRateRepository.findQuoteDates(anyString(), eq("mid"), any(), any()))
                .thenReturn(rates);
        when(fxRateAbsenceRepository.findConfirmedDates(anyString(), eq("mid"), any(), any()))
                .thenReturn(absences);
    }

    // ── 커버리지 조회 ────────────────────────────────────

    @Test
    @DisplayName("직전 영업일까지만 부재를 확정한다 — 월요일에는 금요일이 기준이다")
    void settledThrough_는_직전_영업일이다() {
        assertThat(service.settledThrough()).isEqualTo(d(4));
    }

    @Test
    @DisplayName("부재 확정된 날은 구멍이 아니다 — 공휴일이 매년 구멍으로 잡히는 것을 막는다")
    void 부재_확정은_구멍이_아니다() {
        storedPair(PAIR, "USD");
        known(List.of(d(1), d(2), d(4)), List.of(d(3)));

        FxRateGapService.PairCoverage coverage = service.coverage("USDKRW", d(1), d(4));

        assertThat(coverage.complete()).isTrue();
        assertThat(coverage.pairCode()).isEqualTo(PAIR);
        assertThat(coverage.rateType()).isEqualTo("mid");
        assertThat(coverage.expectedBusinessDays()).isEqualTo(4);
        assertThat(coverage.coveredBusinessDays()).isEqualTo(4);
        assertThat(coverage.missingBusinessDays()).isZero();
        assertThat(coverage.coverageRatio()).isEqualTo(1.0);
        assertThat(coverage.gaps()).isEmpty();
        assertThat(coverage.from()).isEqualTo(d(1));
        assertThat(coverage.to()).isEqualTo(d(4));
    }

    @Test
    @DisplayName("값도 부재 확정도 없는 영업일이 구멍이다")
    void 값도_부재도_없으면_구멍이다() {
        storedPair(PAIR, "USD");
        known(List.of(d(1), d(4)), List.of());

        FxRateGapService.PairCoverage coverage = service.coverage("USD_KRW", d(1), d(4));

        assertThat(coverage.complete()).isFalse();
        assertThat(coverage.gaps())
                .containsExactly(new FxRateGapService.Gap(d(2), d(3), 2));
    }

    @Test
    @DisplayName("저장 쌍 전부를 순회한다")
    void 저장_쌍_전부를_순회한다() {
        when(currencyPairRepository.findByStoredTrueOrderByPairCodeAsc()).thenReturn(List.of(
                MasterFixtures.pair("EURKRW", "EUR", "KRW", true, null),
                MasterFixtures.pair("USDKRW", "USD", "KRW", true, null)));
        known(List.of(d(1), d(2), d(3), d(4)), List.of());

        List<FxRateGapService.PairCoverage> coverages =
                service.coverageOfStoredPairs(d(1), d(4));

        assertThat(coverages).extracting(FxRateGapService.PairCoverage::pairCode)
                .containsExactly("EURKRW", "USDKRW");
    }

    @Test
    @DisplayName("마스터에 없는 쌍·유도 쌍은 400 이다")
    void 알_수_없는_쌍은_거부한다() {
        when(currencyPairRepository.findById("GBPKRW")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.coverage("GBPKRW", d(1), d(4)))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("지원하지 않는 통화쌍");

        when(currencyPairRepository.findById("USDJPY")).thenReturn(
                Optional.of(MasterFixtures.pair("USDJPY", "USD", "JPY", false, "USDKRW")));
        assertThatThrownBy(() -> service.coverage("USDJPY", d(1), d(4)))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("저장하지 않는 통화쌍");
    }

    @Test
    @DisplayName("뒤집히거나 너무 긴 기간은 400 이다")
    void 잘못된_기간은_거부한다() {
        assertThatThrownBy(() -> service.coverage(PAIR, d(4), d(1)))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("시작일이 끝일보다 늦습니다");
        assertThatThrownBy(() -> service.coverage(PAIR, d(1), d(1).plusDays(
                FxRateGapService.MAX_RANGE_DAYS + 1)))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("최대");
        assertThatThrownBy(() -> service.coverage(PAIR, null, d(4)))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> service.coverage(PAIR, d(1), null))
                .isInstanceOf(NullPointerException.class);
    }

    // ── 백필 ────────────────────────────────────

    @Test
    @DisplayName("이미 완전하면 ECOS 를 부르지 않는다")
    void 완전하면_조회하지_않는다() {
        storedPair(PAIR, "USD");
        known(List.of(d(1), d(2), d(3), d(4)), List.of());

        FxRateGapService.PairBackfill result = service.backfill(PAIR, d(1), d(4));

        assertThat(result.complete()).isTrue();
        assertThat(result.filled()).isZero();
        assertThat(result.confirmedAbsent()).isZero();
        assertThat(result.missingBefore()).isZero();
        assertThat(result.failureReason()).isNull();
        verifyNoInteractions(historyProvider);
    }

    @Test
    @DisplayName("구멍 구간만 다시 받아 값으로 채우고 부재 확정을 걷어낸다")
    void 구멍_구간만_다시_받는다() {
        storedPair(PAIR, "USD");
        when(fxRateRepository.findQuoteDates(anyString(), eq("mid"), any(), any()))
                .thenReturn(List.of(d(1), d(4)))
                .thenReturn(List.of(d(1), d(2), d(3), d(4)));
        when(fxRateAbsenceRepository.findConfirmedDates(anyString(), eq("mid"), any(), any()))
                .thenReturn(List.of());
        when(historyProvider.fetchHistorical(eq("USD_KRW"), eq(d(3)), anyInt())).thenReturn(List.of(
                new FxRateHistoryProvider.HistoryRateSnapshot(d(2), 1380.5),
                new FxRateHistoryProvider.HistoryRateSnapshot(d(3), 1381.25)));

        FxRateGapService.PairBackfill result = service.backfill(PAIR, d(1), d(4));

        // 구간 [9/2, 9/3] 만 요청한다 — 전체 재적재가 아니다.
        verify(historyProvider).fetchHistorical("USD_KRW", d(3), 1);
        verify(fxRateRepository).upsert(
                eq(PAIR), eq(d(2)), eq("mid"), eq(new BigDecimal("1380.500000")),
                eq("ECOS"), any());
        verify(fxRateAbsenceRepository).release(PAIR, d(2), "mid");
        verify(fxRateAbsenceRepository).release(PAIR, d(3), "mid");
        assertThat(result.filled()).isEqualTo(2);
        assertThat(result.confirmedAbsent()).isZero();
        assertThat(result.missingBefore()).isEqualTo(2);
        assertThat(result.missingAfter()).isZero();
        assertThat(result.complete()).isTrue();
        assertThat(result.remainingGaps()).isEmpty();
    }

    @Test
    @DisplayName("ECOS 도 값이 없는 영업일은 고시 부재로 확정한다 — 공휴일이 여기로 들어간다")
    void 값이_없으면_부재로_확정한다() {
        storedPair(PAIR, "USD");
        when(fxRateRepository.findQuoteDates(anyString(), eq("mid"), any(), any()))
                .thenReturn(List.of(d(1), d(4)));
        when(fxRateAbsenceRepository.findConfirmedDates(anyString(), eq("mid"), any(), any()))
                .thenReturn(List.of())
                .thenReturn(List.of(d(2), d(3)));
        when(historyProvider.fetchHistorical(anyString(), any(), anyInt())).thenReturn(List.of());

        FxRateGapService.PairBackfill result = service.backfill(PAIR, d(1), d(4));

        verify(fxRateAbsenceRepository).confirm(eq(PAIR), eq(d(2)), eq("mid"), any());
        verify(fxRateAbsenceRepository).confirm(eq(PAIR), eq(d(3)), eq("mid"), any());
        assertThat(result.confirmedAbsent()).isEqualTo(2);
        assertThat(result.filled()).isZero();
        assertThat(result.complete()).isTrue();
    }

    @Test
    @DisplayName("오늘 이후는 부재로 확정하지 않는다 — 아직 안 온 값을 없다고 굳히면 되돌릴 수 없다")
    void 오늘_이후는_확정하지_않는다() {
        storedPair(PAIR, "USD");
        known(List.of(), List.of());
        when(historyProvider.fetchHistorical(anyString(), any(), anyInt())).thenReturn(List.of());

        // 9/4(금)까지가 확정 가능 구간이다. 9/7(월)·9/8(화)은 건드리지 않는다.
        FxRateGapService.PairBackfill result = service.backfill(PAIR, d(3), d(8));

        verify(fxRateAbsenceRepository).confirm(eq(PAIR), eq(d(3)), eq("mid"), any());
        verify(fxRateAbsenceRepository).confirm(eq(PAIR), eq(d(4)), eq("mid"), any());
        verify(fxRateAbsenceRepository, never()).confirm(eq(PAIR), eq(d(7)), eq("mid"), any());
        verify(fxRateAbsenceRepository, never()).confirm(eq(PAIR), eq(d(8)), eq("mid"), any());
        assertThat(result.confirmedAbsent()).isEqualTo(2);
    }

    @Test
    @DisplayName("구멍 밖 날짜가 섞여 와도 그 구간만 반영한다")
    void 구멍_밖_응답은_무시한다() {
        storedPair(PAIR, "USD");
        known(List.of(d(1), d(4)), List.of());
        when(historyProvider.fetchHistorical(anyString(), any(), anyInt())).thenReturn(List.of(
                new FxRateHistoryProvider.HistoryRateSnapshot(d(1), 1379.0),
                new FxRateHistoryProvider.HistoryRateSnapshot(d(2), 1380.5),
                new FxRateHistoryProvider.HistoryRateSnapshot(d(4), 1383.0)));

        FxRateGapService.PairBackfill result = service.backfill(PAIR, d(1), d(4));

        verify(fxRateRepository).upsert(
                eq(PAIR), eq(d(2)), eq("mid"), any(), eq("ECOS"), any());
        verify(fxRateRepository, never()).upsert(
                eq(PAIR), eq(d(1)), anyString(), any(), anyString(), any());
        assertThat(result.filled()).isEqualTo(1);
    }

    @Test
    @DisplayName("어댑터가 null 을 주면 관측이 없는 것과 같게 다룬다")
    void null_응답은_빈_결과와_같다() {
        storedPair(PAIR, "USD");
        known(List.of(d(1), d(4)), List.of());
        when(historyProvider.fetchHistorical(anyString(), any(), anyInt())).thenReturn(null);

        FxRateGapService.PairBackfill result = service.backfill(PAIR, d(1), d(4));

        assertThat(result.filled()).isZero();
        assertThat(result.confirmedAbsent()).isEqualTo(2);
    }

    @Test
    @DisplayName("JPY 는 100엔 고시를 1단위로 접어 저장한다")
    void JPY는_1단위로_접는다() {
        storedPair("JPYKRW", "JPY");
        known(List.of(d(1), d(4)), List.of());
        when(historyProvider.fetchHistorical(eq("JPY_KRW"), any(), anyInt())).thenReturn(List.of(
                new FxRateHistoryProvider.HistoryRateSnapshot(d(2), 939.13)));

        service.backfill("JPYKRW", d(1), d(4));

        verify(fxRateRepository).upsert(
                eq("JPYKRW"), eq(d(2)), eq("mid"), eq(new BigDecimal("9.391300")),
                eq("ECOS"), any());
    }

    @Test
    @DisplayName("조회가 실패하면 사유를 값으로 남기고 부재를 확정하지 않는다")
    void 조회_실패는_부재로_굳히지_않는다() {
        storedPair(PAIR, "USD");
        known(List.of(d(1), d(4)), List.of());
        when(historyProvider.fetchHistorical(anyString(), any(), anyInt()))
                .thenThrow(new IllegalStateException("ECOS 응답 없음"));

        FxRateGapService.PairBackfill result = service.backfill(PAIR, d(1), d(4));

        assertThat(result.failureReason()).isEqualTo("ECOS 응답 없음");
        assertThat(result.complete()).isFalse();
        assertThat(result.remainingGaps())
                .containsExactly(new FxRateGapService.Gap(d(2), d(3), 2));
        verify(fxRateAbsenceRepository, never()).confirm(anyString(), any(), anyString(), any());
    }

    @Test
    @DisplayName("한 쌍이 실패해도 나머지는 계속 돈다")
    void 한_쌍의_실패가_나머지를_막지_않는다() {
        when(currencyPairRepository.findByStoredTrueOrderByPairCodeAsc()).thenReturn(List.of(
                MasterFixtures.pair("EURKRW", "EUR", "KRW", true, null),
                MasterFixtures.pair("USDKRW", "USD", "KRW", true, null)));
        known(List.of(d(1), d(4)), List.of());
        when(historyProvider.fetchHistorical(eq("EUR_KRW"), any(), anyInt()))
                .thenThrow(new IllegalStateException("ECOS 응답 없음"));
        when(historyProvider.fetchHistorical(eq("USD_KRW"), any(), anyInt())).thenReturn(List.of(
                new FxRateHistoryProvider.HistoryRateSnapshot(d(2), 1380.5)));

        FxRateGapService.BackfillReport report = service.backfillStoredPairs(d(1), d(4));

        assertThat(report.pairs()).hasSize(2);
        assertThat(report.hasFailure()).isTrue();
        assertThat(report.complete()).isFalse();
        assertThat(report.totalFilled()).isEqualTo(1);
        assertThat(report.totalConfirmedAbsent()).isEqualTo(1);
        assertThat(report.backfilledAt()).isEqualTo(Instant.now(CLOCK));
    }

    @Test
    @DisplayName("null 협력자를 거부한다")
    void null_협력자를_거부한다() {
        FxRateGapDetector detector = new FxRateGapDetector(new BusinessDayCalendar());
        BusinessDayCalendar calendar = new BusinessDayCalendar();
        QuoteUnitNormalizer normalizer = new QuoteUnitNormalizer();

        assertThatThrownBy(() -> new FxRateGapService(null, fxRateRepository,
                fxRateAbsenceRepository, historyProvider, detector, calendar, normalizer, CLOCK))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new FxRateGapService(currencyPairRepository, null,
                fxRateAbsenceRepository, historyProvider, detector, calendar, normalizer, CLOCK))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new FxRateGapService(currencyPairRepository, fxRateRepository,
                null, historyProvider, detector, calendar, normalizer, CLOCK))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new FxRateGapService(currencyPairRepository, fxRateRepository,
                fxRateAbsenceRepository, null, detector, calendar, normalizer, CLOCK))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new FxRateGapService(currencyPairRepository, fxRateRepository,
                fxRateAbsenceRepository, historyProvider, null, calendar, normalizer, CLOCK))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new FxRateGapService(currencyPairRepository, fxRateRepository,
                fxRateAbsenceRepository, historyProvider, detector, null, normalizer, CLOCK))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new FxRateGapService(currencyPairRepository, fxRateRepository,
                fxRateAbsenceRepository, historyProvider, detector, calendar, null, CLOCK))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new FxRateGapService(currencyPairRepository, fxRateRepository,
                fxRateAbsenceRepository, historyProvider, detector, calendar, normalizer, null))
                .isInstanceOf(NullPointerException.class);
    }
}
