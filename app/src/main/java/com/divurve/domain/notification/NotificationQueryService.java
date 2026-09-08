package com.divurve.domain.notification;

import com.divurve.common.architecture.UseCase;
import com.divurve.domain.notification.entity.Notification;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

/**
 * 알림 조회 유스케이스 ({@code GET /notifications}, 이슈 #97).
 *
 * <p>이 서비스는 <b>조회만</b> 한다 — 알림을 만들거나 읽음 처리하는 경로는 이 이슈 밖이다
 * (지금 알림을 만드는 유일한 경로는 데모 시드다, {@code SampleDataSeeder} 참고).
 *
 * <p>알림이 없으면 빈 목록을 돌려준다. 실사용자는 알림이 없을 수 있으므로 이 빈 상태 응답은
 * 그대로 유효한 결과다(FR-CM-09) — 오류로 바꾸지 않는다.
 */
@UseCase
public class NotificationQueryService {

    private final NotificationRepository notificationRepository;

    public NotificationQueryService(NotificationRepository notificationRepository) {
        this.notificationRepository = Objects.requireNonNull(notificationRepository, "notificationRepository");
    }

    /**
     * 소유자의 알림을 최신순으로 조회한다.
     *
     * @param ownerId 조회 사용자
     * @return 알림 목록 (없으면 빈 목록)
     */
    @Transactional(readOnly = true)
    public List<NotificationView> listNotifications(UUID ownerId) {
        Objects.requireNonNull(ownerId, "ownerId");
        return notificationRepository.findByOwner_IdOrderByCreatedAtDesc(ownerId).stream()
                .map(NotificationView::from)
                .toList();
    }

    /**
     * 알림 한 건의 조회 전용 뷰.
     *
     * @param id        알림 id
     * @param kind      알림 종류 ({@link Notification#KINDS} 중 하나)
     * @param title     제목
     * @param body      본문
     * @param createdAt 생성 시각
     * @param read      읽음 여부
     */
    public record NotificationView(
            UUID id,
            String kind,
            String title,
            String body,
            Instant createdAt,
            boolean read) {

        private static NotificationView from(Notification notification) {
            return new NotificationView(
                    notification.getId(),
                    notification.getKind(),
                    notification.getTitle(),
                    notification.getBody(),
                    notification.getCreatedAt(),
                    notification.isRead());
        }
    }
}
