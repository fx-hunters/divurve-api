package com.divurve.engine.fx;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import com.divurve.engine.planner.BusinessDayCalendar;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link FxRateGapDetector} — 영업일 대조 (이슈 #116 1단계).
 *
 * <p>여기서 고정하는 것이 읽기 경로 전환의 안전장치다. 구멍이 있는데 없다고 판정하면 부분
 * 데이터로 5년 백분위를 계산하고, 없는데 있다고 판정하면 저장분을 영영 쓰지 못한다.
 *
 * <p>날짜는 2026-09 를 쓴다 — 9/5·9/6 이 주말, 9/7 이 월요일이다.
 */
@DisplayName("FxRateGapDetector")
class FxRateGapDetectorTest {

    private final FxRateGapDetector detector = new FxRateGapDetector(new BusinessDayCalendar());

    private static LocalDate d(int day) {
        return LocalDate.of(2026, 9, day);
    }

    @Test
    @DisplayName("영업일이 모두 채워져 있으면 구멍이 없다")
    void 모두_채워지면_완전하다() {
        FxRateCoverage coverage = detector.detect(
                d(1), d(4), Set.of(d(1), d(2), d(3), d(4)));

        assertThat(coverage.complete()).isTrue();
        assertThat(coverage.gaps()).isEmpty();
        assertThat(coverage.expectedBusinessDays()).isEqualTo(4);
        assertThat(coverage.coveredBusinessDays()).isEqualTo(4);
        assertThat(coverage.missingBusinessDays()).isZero();
        assertThat(coverage.coverageRatio()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("주말은 애초에 세지 않는다 — 토·일이 비어 있어도 구멍이 아니다")
    void 주말은_세지_않는다() {
        FxRateCoverage coverage = detector.detect(d(4), d(7), Set.of(d(4), d(7)));

        assertThat(coverage.expectedBusinessDays()).isEqualTo(2);
        assertThat(coverage.complete()).isTrue();
    }

    @Test
    @DisplayName("빠진 영업일은 연속 구간으로 묶인다")
    void 빠진_날은_구간으로_묶인다() {
        FxRateCoverage coverage = detector.detect(d(1), d(4), Set.of(d(1), d(4)));

        assertThat(coverage.gaps()).containsExactly(new FxRateCoverage.Gap(d(2), d(3), 2));
        assertThat(coverage.missingBusinessDays()).isEqualTo(2);
        assertThat(coverage.complete()).isFalse();
        assertThat(coverage.coverageRatio()).isEqualTo(0.5, within(1e-9));
    }

    @Test
    @DisplayName("떨어진 구멍은 따로 잡힌다")
    void 떨어진_구멍은_따로_잡힌다() {
        FxRateCoverage coverage = detector.detect(
                d(1), d(11), Set.of(d(1), d(3), d(4), d(9), d(10), d(11)));

        assertThat(coverage.gaps()).containsExactly(
                new FxRateCoverage.Gap(d(2), d(2), 1),
                new FxRateCoverage.Gap(d(7), d(8), 2));
    }

    @Test
    @DisplayName("주말을 사이에 둔 금·월 결측은 한 구간으로 묶인다 — 원인이 하나다")
    void 주말을_낀_결측은_한_구간이다() {
        FxRateCoverage coverage = detector.detect(
                d(3), d(8), Set.of(d(3)));

        assertThat(coverage.gaps()).containsExactly(new FxRateCoverage.Gap(d(4), d(8), 3));
    }

    @Test
    @DisplayName("구간 끝까지 비어 있으면 마지막 구멍도 닫힌다")
    void 끝까지_빈_구간도_닫힌다() {
        FxRateCoverage coverage = detector.detect(d(1), d(4), Set.of(d(1)));

        assertThat(coverage.gaps()).containsExactly(new FxRateCoverage.Gap(d(2), d(4), 3));
    }

    @Test
    @DisplayName("아는 날짜가 하나도 없으면 구간 전체가 하나의 구멍이다")
    void 아무것도_없으면_전체가_구멍이다() {
        FxRateCoverage coverage = detector.detect(d(1), d(4), List.of());

        assertThat(coverage.gaps()).containsExactly(new FxRateCoverage.Gap(d(1), d(4), 4));
        assertThat(coverage.coveredBusinessDays()).isZero();
        assertThat(coverage.coverageRatio()).isZero();
    }

    @Test
    @DisplayName("구간 밖 날짜와 주말 날짜가 섞여 있어도 판정이 달라지지 않는다")
    void 구간_밖_날짜는_무시한다() {
        FxRateCoverage coverage = detector.detect(
                d(2), d(3), Set.of(d(1), d(2), d(3), d(5), d(30)));

        assertThat(coverage.expectedBusinessDays()).isEqualTo(2);
        assertThat(coverage.coveredBusinessDays()).isEqualTo(2);
        assertThat(coverage.complete()).isTrue();
    }

    @Test
    @DisplayName("영업일이 하나도 없는 구간은 완전하다 — 채울 것이 없다")
    void 주말만_있는_구간은_완전하다() {
        FxRateCoverage coverage = detector.detect(d(5), d(6), List.of());

        assertThat(coverage.expectedBusinessDays()).isZero();
        assertThat(coverage.complete()).isTrue();
        assertThat(coverage.coverageRatio()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("하루짜리 구간도 판정한다")
    void 하루짜리_구간() {
        assertThat(detector.detect(d(4), d(4), Set.of(d(4))).complete()).isTrue();
        assertThat(detector.detect(d(4), d(4), List.of()).gaps())
                .containsExactly(new FxRateCoverage.Gap(d(4), d(4), 1));
    }

    @Test
    @DisplayName("시작일이 끝일보다 늦으면 거부한다")
    void 뒤집힌_구간은_거부한다() {
        assertThatThrownBy(() -> detector.detect(d(4), d(1), List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("시작일이 끝일보다 늦습니다");
    }

    @Test
    @DisplayName("null 인자를 거부한다")
    void null_인자를_거부한다() {
        assertThatThrownBy(() -> new FxRateGapDetector(null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> detector.detect(null, d(4), List.of()))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> detector.detect(d(1), null, List.of()))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> detector.detect(d(1), d(4), null))
                .isInstanceOf(NullPointerException.class);
    }
}
