package com.divurve.domain.port;

import java.time.LocalDate;
import java.util.List;

/**
 * 공식 캘린더에서 확정 공표된 경제 일정을 공급하는 포트 (이슈 #163, 이슈 #187 로 조회 단위 변경).
 *
 * <p><b>{@link RawArticleSource} 와 다른 경로다.</b> 그쪽은 구조가 없는 원문을 LLM 에 태워
 * 추출하는 입구이고({@code AI_EXTRACTED}), 이 포트는 이미 구조화된 공식 캘린더를 그대로
 * 읽는다({@code OFFICIAL_PARSER}). 정책회의·지표 발표 일정은 각 기관이 연 단위로 미리
 * 공표하므로 LLM 을 거칠 이유가 없다.
 *
 * <p><b>지표 하나씩 조회한다</b>(이슈 #187). 예전에는 캘린더 전체를 한 번에 받아 도메인이
 * 걸렀는데, 그러면 관심 없는 지표까지 수천 건을 받아 응답 상한에 잘렸다 — 실제로 요청 구간의
 * 5분의 1만 들어왔다. 필요한 것만 지목해 받으면 잘릴 일이 없고 버리는 양도 없다.
 *
 * <p><b>구현체는 중요도도 표시 제목도 정하지 않는다.</b> 공식 캘린더는 중요도를 주지 않으며,
 * 어댑터가 그것을 지어내면 외부 어댑터가 등급을 생성하는 셈이 된다(CLAUDE.md §1). 둘 다
 * domain 의 고정 표가 정한다.
 */
public interface OfficialEventCalendarSource {

    /**
     * 지표 하나의 공표 일정을 구간으로 읽는다.
     *
     * @param calendarKey 캘린더가 이 지표를 부르는 식별자. FRED 에서는 {@code release_id} 다 —
     *                    이름이 아니라 식별자를 쓰는 이유는 이름이 바뀌어도 깨지지 않기 위해서다
     * @param from        시작일 (포함)
     * @param to          종료일 (포함)
     * @return 발표 예정일 목록. 비어 있을 수 있다
     */
    List<OfficialEvent> fetchScheduled(String calendarKey, LocalDate from, LocalDate to);

    /**
     * 공식 캘린더가 공표한 일정 한 건.
     *
     * <p>제목이 없다 — 단건 조회 응답은 날짜만 주고, 표시 제목은 domain 의 표가 붙인다.
     *
     * @param date      발표 예정일
     * @param region    지역 ({@code EconEventValidator} 허용 어휘)
     * @param sourceUrl 원문 URL. 실존해야 한다 — 지어내지 않는다(FR-CM-10)
     */
    record OfficialEvent(LocalDate date, String region, String sourceUrl) {
    }
}
