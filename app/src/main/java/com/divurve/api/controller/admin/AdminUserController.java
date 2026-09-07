package com.divurve.api.controller.admin;

import com.divurve.api.config.auth.CurrentAdmin;
import com.divurve.api.dto.admin.AdminUserDataResponse;
import com.divurve.api.dto.admin.AdminUserListResponse;
import com.divurve.common.architecture.WebAdapter;
import com.divurve.common.response.ApiResponse;
import com.divurve.domain.user.AdminDataQueryService;
import com.divurve.domain.user.AdminUserQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 관리자 사용자 조회 (이슈 #111).
 *
 * <p>모든 메서드가 {@code @CurrentAdmin} 을 받는다 — 그것이 인증(401)과 인가(403)를 강제하는
 * 유일한 지점이다. 파라미터를 빼면 그 엔드포인트는 공개가 된다.
 *
 * <p>전 사용자의 전 컬럼을 다루는 API 다. {@code password_hash} 는 어느 응답에도 담기지 않는다.
 */
@WebAdapter
@RestController
@RequestMapping("/api/v1/admin/users")
@Tag(name = "Admin - Users", description = "관리자: 사용자 목록·전 데이터 조회")
public class AdminUserController {

    private final AdminUserQueryService adminUserQueryService;
    private final AdminDataQueryService adminDataQueryService;

    public AdminUserController(
            AdminUserQueryService adminUserQueryService,
            AdminDataQueryService adminDataQueryService) {
        this.adminUserQueryService = adminUserQueryService;
        this.adminDataQueryService = adminDataQueryService;
    }

    @Operation(summary = "전체 사용자 목록 (데모 계정 포함)",
            description = "가입 역순. email 이 로그인 식별자다 — 별도 username 은 없다.")
    @GetMapping
    public ApiResponse<AdminUserListResponse> list(
            @CurrentAdmin UUID adminId,
            @RequestParam(value = "q", required = false) String keyword,
            @RequestParam(value = "is_demo", required = false) Boolean isDemo,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", required = false) Integer size) {
        return ApiResponse.of(AdminUserListResponse.from(
                adminUserQueryService.list(keyword, isDemo, page, size)));
    }

    @Operation(summary = "사용자 한 명 요약")
    @GetMapping("/{id}")
    public ApiResponse<AdminUserListResponse.AdminUser> get(
            @CurrentAdmin UUID adminId, @PathVariable("id") UUID userId) {
        return ApiResponse.of(
                AdminUserListResponse.AdminUser.of(adminUserQueryService.get(userId)));
    }

    @Operation(summary = "사용자의 모든 도메인 데이터 (모든 컬럼)",
            description = "자산·목표·계획·회차·성향·설정·스트레스 이력. 소유자 참조는 id 로만 담는다.")
    @GetMapping("/{id}/data")
    public ApiResponse<AdminUserDataResponse> data(
            @CurrentAdmin UUID adminId, @PathVariable("id") UUID userId) {
        return ApiResponse.of(AdminUserDataResponse.from(adminDataQueryService.collect(userId)));
    }
}
