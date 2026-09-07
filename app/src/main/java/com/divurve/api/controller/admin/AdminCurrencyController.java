package com.divurve.api.controller.admin;

import com.divurve.api.config.auth.CurrentAdmin;
import com.divurve.api.dto.admin.AdminCurrencyResponse;
import com.divurve.common.architecture.WebAdapter;
import com.divurve.common.response.ApiResponse;
import com.divurve.domain.master.MasterDataService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 관리자 통화 마스터 조회 (이슈 #111).
 *
 * <p>공개 {@code GET /api/v1/currencies} 는 그대로 둔다 — 프론트가 쓰는 5필드 계약을 이 작업으로
 * 바꾸지 않는다. 여기는 {@code is_supported}·{@code support_note} 를 포함한 전 컬럼을 보여준다.
 */
@WebAdapter
@RestController
@RequestMapping("/api/v1/admin/currencies")
@Tag(name = "Admin - Currencies", description = "관리자: DB 통화·통화쌍 마스터 전 컬럼")
public class AdminCurrencyController {

    private final MasterDataService masterDataService;

    public AdminCurrencyController(MasterDataService masterDataService) {
        this.masterDataService = masterDataService;
    }

    @Operation(summary = "DB 에 등록된 통화·통화쌍 전부",
            description = "is_supported=false 는 환율을 조달할 수 없는 통화다(support_note 에 사유).")
    @GetMapping
    public ApiResponse<AdminCurrencyResponse> list(@CurrentAdmin UUID adminId) {
        return ApiResponse.of(AdminCurrencyResponse.of(
                masterDataService.listAllCurrencies(), masterDataService.listCurrencyPairs()));
    }
}
