package com.divurve.domain.event;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 공식 캘린더가 부르는 이름 → 표시 제목·중요도 대조표 (이슈 #163).
 *
 * <p><b>왜 표인가</b> — 공식 캘린더는 중요도를 주지 않는다. 그렇다고 "이 지표가 얼마나
 * 중요한가"를 LLM 에 물으면 등급을 생성하게 된다(CLAUDE.md §1). 사람이 정한 고정 표만이
 * 답이며, 표에 없는 이름은 <b>저장하지 않는다</b> — 모르는 것을 중간값으로 채우지 않는다.
 *
 * <p>표시 제목을 함께 두는 이유는 화면 어휘를 한국어로 맞추기 위해서다. 원문 이름은
 * {@code source_url} 과 함께 추적 가능하므로 출처가 흐려지지 않는다.
 *
 * <p>{@code impact} 는 1(낮음)~3(높음)으로 {@code econ_events} 의 CHECK 제약과 같은 규약이며,
 * {@link EconEventVocabulary} 가 조회 시 {@code High}/{@code Medium}/{@code Low} 로 옮긴다.
 */
public final class OfficialEventCatalog {

    /**
     * FRED {@code release_name} 기준. 값은 (표시 제목, 중요도)다.
     *
     * <p>환율에 실제로 영향을 주는 지표만 담는다 — 캘린더 전체를 옮기면 화면이 소음이 된다.
     * 이름은 FRED 가 쓰는 문자열과 정확히 같아야 한다. 틀린 이름은 조회에서 걸리지 않아
     * 그 일정이 조용히 빠질 뿐, 잘못된 데이터가 들어가지는 않는다.
     */
    private static final Map<String, Entry> FRED_RELEASES = new LinkedHashMap<>();

    static {
        FRED_RELEASES.put("Consumer Price Index",
                new Entry("미국 소비자물가지수(CPI) 발표", (short) 3));
        FRED_RELEASES.put("Employment Situation",
                new Entry("미국 고용상황(비농업 취업자수) 발표", (short) 3));
        FRED_RELEASES.put("Gross Domestic Product",
                new Entry("미국 국내총생산(GDP) 발표", (short) 3));
        FRED_RELEASES.put("Personal Income and Outlays",
                new Entry("미국 개인소득·소비지출(PCE 물가) 발표", (short) 3));
        FRED_RELEASES.put("Producer Price Index",
                new Entry("미국 생산자물가지수(PPI) 발표", (short) 2));
        FRED_RELEASES.put("Advance Monthly Sales for Retail and Food Services",
                new Entry("미국 소매판매 발표", (short) 2));
        FRED_RELEASES.put("Job Openings and Labor Turnover Survey",
                new Entry("미국 구인·이직 보고서(JOLTS) 발표", (short) 2));
        FRED_RELEASES.put("Industrial Production and Capacity Utilization",
                new Entry("미국 산업생산 발표", (short) 1));
    }

    private OfficialEventCatalog() {
    }

    /**
     * 캘린더 이름으로 표시 제목과 중요도를 찾는다.
     *
     * @param calendarName 공식 캘린더가 쓰는 이름 (FRED {@code release_name})
     * @return 표에 있으면 항목, 없으면 빈 값 — 호출자는 이 경우 저장하지 않는다
     */
    public static Optional<Entry> find(String calendarName) {
        if (calendarName == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(FRED_RELEASES.get(calendarName.trim()));
    }

    /**
     * 표시 제목과 중요도.
     *
     * @param title  화면·API 에 나갈 제목
     * @param impact 1(낮음)~3(높음)
     */
    public record Entry(String title, short impact) {
    }
}
