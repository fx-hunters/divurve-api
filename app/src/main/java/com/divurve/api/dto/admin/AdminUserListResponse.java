package com.divurve.api.dto.admin;

import com.divurve.domain.user.AdminUserQueryService;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 관리자 사용자 목록 응답 (이슈 #111).
 *
 * <p>Spring Data 의 {@code Page} 를 그대로 직렬화하지 않는다 — 그 형태는 프레임워크 내부 구조라
 * API 계약이 프레임워크 버전에 묶인다.
 */
public record AdminUserListResponse(
        List<AdminUser> items,
        @Schema(example = "0") int page,
        @Schema(example = "50") int size,
        long totalElements,
        int totalPages) {

    /** 서비스 결과를 응답 형태로 옮긴다. */
    public static AdminUserListResponse from(AdminUserQueryService.UserPage page) {
        return new AdminUserListResponse(
                page.items().stream().map(AdminUser::from).toList(),
                page.page(),
                page.size(),
                page.totalElements(),
                page.totalPages());
    }

    /**
     * 사용자 한 명. {@code email} 이 로그인 식별자다 — 이 스키마에 별도 {@code username} 은 없다.
     * {@code password_hash} 는 담지 않는다.
     */
    public record AdminUser(
            UUID id,
            @Schema(example = "user@example.com") String email,
            String name,
            @Schema(example = "USER", allowableValues = {"USER", "ADMIN"}) String role,
            boolean isDemo,
            boolean sampleDataSeeded,
            Instant createdAt,
            Instant onboardedAt,
            Instant lastLoginAt,
            @Schema(example = "203.0.113.42") String lastLoginIp) {

        static AdminUser from(AdminUserQueryService.UserSummary summary) {
            return new AdminUser(
                    summary.id(),
                    summary.email(),
                    summary.name(),
                    summary.role(),
                    summary.demo(),
                    summary.sampleDataSeeded(),
                    summary.createdAt(),
                    summary.onboardedAt(),
                    summary.lastLoginAt(),
                    summary.lastLoginIp());
        }

        /** 단건 조회 응답에서도 같은 형태를 쓴다. */
        public static AdminUser of(AdminUserQueryService.UserSummary summary) {
            return from(summary);
        }
    }
}
