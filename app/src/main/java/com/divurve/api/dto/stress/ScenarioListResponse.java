package com.divurve.api.dto.stress;

import com.divurve.domain.stress.StressScenarioService;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * 스트레스 시나리오 마스터 목록 ({@code GET /stress/scenarios}, 명세 v2 §3 · FR-ST-01).
 *
 * <p>충격률은 <b>가정값</b>이며 예측이 아니다. 그래서 {@code assumption_note} · {@code reference_event}
 * 를 함께 내려 사용자가 어떤 가정인지 확인할 수 있게 한다(FR-ST-04).
 */
@Schema(description = "스트레스 시나리오 마스터 목록. 충격률은 가정값이며 예측이 아니다.")
public record ScenarioListResponse(
        @Schema(description = "노출 순서대로 정렬된 시나리오. 클라이언트는 재정렬하지 않는다(NFR-UI-01).")
        List<Scenario> scenarios) {

    /** 도메인 뷰를 응답 DTO 로 옮긴다. */
    public static ScenarioListResponse from(List<StressScenarioService.ScenarioView> views) {
        return new ScenarioListResponse(views.stream().map(Scenario::from).toList());
    }

    /**
     * 시나리오 한 건.
     *
     * @param scenarioCode   시나리오 코드
     * @param nameKo         한국어 이름
     * @param equityShockPct 가정 주가 충격률 (음수 = 하락)
     * @param fxShockPct     가정 환율 충격률 (양수 = USD/KRW 상승 = 원화 약세, FR-CM-05)
     * @param referenceEvent 참고한 실제 사건
     * @param assumptionNote 적용 순서·가정 설명
     * @param isDefault      기본 제공 시나리오 여부
     * @param sortOrder      화면 노출 순서
     */
    @Schema(name = "StressScenario",
            description = "선택 가능한 스트레스 시나리오 마스터 한 건. 실행 결과에 붙는 "
                    + "StressRunResponse.Scenario 와 구조가 달라 이름을 분리했다(이슈 #88)")
    public record Scenario(
            @Schema(description = "시나리오 코드", example = "equity_down_krw_weak")
            String scenarioCode,

            @Schema(description = "한국어 이름", example = "주가 하락 + 원화 약세")
            String nameKo,

            @Schema(description = "가정 주가 충격률 (음수 = 하락)", example = "-0.2")
            double equityShockPct,

            @Schema(description = "가정 환율 충격률 (양수 = 원화 약세)", example = "0.1")
            double fxShockPct,

            @Schema(description = "참고한 실제 사건", example = "2020년 3월 변동성 급등 참고")
            String referenceEvent,

            @Schema(description = "적용 순서·가정 설명")
            String assumptionNote,

            @Schema(description = "기본 제공 시나리오 여부", example = "true")
            boolean isDefault,

            @Schema(description = "화면 노출 순서", example = "1")
            short sortOrder) {

        static Scenario from(StressScenarioService.ScenarioView view) {
            return new Scenario(
                    view.scenarioCode(),
                    view.nameKo(),
                    view.equityShockPct(),
                    view.fxShockPct(),
                    view.referenceEvent(),
                    view.assumptionNote(),
                    view.isDefault(),
                    view.sortOrder());
        }
    }
}
