package com.divurve.api.controller;

import com.divurve.api.config.auth.CurrentUser;
import com.divurve.api.dto.ai.ExplainRequest;
import com.divurve.api.dto.ai.ExplainResponse;
import com.divurve.common.architecture.WebAdapter;
import com.divurve.common.exception.InvalidRequestException;
import com.divurve.common.response.ApiResponse;
import com.divurve.common.response.Meta;
import com.divurve.domain.ai.AiService;
import com.divurve.domain.port.AuthPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import java.util.Objects;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * AI 서술 엔드포인트 (API 명세 v2 §5.12, 이슈 #54(7.5)).
 * FR-AI-01: 점수·금액·범위·등급은 계산 엔진이 만들고, AI 는 검증된 {@code facts} 만 서술한다.
 * FR-AI-05: 서술의 숫자·표현을 후처리로 대조·검사한다.
 * FR-AI-06: 검증 실패 시에도 <b>200 + fallback:true</b> 를 반환한다 — AI 실패는 서비스 실패가 아니다.
 *
 * <p>v1 의 {@code POST /ai/parse-goal} 은 v2 에서 삭제됐다(요구사항 v2 §0 개정표 — 자연어 목표 입력은
 * Route 상세설계 확정 전까지 MVP 범위 밖).
 */
@WebAdapter
@RestController
@RequestMapping("/api/v1/ai")
@Tag(name = "AI", description = "계산 엔진 결과의 자연어 서술 (v2)")
public class AiController {

    private final AiService aiService;

    public AiController(AiService aiService) {
        this.aiService = Objects.requireNonNull(aiService, "aiService");
    }

    @Operation(
            summary = "엔진 결과를 설명 선호에 맞춰 서술",
            description = "surface·facts 를 받아 문장으로 서술한다. explain_level·explain_domain 은 "
                    + "요청 본문이 아니라 사용자 설정에서 읽는다(FR-CM-08). 수치 대조·표현 필터를 "
                    + "통과하지 못해도 400 을 내지 않고 200 + fallback:true + 고정 템플릿을 반환한다"
                    + "(FR-AI-06, NFR-AI-03).")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "200", description = "서술 성공 또는 폴백(둘 다 200)"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "400", description = "surface 또는 facts 누락")
    })
    @PostMapping("/explain")
    public ApiResponse<ExplainResponse> explain(
            @CurrentUser AuthPrincipal principal, @RequestBody ExplainRequest request) {
        validateExplainRequest(request);

        // 데모 여부를 도메인까지 넘기는 이유(이슈 #143) — 호출 기록의 is_demo 는 데모 트래픽이
        // 비용에서 차지하는 비중을 보는 축이고, 그 근거는 토큰의 is_demo 클레임뿐이다. 도메인이
        // 사용자를 다시 조회해 알아내면 서술 요청마다 불필요한 쿼리가 하나 늘고, 계정이 데모로
        // 만들어졌다는 사실과 이 요청이 데모 세션에서 왔다는 사실이 어긋날 수 있다.
        AiService.ExplainOutcome outcome = aiService.explain(
                principal.userId(), principal.isDemo(), request.surface(), request.facts());

        ExplainResponse response = new ExplainResponse(
                new ExplainResponse.Explanation(
                        outcome.sentences(),
                        outcome.sentences().size(),
                        outcome.explainLevel(),
                        outcome.explainDomain(),
                        outcome.fallback()),
                verificationOf(outcome));

        return ApiResponse.of(response, resolveMeta(request));
    }

    /**
     * 검증 측정값과 폴백 사유를 그대로 옮긴다 (이슈 #122).
     *
     * <p>폴백에 이르는 경로가 넷인데 응답이 전부 같은 값으로 수렴해, 어느 검증에서 걸렸는지 —
     * 애초에 검증까지 가기는 했는지 — 를 화면에서 알 수 없었다. 사유를 값으로 내려 그 넷을 가른다.
     */
    private ExplainResponse.Verification verificationOf(AiService.ExplainOutcome outcome) {
        AiService.FallbackReason reason = outcome.fallbackReason();
        return new ExplainResponse.Verification(
                outcome.numericMatch(),
                outcome.regimeDisclosed(),
                outcome.blockedPhrases(),
                reason == null ? null : reason.code());
    }

    /**
     * {@code facts.regime} 이 있으면 급변 상태 배지를 메타에 실어 전 화면과 같은 어휘로 노출한다(FR-SF-02).
     * {@code request.facts()} 는 {@link #validateExplainRequest} 가 이미 비어있지 않음을 보장했으므로 여기서는
     * {@code null} 을 다시 검사하지 않는다.
     */
    private Meta resolveMeta(ExplainRequest request) {
        Meta meta = Meta.mock(Instant.now());
        Object regime = request.facts().get("regime");
        if (regime instanceof String regimeCode && !regimeCode.isBlank()) {
            meta = meta.withRegime(regimeCode);
        }
        return meta;
    }

    private void validateExplainRequest(ExplainRequest request) {
        if (request == null) {
            throw new InvalidRequestException("요청 본문이 필요합니다.");
        }
        if (request.surface() == null || request.surface().isBlank()) {
            throw new InvalidRequestException("surface 필드는 필수입니다.");
        }
        if (request.facts() == null || request.facts().isEmpty()) {
            throw new InvalidRequestException("facts 필드는 필수입니다.");
        }
    }
}
