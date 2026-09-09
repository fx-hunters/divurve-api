package com.divurve.api.controller.admin;

import com.divurve.api.config.auth.CurrentAdmin;
import com.divurve.api.dto.admin.AdminEconEventRefreshResponse;
import com.divurve.api.dto.admin.AdminEconEventStatusResponse;
import com.divurve.common.architecture.WebAdapter;
import com.divurve.common.response.ApiResponse;
import com.divurve.domain.event.EconEventAdminService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Objects;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 경제 일정 공식 캘린더 관리자 엔드포인트 (이슈 #176).
 *
 * <p>경로를 {@code /admin/master/econ-events} 아래에 둔다 — 같은 표의 손편집 CRUD 가 이 경로로
 * 올 예정이라(이슈 #174), 리소스 하나를 두 군데로 갈라 두지 않는다.
 *
 * <p><b>갱신이 {@code POST} 인 이유</b> — 외부 API 를 호출하고 DB 를 바꾼다. 같은 일정을 다시
 * 넣지 않으므로 멱등하지만 안전하지는 않다({@link AdminFxRateController} 와 같은 판단).
 */
@WebAdapter
@RestController
@RequestMapping("/api/v1/admin/master/econ-events")
@Tag(name = "Admin · Econ Events", description = "경제 일정 공식 캘린더 적재·현황")
public class AdminEconEventController {

    private final EconEventAdminService econEventAdminService;

    public AdminEconEventController(EconEventAdminService econEventAdminService) {
        this.econEventAdminService =
                Objects.requireNonNull(econEventAdminService, "econEventAdminService");
    }

    @Operation(
            summary = "공식 캘린더 수동 갱신",
            description = "스케줄러를 기다리지 않고 지금 적재한다. 외부 호출이 실패해도 500 이 아니라 "
                    + "failure_reason 을 값으로 낸다 — 이 호출의 목적이 '왜 안 되는가' 를 보는 것이다. "
                    + "unknown 이 크면 대조표의 지표명이 캘린더 이름과 어긋난다는 뜻이고, "
                    + "scheduled 가 0 이면 캘린더가 일정을 주지 않았다는 뜻이다.")
    @PostMapping("/refresh")
    public ApiResponse<AdminEconEventRefreshResponse> refresh(@CurrentAdmin UUID adminId) {
        return ApiResponse.of(
                AdminEconEventRefreshResponse.from(econEventAdminService.refresh()));
    }

    @Operation(
            summary = "적재 현황 조회",
            description = "출처별 건수·마지막 적재 시각·가장 먼 일정 날짜. 공식 파서·AI 추출·시연용 "
                    + "예시를 합치지 않고 나눠 낸다 — 신뢰도를 섞지 않는다.")
    @GetMapping("/status")
    public ApiResponse<AdminEconEventStatusResponse> status(@CurrentAdmin UUID adminId) {
        return ApiResponse.of(AdminEconEventStatusResponse.from(econEventAdminService.status()));
    }
}
