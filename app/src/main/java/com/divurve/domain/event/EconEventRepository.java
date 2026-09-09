package com.divurve.domain.event;

import com.divurve.domain.event.entity.EconEvent;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.time.Instant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/**
 * 경제 일정 접근 리포지토리. Spring Data JPA 가 런타임 구현을 주입한다.
 * (event_date, region, title) 중복 적재 방지 확인용 존재 여부 조회를 노출한다(이슈 #74).
 */
public interface EconEventRepository extends JpaRepository<EconEvent, UUID> {

    /** 같은 사건이 이미 적재되어 있는지 확인한다 (ERD {@code uq_events_date_region_title}). */
    boolean existsByEventDateAndRegionAndTitle(LocalDate eventDate, String region, String title);

    /**
     * 조회 구간에 걸린 일정을 날짜 오름차순으로 읽는다 (이슈 #162).
     *
     * <p>구간을 <b>쿼리로 내린다</b> — 예전 읽기 경로는 90일치를 전부 받아 서비스에서 다시 걸렀다.
     * 홈은 14일, {@code GET /events} 는 90일로 창이 다르므로 필요한 만큼만 읽는다.
     *
     * @param from 시작일 (포함)
     * @param to   종료일 (포함)
     * @return 일정 목록 (날짜 오름차순). {@code idx_events_date} 를 탄다
     */
    List<EconEvent> findByEventDateBetweenOrderByEventDateAsc(LocalDate from, LocalDate to);

    /**
     * 유니크 키로 한 건을 읽는다 (이슈 #163).
     *
     * <p>{@code existsBy...} 는 "있는지" 만 답해서 <b>어느 출처의 행인지</b> 를 모른다. 공식
     * 파서가 낮은 신뢰도의 행을 승격하려면 행 자체가 필요하다.
     */
    Optional<EconEvent> findByEventDateAndRegionAndTitle(
            LocalDate eventDate, String region, String title);

    /**
     * 출처별 적재 현황 (이슈 #176). 관리자 화면의 "마지막 갱신" 자리를 채운다.
     *
     * <p>출처를 <b>섞지 않고</b> 나눠 센다 — 공식 파서·AI 추출·시연용 예시를 한 숫자로 합치면
     * 신뢰도 혼합 금지(#74 제약 4)가 무의미해진다. 시연용 시드가 아직 남아 있는지도 여기서 보인다.
     */
    @Query("select e.sourceKind as sourceKind, count(e) as total, "
            + "max(e.fetchedAt) as lastFetchedAt, max(e.eventDate) as lastEventDate "
            + "from EconEvent e group by e.sourceKind")
    List<SourceKindStat> statsBySourceKind();

    /**
     * 출처 하나의 적재 현황.
     *
     * <p>{@code lastEventDate} 는 <b>얼마나 앞까지 채워져 있는가</b>를 말한다 — 적재가 언제
     * 돌았는지({@code lastFetchedAt})와 다른 질문이다. 배치가 오늘 돌았어도 캘린더가 일정을
     * 주지 않았다면 앞이 비어 있을 수 있다.
     */
    interface SourceKindStat {
        String getSourceKind();

        long getTotal();

        Instant getLastFetchedAt();

        LocalDate getLastEventDate();
    }
}
