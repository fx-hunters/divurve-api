package com.divurve.domain.port;

import java.time.LocalDate;
import java.util.List;

/**
 * 공식 캘린더에서 확정 공표된 경제 일정을 공급하는 포트 (이슈 #163).
 *
 * <p><b>{@link RawArticleSource} 와 다른 경로다.</b> 그쪽은 구조가 없는 원문을 LLM 에 태워
 * 추출하는 입구이고({@code AI_EXTRACTED}), 이 포트는 이미 구조화된 공식 캘린더를 그대로
 * 읽는다({@code OFFICIAL_PARSER}). 정책회의·지표 발표 일정은 각 기관이 연 단위로 미리
 * 공표하므로 LLM 을 거칠 이유가 없다 — 토큰을 쓰고 환각 위험을 지면서 정확도는 더 낮다.
 *
 * <p><b>구현체는 중요도를 정하지 않는다.</b> 공식 캘린더는 중요도를 주지 않으며, 어댑터가
 * 그것을 지어내면 외부 어댑터가 등급을 생성하는 셈이 된다(CLAUDE.md §1). 등급은 domain 의
 * 고정 표가 정한다.
 */
public interface OfficialEventCalendarSource {

    /**
     * 구간에 걸린 공표 일정을 읽는다.
     *
     * @param from 시작일 (포함)
     * @param to   종료일 (포함)
     * @return 일정 목록. 비어 있을 수 있다
     */
    List<OfficialEvent> fetchScheduled(LocalDate from, LocalDate to);

    /**
     * 공식 캘린더가 공표한 일정 한 건.
     *
     * @param date      발표 예정일
     * @param region    지역 ({@code EconEventValidator} 허용 어휘)
     * @param name      캘린더가 부르는 이름. 표시명이 아니라 <b>대조 키</b>다 — domain 의 표가
     *                  이 이름으로 중요도와 표시 제목을 찾는다
     * @param sourceUrl 원문 URL. 실존해야 한다 — 지어내지 않는다(FR-CM-10)
     */
    record OfficialEvent(LocalDate date, String region, String name, String sourceUrl) {
    }
}
