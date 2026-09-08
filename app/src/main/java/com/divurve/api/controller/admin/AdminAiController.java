package com.divurve.api.controller.admin;

import com.divurve.api.config.auth.CurrentAdmin;
import com.divurve.api.dto.admin.AdminAiCallLogResponse;
import com.divurve.api.dto.admin.AdminAiUsageSummaryResponse;
import com.divurve.api.dto.admin.AdminExtractPreviewRequest;
import com.divurve.api.dto.admin.AdminExtractPreviewResponse;
import com.divurve.common.architecture.WebAdapter;
import com.divurve.common.response.ApiResponse;
import com.divurve.domain.ai.AiCallLogQueryService;
import com.divurve.domain.event.EconEventExtractPreviewService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.time.Instant;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 관리자 AI 테스트 (이슈 #111).
 *
 * <p><b>AI 자연어 설명은 여기에 없다.</b> 이미 {@code POST /api/v1/ai/explain} 이 있으므로
 * 관리자 화면이 그 경로를 그대로 호출한다 — 같은 기능을 두 경로로 두면 어느 쪽이 진짜인지
 * 모르게 되고, 가드레일 동작이 갈린다.
 *
 * <p>호출 로그 조회 두 건이 여기 붙는다(이슈 #143). 목록과 집계 중 <b>관리자가 실제로 보는 것은
 * 집계</b>다 — 행 단위 목록만으로는 "이번 주 비용이 왜 늘었는가" 가 보이지 않는다. 목록은 집계에서
 * 이상한 날을 찾은 뒤 그 안을 들여다보는 용도다.
 */
@WebAdapter
@RestController
@RequestMapping("/api/v1/admin/ai")
@Tag(name = "Admin - AI", description = "관리자: 비정형 데이터 정형화 테스트")
public class AdminAiController {

    private final EconEventExtractPreviewService econEventExtractPreviewService;
    private final AiCallLogQueryService aiCallLogQueryService;

    public AdminAiController(
            EconEventExtractPreviewService econEventExtractPreviewService,
            AiCallLogQueryService aiCallLogQueryService) {
        this.econEventExtractPreviewService = econEventExtractPreviewService;
        this.aiCallLogQueryService = aiCallLogQueryService;
    }

    @Operation(summary = "AI 호출 로그 목록",
            description = "최신순. 실 호출이 없던 요청도 남는다 — 템플릿 응답(model=null)·캐시 "
                    + "히트·쿼터 차단은 토큰이 0 이고 outcome 이 그 셋을 가른다. 프롬프트·응답 "
                    + "전문은 담지 않는다(이슈 #56).")
    @GetMapping("/calls")
    public ApiResponse<AdminAiCallLogResponse> calls(
            @CurrentAdmin UUID adminId,
            @RequestParam(value = "from", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(value = "to", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(value = "purpose", required = false) String purpose,
            @RequestParam(value = "surface", required = false) String surface,
            @RequestParam(value = "outcome", required = false) String outcome,
            @RequestParam(value = "is_demo", required = false) Boolean isDemo,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", required = false) Integer size) {
        return ApiResponse.of(AdminAiCallLogResponse.from(aiCallLogQueryService.list(
                new AiCallLogQueryService.CallLogFilter(
                        from, to, purpose, surface, outcome, isDemo),
                page,
                size)));
    }

    @Operation(summary = "AI 사용량 집계 (일자·용도·모델별)",
            description = "일자는 UTC 기준으로 자른다 — 표시 시간대는 화면의 선택이다. 비용 "
                    + "금액은 내지 않는다: 모델별 단가는 개정되고 어떤 단가를 적용할지는 팀이 "
                    + "정할 문제다.")
    @GetMapping("/usage-summary")
    public ApiResponse<AdminAiUsageSummaryResponse> usageSummary(
            @CurrentAdmin UUID adminId,
            @RequestParam(value = "from", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(value = "to", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
        return ApiResponse.of(
                AdminAiUsageSummaryResponse.from(aiCallLogQueryService.summarize(from, to)));
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
