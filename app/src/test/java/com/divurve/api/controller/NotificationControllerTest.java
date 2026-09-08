package com.divurve.api.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.divurve.api.dto.notifications.NotificationsResponse;
import com.divurve.common.response.ApiResponse;
import com.divurve.domain.notification.NotificationQueryService;
import com.divurve.domain.notification.NotificationQueryService.NotificationView;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * {@link NotificationController} 매핑 검증.
 *
 * <p>"인증 컨텍스트가 없으면 401" 은 이제 컨트롤러가 아니라
 * {@code CurrentUserArgumentResolver} 의 책임이다 (이슈 #50) — 해당 검증은
 * {@code CurrentUserArgumentResolverTest} 에 한 벌로 모여 있다.
 * 컨트롤러가 {@code @CurrentUser} 파라미터를 받는 이상, 미인증 요청은 여기까지 도달하지 못한다.
 */
@ExtendWith(MockitoExtension.class)
class NotificationControllerTest {

    private final UUID userId = UUID.randomUUID();

    @Mock
    private NotificationQueryService notificationQueryService;

    private NotificationController controller() {
        return new NotificationController(notificationQueryService);
    }

    @Test
    void getNotifications_은_알림이_없으면_빈_배열을_돌려준다() {
        when(notificationQueryService.listNotifications(userId)).thenReturn(List.of());

        ApiResponse<NotificationsResponse> response = controller().getNotifications(userId);

        assertThat(response.data().notifications()).isEmpty();
    }

    @Test
    void getNotifications_은_조회한_알림을_그대로_옮긴다() {
        Instant createdAt = Instant.parse("2026-09-08T00:00:00Z");
        UUID notificationId = UUID.randomUUID();
        when(notificationQueryService.listNotifications(userId)).thenReturn(List.of(
                new NotificationView(notificationId, "target_zone", "제목", "본문", createdAt, false)));

        NotificationsResponse response = controller().getNotifications(userId).data();

        assertThat(response.notifications()).hasSize(1);
        NotificationsResponse.NotificationDto dto = response.notifications().get(0);
        assertThat(dto.id()).isEqualTo(notificationId);
        assertThat(dto.kind()).isEqualTo("target_zone");
        assertThat(dto.title()).isEqualTo("제목");
        assertThat(dto.body()).isEqualTo("본문");
        assertThat(dto.createdAt()).isEqualTo(createdAt);
        assertThat(dto.isRead()).isFalse();
    }

    /**
     * 응답 키를 못 박는다 — 이 엔드포인트는 지금까지 항상 빈 배열만 내보내 프론트가 필드명에
     * 의존할 수 없었고, 값이 실제로 나가기 시작하는 지금이 이름을 DB 컬럼에 맞출 마지막 시점이다
     * (이슈 #97). {@code type}/{@code message} → {@code kind}/{@code body} 로 바꿨고
     * {@code isRead} 는 전역 SNAKE_CASE 전략이 {@code is_read} 로 직렬화한다 — 컬럼명과 같다.
     * 앞으로 이 키가 조용히 바뀌면 프론트가 깨지므로 테스트로 고정한다.
     */
    @Test
    void getNotifications_응답_키는_DB_컬럼명과_같다() {
        Instant createdAt = Instant.parse("2026-09-08T00:00:00Z");
        when(notificationQueryService.listNotifications(userId)).thenReturn(List.of(
                new NotificationView(UUID.randomUUID(), "target_zone", "제목", "본문", createdAt, false)));
        ObjectMapper objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);

        JsonNode json = objectMapper.valueToTree(controller().getNotifications(userId).data());

        JsonNode notification = json.get("notifications").get(0);
        assertThat(notification.fieldNames()).toIterable()
                .containsExactlyInAnyOrder("id", "kind", "title", "body", "created_at", "is_read");
        assertThat(notification.get("kind").asText()).isEqualTo("target_zone");
        assertThat(notification.get("body").asText()).isEqualTo("본문");
        assertThat(notification.get("is_read").asBoolean()).isFalse();
    }

    @Test
    void getNotifications_은_조회_사용자_기준으로_위임한다() {
        when(notificationQueryService.listNotifications(userId)).thenReturn(List.of());

        controller().getNotifications(userId);

        verify(notificationQueryService).listNotifications(userId);
    }

    @Test
    void getNotifications_은_응답을_ApiResponse로_래핑한다() {
        when(notificationQueryService.listNotifications(userId)).thenReturn(List.of());

        ApiResponse<NotificationsResponse> response = controller().getNotifications(userId);

        assertThat(response.data()).isNotNull();
        assertThat(response.meta()).isNotNull();
    }
}
