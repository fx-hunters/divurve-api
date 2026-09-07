package com.divurve.engine.fx;

import com.divurve.engine.EngineComponent;
import com.divurve.engine.planner.BusinessDayCalendar;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 영업일 달력과 보유 날짜를 대조해 빠진 구간을 찾는다 (이슈 #116 1단계).
 *
 * <p>순수 함수다 — DB 도 ECOS 도 모른다. 호출자가 "이 구간에서 우리가 아는 날짜" 를 모아서 넘기면
 * 그 여집합을 영업일 기준으로 돌려줄 뿐이다. 판정 규칙이 계산 결과를 좌우하므로(구멍이 있다고
 * 잘못 판정하면 읽기 경로가 영원히 ECOS 로 가고, 없다고 잘못 판정하면 부분 데이터로 5년 백분위를
 * 계산한다) engine 에 두고 테스트로 고정한다.
 *
 * <p><b>"아는 날짜" 는 값이 있는 날 + 부재가 확정된 날이다.</b> 둘을 합쳐 넘기는 것이 이 설계의
 * 핵심이다. 공휴일은 ECOS 가 고시하지 않으므로 {@code fx_rates} 에 영원히 행이 없는데,
 * {@link BusinessDayCalendar} 는 주말만 제외하므로 값만 대조하면 공휴일이 매년 구멍으로 잡힌다.
 * 부재 확정({@code fx_rate_absences})을 함께 넘기면 "휴일이라 없는 날" 과 "배치가 빠뜨린 날" 이
 * 갈린다 — 이슈 #116 이 읽기 경로 전환보다 구멍 탐지를 먼저 요구한 이유가 그것이다.
 */
@EngineComponent
public class FxRateGapDetector {

    private final BusinessDayCalendar businessDayCalendar;

    public FxRateGapDetector(BusinessDayCalendar businessDayCalendar) {
        this.businessDayCalendar =
                Objects.requireNonNull(businessDayCalendar, "businessDayCalendar");
    }

    /**
     * 구간의 커버리지를 판정한다.
     *
     * <p>{@code knownDates} 에 구간 밖 날짜나 주말이 섞여 있어도 무시한다 — 호출자가 조회 구간을
     * 넉넉히 잡아 넘기는 것이 자연스럽고, 그 여유분 때문에 판정이 달라지면 안 된다.
     *
     * @param from       시작일 (포함)
     * @param to         끝일 (포함)
     * @param knownDates 값이 있거나 부재가 확정된 날짜
     * @return 커버리지
     * @throws IllegalArgumentException {@code from} 이 {@code to} 보다 늦은 경우
     */
    public FxRateCoverage detect(LocalDate from, LocalDate to, Collection<LocalDate> knownDates) {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        Objects.requireNonNull(knownDates, "knownDates");
        if (from.isAfter(to)) {
            throw new IllegalArgumentException(
                    "시작일이 끝일보다 늦습니다: %s > %s".formatted(from, to));
        }

        Set<LocalDate> known = new HashSet<>(knownDates);
        List<FxRateCoverage.Gap> gaps = new ArrayList<>();
        int expected = 0;
        int covered = 0;

        LocalDate gapStart = null;
        LocalDate gapEnd = null;
        int gapDays = 0;

        for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1)) {
            if (!businessDayCalendar.isBusinessDay(date)) {
                continue;
            }
            expected++;
            if (known.contains(date)) {
                covered++;
                if (gapStart != null) {
                    gaps.add(new FxRateCoverage.Gap(gapStart, gapEnd, gapDays));
                    gapStart = null;
                    gapDays = 0;
                }
                continue;
            }
            // 주말을 사이에 두고 떨어진 영업일은 한 구간으로 묶는다 — 금요일과 월요일이 함께
            // 비었다면 원인은 하나(주말을 낀 배치 정지)일 가능성이 높고, 재조회도 한 번이면 된다.
            if (gapStart == null) {
                gapStart = date;
            }
            gapEnd = date;
            gapDays++;
        }
        if (gapStart != null) {
            gaps.add(new FxRateCoverage.Gap(gapStart, gapEnd, gapDays));
        }

        return new FxRateCoverage(from, to, expected, covered, gaps);
    }
}
