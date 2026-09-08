package com.divurve.domain.event;

import com.divurve.domain.event.entity.EconEvent;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

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
}
