package com.divurve.api.dto.notifications;

import com.divurve.domain.notification.NotificationQueryService.NotificationView;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 알림 목록 조회 응답 ({@code GET /notifications}, 이슈 #97).
 * 알림이 없으면 {@code notifications} 는 빈 배열이다(FR-CM-09) — 빈 배열 자체가 유효한 응답이다.
 */
@Schema(description = "알림 목록 조회 응답. 알림이 없으면 빈 배열이다.")
public record NotificationsResponse(
        @Schema(description = "최신순 알림 목록. 없으면 빈 배열")
        List<NotificationDto> notifications) {

    /** 도메인 뷰를 응답 DTO 로 옮긴다. */
    public static NotificationsResponse from(List<NotificationView> views) {
        return new NotificationsResponse(views.stream().map(NotificationDto::from).toList());
    }

    /**
     * 개별 알림 정보.
     *
     * @param id        알림 id
     * @param kind      알림 종류 — ERD {@code notification_type} ENUM 6종 (이슈 #97 판단 근거는
     *                  {@code Notification} 엔티티 Javadoc 참고)
     * @param title     제목
     * @param body      본문
     * @param createdAt 생성 시각
     * @param isRead    읽음 여부. DB 컬럼 {@code is_read} 와 이름을 맞춘다(CLAUDE.md 5장) —
     *                  전역 SNAKE_CASE 전략이 {@code is_read} 로 직렬화한다.
     *                  {@code isDemo}·{@code isSampleData} 와 같은 표기다
     */
    @Schema(description = "알림 한 건")
    public record NotificationDto(
            UUID id,

            @Schema(description = "알림 종류 (ERD notification_type ENUM)", example = "target_zone",
                    allowableValues = {
                            "step_due", "regime_shift", "deadline_near",
                            "target_zone", "safe_mode", "concentration"})
            String kind,

            @Schema(description = "제목")
            String title,

            @Schema(description = "본문")
            String body,

            @Schema(description = "생성 시각")
            Instant createdAt,

            @Schema(description = "읽음 여부")
            boolean isRead) {

        static NotificationDto from(NotificationView view) {
            return new NotificationDto(
                    view.id(), view.kind(), view.title(), view.body(), view.createdAt(), view.read());
        }
    }
}
