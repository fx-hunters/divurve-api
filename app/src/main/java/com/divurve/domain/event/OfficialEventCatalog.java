package com.divurve.domain.event;

import java.util.List;

/**
 * 공식 캘린더에서 가져올 지표 목록과 그 표시 제목·중요도 (이슈 #163, 이슈 #187 로 키 변경).
 *
 * <p><b>왜 표인가</b> — 공식 캘린더는 중요도를 주지 않는다. 그렇다고 "이 지표가 얼마나
 * 중요한가"를 LLM 에 물으면 등급을 생성하게 된다(CLAUDE.md §1). 사람이 정한 고정 표만이
 * 답이며, 표에 없는 지표는 아예 조회하지 않는다.
 *
 * <p><b>이름이 아니라 식별자로 찾는다</b>(이슈 #187). 예전에는 캘린더가 준 이름을 문자열로
 * 대조했는데, 이름이 한 글자만 달라도 그 지표가 조용히 사라졌다 — 에러가 아니라 매칭 실패라
 * 아무도 눈치채지 못한다. 실제로 산업생산의 FRED 이름이
 * {@code G.17 Industrial Production and Capacity Utilization} 이라 접두사 때문에 처음부터
 * 한 번도 매칭되지 않고 있었다.
 *
 * <p>{@code impact} 는 1(낮음)~3(높음)으로 {@code econ_events} 의 CHECK 제약과 같은 규약이며,
 * {@link EconEventVocabulary} 가 조회 시 {@code High}/{@code Medium}/{@code Low} 로 옮긴다.
 */
public final class OfficialEventCatalog {

    /**
     * FRED {@code release_id} 기준. 환율에 실제로 영향을 주는 지표만 담는다 — 캘린더 전체를
     * 옮기면 화면이 소음이 된다(FRED 에 등록된 릴리스는 331개다).
     *
     * <p>번호는 2026-09-09 에 FRED 릴리스 목록에서 확인했다. 괄호 안은 그때의 캘린더 이름이며,
     * 조회는 번호로 하므로 이름이 바뀌어도 영향이 없다 — 사람이 표를 읽을 때의 참고다.
     */
    private static final List<Entry> FRED_RELEASES = List.of(
            new Entry("10", "미국 소비자물가지수(CPI) 발표", (short) 3),
            new Entry("50", "미국 고용상황(비농업 취업자수) 발표", (short) 3),
            new Entry("53", "미국 국내총생산(GDP) 발표", (short) 3),
            new Entry("54", "미국 개인소득·소비지출(PCE 물가) 발표", (short) 3),
            new Entry("46", "미국 생산자물가지수(PPI) 발표", (short) 2),
            new Entry("9", "미국 소매판매 발표", (short) 2),
            new Entry("192", "미국 구인·이직 보고서(JOLTS) 발표", (short) 2),
            new Entry("13", "미국 산업생산 발표", (short) 1));

    private OfficialEventCatalog() {
    }

    /**
     * 가져올 지표 전체. 적재 유스케이스가 이 목록을 돌며 하나씩 조회한다.
     *
     * @return 지표 목록 (불변)
     */
    public static List<Entry> entries() {
        return FRED_RELEASES;
    }

    /**
     * 지표 하나.
     *
     * @param calendarKey 캘린더 식별자 (FRED {@code release_id})
     * @param title       화면·API 에 나갈 제목
     * @param impact      1(낮음)~3(높음)
     */
    public record Entry(String calendarKey, String title, short impact) {
    }
}
