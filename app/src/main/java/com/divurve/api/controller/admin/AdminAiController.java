package com.divurve.api.controller.admin;

import com.divurve.api.config.auth.CurrentAdmin;
import com.divurve.api.dto.admin.AdminExtractPreviewRequest;
import com.divurve.api.dto.admin.AdminExtractPreviewResponse;
import com.divurve.common.architecture.WebAdapter;
import com.divurve.common.response.ApiResponse;
import com.divurve.domain.event.EconEventExtractPreviewService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 관리자 AI 테스트 (이슈 #111).
 *
 * <p><b>AI 자연어 설명은 여기에 없다.</b> 이미 {@code POST /api/v1/ai/explain} 이 있으므로
 * 관리자 화면이 그 경로를 그대로 호출한다 — 같은 기능을 두 경로로 두면 어느 쪽이 진짜인지
 * 모르게 되고, 가드레일 동작이 갈린다.
 */
@WebAdapter
@RestController
@RequestMapping("/api/v1/admin/ai")
@Tag(name = "Admin - AI", description = "관리자: 비정형 데이터 정형화 테스트")
public class AdminAiController {

    private final EconEventExtractPreviewService econEventExtractPreviewService;

    public AdminAiController(EconEventExtractPreviewService econEventExtractPreviewService) {
        this.econEventExtractPreviewService = econEventExtractPreviewService;
    }

    @Operation(summary = "비정형 원문 → 구조화 추출 미리보기",
            description = "econ_events 에 저장하지 않는다. 거부된 후보도 사유와 함께 전부 돌려준다. "
                    + "extractor 가 NoOpEconEventExtractor 면 추출기가 꺼져 있다는 뜻이다.")
    @PostMapping("/extract-preview")
    public ApiResponse<AdminExtractPreviewResponse> extractPreview(
            @CurrentAdmin UUID adminId, @Valid @RequestBody AdminExtractPreviewRequest request) {
        return ApiResponse.of(AdminExtractPreviewResponse.from(
                econEventExtractPreviewService.preview(request.sourceUrl(), request.text())));
    }
}
