package com.divurve.domain.fx;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.divurve.domain.RepositoryTestBase;
import com.divurve.domain.fx.entity.FxRate;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * {@link FxRateRepository} — upsert 동작과 스키마 제약 (이슈 #111, 마이그레이션 V22).
 *
 * <p>upsert 는 {@code ON CONFLICT} 네이티브 쿼리라 실제 Postgres 로만 검증된다.
 * 스케줄러와 관리자 수동 갱신이 같은 날짜를 적재해도 충돌하지 않아야 한다.
 */
@DisplayName("FxRateRepository")
class FxRateRepositoryTest extends RepositoryTestBase {

    private static final String PAIR = "USDKRW";
    private static final LocalDate DATE = LocalDate.of(2026, 9, 4);
    private static final Instant FETCHED_AT = Instant.parse("2026-09-07T00:30:00Z");

    @Autowired
    private FxRateRepository fxRateRepository;

    @Autowired
    private EntityManager entityManager;

    private int upsert(String pairCode, LocalDate date, String rate, String source) {
        int affected = fxRateRepository.upsert(
                pairCode, date, "mid", new BigDecimal(rate), source, FETCHED_AT);
        entityManager.flush();
        entityManager.clear();
        return affected;
    }

    private List<FxRate> findSeries(LocalDate from, LocalDate to) {
        return fxRateRepository
                .findByIdPairCodeAndIdRateTypeAndIdQuoteDateBetweenOrderByIdQuoteDateAsc(
                        PAIR, "mid", from, to);
    }

    @Test
    @DisplayName("같은 키를 다시 넣으면 행이 늘지 않고 값만 갱신된다 — ECOS 정정을 반영한다")
    void upsert_UpdatesInsteadOfDuplicating() {
        upsert(PAIR, DATE, "1380.500000", "ECOS");
        upsert(PAIR, DATE, "1381.250000", "ECOS_REVISED");

        List<FxRate> found = findSeries(DATE, DATE);

        assertThat(found).singleElement().satisfies(rate -> {
            assertThat(rate.getRate()).isEqualByComparingTo("1381.25");
            assertThat(rate.getDataSource()).isEqualTo("ECOS_REVISED");
            assertThat(rate.getId().getPairCode().trim()).isEqualTo(PAIR);
            assertThat(rate.getId().getRateType()).isEqualTo("mid");
            assertThat(rate.getFetchedAt()).isEqualTo(FETCHED_AT);
        });
    }

    @Test
    @DisplayName("기간 조회는 오래된 순으로 나온다")
    void series_OrdersByQuoteDateAsc() {
        upsert(PAIR, LocalDate.of(2026, 9, 5), "1382.000000", "ECOS");
        upsert(PAIR, LocalDate.of(2026, 9, 3), "1379.000000", "ECOS");
        upsert(PAIR, DATE, "1380.500000", "ECOS");

        assertThat(findSeries(LocalDate.of(2026, 9, 3), LocalDate.of(2026, 9, 5)))
                .extracting(rate -> rate.getId().getQuoteDate())
                .containsExactly(
                        LocalDate.of(2026, 9, 3), DATE, LocalDate.of(2026, 9, 5));
    }

    @Test
    @DisplayName("구간 밖의 관측은 나오지 않는다")
    void series_ExcludesOutsideRange() {
        upsert(PAIR, LocalDate.of(2026, 8, 1), "1370.000000", "ECOS");

        assertThat(findSeries(DATE, DATE)).isEmpty();
    }

    @Test
    @DisplayName("마스터에 없는 통화쌍은 FK 가 막는다")
    void unknownPair_Rejected() {
        assertThatThrownBy(() -> upsert("CADKRW", DATE, "1000.000000", "ECOS"))
                .isInstanceOf(Exception.class);
    }

    @Test
    @DisplayName("허용되지 않는 rate_type 은 CHECK 가 막는다")
    void invalidRateType_Rejected() {
        assertThatThrownBy(() -> {
            fxRateRepository.upsert(
                    PAIR, DATE, "spot", new BigDecimal("1380.5"), "ECOS", FETCHED_AT);
            entityManager.flush();
        }).isInstanceOf(Exception.class);
    }

    @Test
    @DisplayName("0 이하 환율은 CHECK 가 막는다 — 삼각환산에서 0 나눗셈이 된다")
    void nonPositiveRate_Rejected() {
        assertThatThrownBy(() -> upsert(PAIR, DATE, "0.000000", "ECOS"))
                .isInstanceOf(Exception.class);
    }
}
