package com.divurve.engine.fx;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link FxRateCoverage} — 커버리지 값 객체 (이슈 #116).
 */
@DisplayName("FxRateCoverage")
class FxRateCoverageTest {

    private static final LocalDate FROM = LocalDate.of(2026, 9, 1);
    private static final LocalDate TO = LocalDate.of(2026, 9, 4);

    @Test
    @DisplayName("gaps 는 방어 복사된다 — 호출자가 나중에 바꿔도 판정이 흔들리지 않는다")
    void gaps는_방어복사된다() {
        List<FxRateCoverage.Gap> mutable = new ArrayList<>();
        mutable.add(new FxRateCoverage.Gap(FROM, FROM, 1));

        FxRateCoverage coverage = new FxRateCoverage(FROM, TO, 4, 3, mutable);
        mutable.clear();

        assertThat(coverage.gaps()).hasSize(1);
        assertThat(coverage.complete()).isFalse();
    }

    @Test
    @DisplayName("null 은 거부한다")
    void null을_거부한다() {
        assertThatThrownBy(() -> new FxRateCoverage(null, TO, 0, 0, List.of()))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new FxRateCoverage(FROM, null, 0, 0, List.of()))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new FxRateCoverage(FROM, TO, 0, 0, null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new FxRateCoverage.Gap(null, TO, 1))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new FxRateCoverage.Gap(FROM, null, 1))
                .isInstanceOf(NullPointerException.class);
    }
}
