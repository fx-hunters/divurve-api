package com.divurve.domain.notification;

import com.divurve.domain.notification.entity.Notification;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 알림 접근 리포지토리. Spring Data JPA 가 런타임 구현을 주입한다.
 * 소유자 기준 필터(NFR-SE-02 계열)를 파생 쿼리로 노출한다 — 남의 알림은 조회되지 않는다.
 */
public interface NotificationRepository extends JpaRepository<Notification, UUID> {

    /** 소유자의 알림을 최신순으로 조회한다. */
    List<Notification> findByOwner_IdOrderByCreatedAtDesc(UUID ownerId);
}
