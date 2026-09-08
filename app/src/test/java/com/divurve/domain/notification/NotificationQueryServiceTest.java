package com.divurve.domain.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.divurve.domain.notification.NotificationQueryService.NotificationView;
import com.divurve.domain.notification.entity.Notification;
import com.divurve.domain.user.entity.User;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * {@link NotificationQueryService} 단위 테스트 — 조회 전용 유스케이스라 매핑·빈 상태만 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class NotificationQueryServiceTest {

    private final UUID ownerId = UUID.randomUUID();
    private final User owner = User.create("me@divurve.com", "나", "hash");

    @Mock
    private NotificationRepository notificationRepository;

    @InjectMocks
    private NotificationQueryService service;

    @Test
    void 소유자의_알림을_최신순으로_뷰로_옮긴다() {
        Notification notification = Notification.create(owner, "target_zone", "제목", "본문");
        when(notificationRepository.findByOwner_IdOrderByCreatedAtDesc(ownerId))
                .thenReturn(List.of(notification));

        List<NotificationView> views = service.listNotifications(ownerId);

        assertThat(views).hasSize(1);
        NotificationView view = views.get(0);
        assertThat(view.kind()).isEqualTo("target_zone");
        assertThat(view.title()).isEqualTo("제목");
        assertThat(view.body()).isEqualTo("본문");
        assertThat(view.read()).isFalse();
    }

    @Test
    void 알림이_없으면_빈_목록이다_오류가_아니다() {
        when(notificationRepository.findByOwner_IdOrderByCreatedAtDesc(ownerId)).thenReturn(List.of());

        assertThat(service.listNotifications(ownerId)).isEmpty();
    }

    @Test
    void ownerId가_null이면_예외를_던진다() {
        org.junit.jupiter.api.Assertions.assertThrows(
                NullPointerException.class, () -> service.listNotifications(null));
    }
}
