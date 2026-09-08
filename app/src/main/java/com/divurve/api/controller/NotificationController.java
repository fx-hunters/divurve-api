package com.divurve.api.controller;

import com.divurve.api.config.auth.CurrentUser;
import com.divurve.api.dto.notifications.NotificationsResponse;
import com.divurve.common.architecture.WebAdapter;
import com.divurve.common.response.ApiResponse;
import com.divurve.domain.notification.NotificationQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Objects;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 알림 엔드포인트 (이슈 #21, #97). 사용자의 알림 목록을 조회한다.
 * 발송(적재)·읽음 처리 유스케이스는 이 이슈 밖이다 — 지금은 조회만 한다.
 */
@WebAdapter
@RestController
@RequestMapping("/api/v1/notifications")
@Tag(name = "Notifications", description = "사용자 알림")
public class NotificationController {

    private final NotificationQueryService notificationQueryService;

    public NotificationController(NotificationQueryService notificationQueryService) {
        this.notificationQueryService = Objects.requireNonNull(notificationQueryService, "notificationQueryService");
    }

    @Operation(
            summary = "알림 목록 조회",
            description = "본인의 알림을 최신순으로 반환한다. 알림이 없으면 200 + 빈 배열이다(FR-CM-09) — "
                    + "실사용자는 알림이 없을 수 있으므로 이 빈 상태 자체가 유효한 결과다.")
    @GetMapping
    public ApiResponse<NotificationsResponse> getNotifications(@CurrentUser UUID userId) {
        // @CurrentUser 파라미터 자체가 인증을 강제하므로 별도 확인 호출이 필요 없다.
        return ApiResponse.of(NotificationsResponse.from(notificationQueryService.listNotifications(userId)));
    }
}
