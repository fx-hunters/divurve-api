package com.divurve.domain.fx;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.divurve.domain.RepositoryTestBase;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * {@link FxRateAbsenceRepository} — 고시 부재 확정 (이슈 #116, 마이그레이션 V23).
 *
 * <p>{@code ON CONFLICT} 네이티브 쿼리라 실제 Postgres 로만 검증된다. 백필과 스케줄러가 같은
 * 날짜를 동시에 확정해도 충돌하지 않아야 한다 — {@code FxRateRepository.upsert} 와 같은 이유다.
 */
@DisplayName("FxRateAbsenceRepository")
class FxRateAbsenceRepositoryTest extends RepositoryTestBase {

    private static final String PAIR = "USDKRW";
    private static final LocalDate DATE = LocalDate.of(2026, 9, 3);
    private static final Instant CONFIRMED_AT = Instant.parse("2026-09-07T00:30:00Z");

    @Autowired
    private FxRateAbsenceRepository repository;

    @Autowired
    private EntityManager entityManager;

    private int confirm(String pairCode, LocalDate date, Instant confirmedAt) {
        int affected = repository.confirm(pairCode, date, "mid", confirmedAt);
        entityManager.flush();
        entityManager.clear();
        return affected;
    }

    private List<LocalDate> confirmedDates(LocalDate from, LocalDate to) {
        return repository.findConfirmedDates(PAIR, "mid", from, to);
    }

    @Test
    @DisplayName("같은 키를 다시 확정하면 행이 늘지 않고 확인 시각만 갱신된다")
    void confirm_UpdatesInsteadOfDuplicating() {
        confirm(PAIR, DATE, CONFIRMED_AT);
        confirm(PAIR, DATE, CONFIRMED_AT.plusSeconds(3600));

        assertThat(confirmedDates(DATE, DATE)).containsExactly(DATE);
        assertThat(repository.findAll()).singleElement().satisfies(absence -> {
            assertThat(absence.getId().getPairCode().trim()).isEqualTo(PAIR);
            assertThat(absence.getId().getRateType()).isEqualTo("mid");
            assertThat(absence.getConfirmedAt()).isEqualTo(CONFIRMED_AT.plusSeconds(3600));
        });
    }

    @Test
    @DisplayName("구간 조회는 그 구간의 날짜만 돌려준다")
    void findConfirmedDates_FiltersRange() {
        confirm(PAIR, LocalDate.of(2026, 8, 1), CONFIRMED_AT);
        confirm(PAIR, DATE, CONFIRMED_AT);

        assertThat(confirmedDates(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30)))
                .containsExactly(DATE);
    }

    @Test
    @DisplayName("뒤늦은 고시가 들어오면 부재 확정을 걷어낸다 — 두 표에 같은 키가 남으면 안 된다")
    void release_RemovesConfirmation() {
        confirm(PAIR, DATE, CONFIRMED_AT);

        assertThat(repository.release(PAIR, DATE, "mid")).isEqualTo(1);
        entityManager.flush();
        entityManager.clear();

        assertThat(confirmedDates(DATE, DATE)).isEmpty();
        assertThat(repository.release(PAIR, DATE, "mid")).isZero();
    }

    @Test
    @DisplayName("마스터에 없는 통화쌍은 FK 가 막는다")
    void unknownPair_Rejected() {
        assertThatThrownBy(() -> confirm("CADKRW", DATE, CONFIRMED_AT))
                .isInstanceOf(Exception.class);
    }

    @Test
    @DisplayName("허용되지 않는 rate_type 은 CHECK 가 막는다")
    void invalidRateType_Rejected() {
        assertThatThrownBy(() -> {
            repository.confirm(PAIR, DATE, "spot", CONFIRMED_AT);
            entityManager.flush();
        }).isInstanceOf(Exception.class);
    }
}
