package com.divurve.domain.fx;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.divurve.domain.fx.FxRateStatusService.StatusResult;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * {@link FxRateStatusService} — 마지막 갱신 시각 조회 (이슈 #128).
 *
 * <p>핵심은 <b>전체 값을 쌍별 값에서 접는다</b>는 것이다. DB 에 같은 질문을 두 번 묻지 않으며,
 * 두 최댓값은 서로 다른 행에서 와도 된다 — 적재 시각과 고시일은 다른 질문에 답한다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("FxRateStatusService")
class FxRateStatusServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-09T05:00:00Z");

    @Mock
    private FxRateRepository fxRateRepository;

    private FxRateStatusService service;

    @BeforeEach
    void setUp() {
        service = new FxRateStatusService(fxRateRepository, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    /** {@link FxRateRepository.PairFreshness} 프로젝션 대역. */
    private record Freshness(String pairCode, Instant lastFetchedAt, LocalDate lastQuoteDate)
            implements FxRateRepository.PairFreshness {

        @Override
        public String getPairCode() {
            return pairCode;
        }

        @Override
        public Instant getLastFetchedAt() {
            return lastFetchedAt;
        }

        @Override
        public LocalDate getLastQuoteDate() {
            return lastQuoteDate;
        }
    }

    @Test
    @DisplayName("쌍별 값을 그대로 옮기고 전체는 그중 최댓값이다")
    void status_FoldsPairsIntoOverall() {
        when(fxRateRepository.freshnessByPair()).thenReturn(List.of(
                new Freshness("EURKRW",
                        Instant.parse("2026-09-08T00:31:07Z"), LocalDate.of(2026, 9, 4)),
                new Freshness("USDKRW",
                        Instant.parse("2026-09-09T00:30:11Z"), LocalDate.of(2026, 9, 5))));

        StatusResult result = service.status();

        assertThat(result.lastFetchedAt()).isEqualTo(Instant.parse("2026-09-09T00:30:11Z"));
        assertThat(result.lastQuoteDate()).isEqualTo(LocalDate.of(2026, 9, 5));
        assertThat(result.pairs())
                .extracting(FxRateStatusService.PairStatus::pairCode)
                .containsExactly("EURKRW", "USDKRW");
        assertThat(result.pairs().get(0).lastFetchedAt())
                .isEqualTo(Instant.parse("2026-09-08T00:31:07Z"));
        assertThat(result.pairs().get(0).lastQuoteDate()).isEqualTo(LocalDate.of(2026, 9, 4));
        assertThat(result.checkedAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("적재 시각과 고시일의 최댓값은 다른 쌍에서 와도 된다 — 다른 질문에 답한다")
    void status_MaximaComeFromDifferentPairs() {
        when(fxRateRepository.freshnessByPair()).thenReturn(List.of(
                new Freshness("USDKRW",
                        Instant.parse("2026-09-09T00:30:00Z"), LocalDate.of(2026, 9, 3)),
                new Freshness("JPYKRW",
                        Instant.parse("2026-09-07T00:30:00Z"), LocalDate.of(2026, 9, 5))));

        StatusResult result = service.status();

        assertThat(result.lastFetchedAt()).isEqualTo(Instant.parse("2026-09-09T00:30:00Z"));
        assertThat(result.lastQuoteDate()).isEqualTo(LocalDate.of(2026, 9, 5));
    }

    @Test
    @DisplayName("적재가 한 번도 없으면 전체가 null 이다 — 0 이나 지금 시각으로 채우지 않는다")
    void status_EmptyWhenNothingIngested() {
        when(fxRateRepository.freshnessByPair()).thenReturn(List.of());

        StatusResult result = service.status();

        assertThat(result.lastFetchedAt()).isNull();
        assertThat(result.lastQuoteDate()).isNull();
        assertThat(result.pairs()).isEmpty();
        assertThat(result.checkedAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("값이 빈 쌍이 섞여도 나머지에서 최댓값을 고른다")
    void status_SkipsNullsWhenFolding() {
        when(fxRateRepository.freshnessByPair()).thenReturn(List.of(
                new Freshness("USDKRW", null, null),
                new Freshness("EURKRW",
                        Instant.parse("2026-09-08T00:31:07Z"), LocalDate.of(2026, 9, 4))));

        StatusResult result = service.status();

        assertThat(result.lastFetchedAt()).isEqualTo(Instant.parse("2026-09-08T00:31:07Z"));
        assertThat(result.lastQuoteDate()).isEqualTo(LocalDate.of(2026, 9, 4));
        assertThat(result.pairs().get(0).lastFetchedAt()).isNull();
    }

    @Test
    @DisplayName("모든 쌍이 값을 못 내면 전체도 null 이다")
    void status_NullWhenEveryPairIsEmpty() {
        when(fxRateRepository.freshnessByPair()).thenReturn(List.of(
                new Freshness("USDKRW", null, null)));

        StatusResult result = service.status();

        assertThat(result.lastFetchedAt()).isNull();
        assertThat(result.lastQuoteDate()).isNull();
        assertThat(result.pairs()).hasSize(1);
    }

    @Test
    @DisplayName("결과의 쌍 목록은 넘겨준 뒤 바꿔도 흔들리지 않는다")
    void statusResult_CopiesPairs() {
        List<FxRateStatusService.PairStatus> mutable = new ArrayList<>();
        mutable.add(new FxRateStatusService.PairStatus("USDKRW", NOW, LocalDate.of(2026, 9, 5)));

        StatusResult result = new StatusResult(NOW, LocalDate.of(2026, 9, 5), mutable, NOW);
        mutable.clear();

        assertThat(result.pairs()).hasSize(1);
        assertThatThrownBy(() -> result.pairs().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("의존성은 생성 시점에 확인한다")
    void constructor_RejectsNulls() {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

        assertThatThrownBy(() -> new FxRateStatusService(null, clock))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("fxRateRepository");
        assertThatThrownBy(() -> new FxRateStatusService(fxRateRepository, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("clock");
    }
}
