package com.divurve.engine.fx;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * 한 구간의 환율 관측 커버리지 (이슈 #116 1단계).
 *
 * <p>"이 구간을 믿고 계산해도 되는가" 하나만 답한다. {@link #complete()} 가 거짓이면 부분 데이터라는
 * 뜻이고, 부분 데이터로 5년 변동성 백분위를 계산하면 값이 조용히 틀린다 — 그래서 읽기 경로는
 * 이 값을 보고 DB 를 쓸지 ECOS 로 갈지 정한다.
 *
 * <p>구멍을 날짜 하나하나가 아니라 <b>연속 구간</b>으로 묶어 담는 이유는 두 가지다. 재조회가
 * 구간 단위라 그대로 요청으로 쓸 수 있고, 사람이 볼 때도 "3월 2일~3월 6일이 통째로 비었다" 가
 * 날짜 5개보다 원인에 가깝다.
 *
 * @param from                시작일 (포함)
 * @param to                  끝일 (포함)
 * @param expectedBusinessDays 구간 안의 영업일 수
 * @param coveredBusinessDays 그중 값이나 부재 확정이 있는 날 수
 * @param gaps                빠진 연속 구간 (날짜 오름차순)
 */
public record FxRateCoverage(
        LocalDate from,
        LocalDate to,
        int expectedBusinessDays,
        int coveredBusinessDays,
        List<Gap> gaps) {

    public FxRateCoverage {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        Objects.requireNonNull(gaps, "gaps");
        gaps = List.copyOf(gaps);
    }

    /** 구멍이 하나도 없는가. 읽기 경로가 DB 를 신뢰하는 유일한 조건이다. */
    public boolean complete() {
        return gaps.isEmpty();
    }

    /** 빠진 영업일 수. */
    public int missingBusinessDays() {
        return expectedBusinessDays - coveredBusinessDays;
    }

    /**
     * 커버리지 비율 (0.0~1.0).
     *
     * <p>영업일이 하나도 없는 구간(주말만으로 이뤄진 구간)은 1.0 이다 — 채울 것이 없으므로
     * 완전하다. 0으로 나누지 않기 위한 예외 처리가 아니라 정의 그대로다.
     */
    public double coverageRatio() {
        if (expectedBusinessDays == 0) {
            return 1.0;
        }
        return (double) coveredBusinessDays / expectedBusinessDays;
    }

    /**
     * 빠진 연속 구간 하나.
     *
     * @param from         구간의 첫 영업일
     * @param to           구간의 마지막 영업일
     * @param businessDays 구간 안의 빠진 영업일 수
     */
    public record Gap(LocalDate from, LocalDate to, int businessDays) {

        public Gap {
            Objects.requireNonNull(from, "from");
            Objects.requireNonNull(to, "to");
        }
    }
}
