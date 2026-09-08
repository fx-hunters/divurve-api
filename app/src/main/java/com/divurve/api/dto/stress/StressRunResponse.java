package com.divurve.api.dto.stress;

import com.divurve.domain.stress.StressRunService;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.util.UUID;

/**
 * 스트레스 시나리오 실행 결과 ({@code POST /stress/runs} 201, 명세 v2 §5.9).
 *
 * <h2>적용 순서를 응답으로 못박는다</h2>
 * 주가 충격 → 환율 충격 순서라서 {@code equity_effect_krw + fx_effect_krw = total_effect_krw} 가
 * <b>정확히</b> 성립한다. 클라이언트가 세 값을 각각 그려도 합이 어긋나지 않는다.
 *
 * <p>{@code conditional_note} 는 결과가 예측이 아니라 가정 충격의 조건부 계산임을 응답 자체에 싣는다(FR-ST-04).
 */
@Schema(description = "스트레스 시나리오 실행 결과. 예측이 아니라 가정 충격에 대한 조건부 계산이다.")
public record StressRunResponse(
        @Schema(description = "저장된 실행 id")
        UUID id,

        @Schema(description = "적용한 시나리오")
        Scenario scenario,

        @Schema(description = "계산 기준일", example = "2026-09-01")
        LocalDate baseDate,

        @Schema(description = "적용한 충격값 (실행 시점 스냅샷)")
        Shock shock,

        @Schema(description = "적용 전 평가액")
        Before before,

        @Schema(description = "효과 3항. 세 값의 합 관계가 항상 성립한다.")
        Effects effects,

        @Schema(description = "적용 후 평가액")
        After after,

        @Schema(
                description = "주가 효과와 환율 효과의 관계 코드. 문장은 클라이언트가 코드로 고른다.",
                allowableValues = {
                        "fx_cushions_equity_loss",
                        "fx_offsets_equity_loss",
                        "equity_and_fx_both_negative",
                        "fx_reduces_equity_gain",
                        "equity_and_fx_both_positive"
                },
                example = "fx_cushions_equity_loss")
        String interpretationCode,

        @Schema(description = "결과가 예측이 아님을 알리는 고정 문구")
        String conditionalNote) {

    /** 도메인 뷰를 응답 DTO 로 옮긴다. */
    public static StressRunResponse from(StressRunService.RunView view) {
        return new StressRunResponse(
                view.id(),
                Scenario.from(view.scenario()),
                view.baseDate(),
                new Shock(view.equityShockPct(), view.fxShockPct()),
                new Before(view.equityAssetKrw(), view.fxAssetBeforeKrw()),
                new Effects(view.equityEffectKrw(), view.fxEffectKrw(), view.totalEffectKrw()),
                new After(view.fxAssetAfterKrw()),
                view.interpretationCode(),
                StressRunService.CONDITIONAL_NOTE);
    }

    /**
     * 적용한 시나리오 요약.
     *
     * @param scenarioCode   시나리오 코드
     * @param nameKo         한국어 이름
     * @param referenceEvent 참고한 실제 사건
     * @param assumptionNote 적용 순서·가정 설명
     */
    @Schema(name = "StressRunScenario",
            description = "실행 결과에 동반되는 시나리오 설명 (가정 공개용). 충격률을 담는 "
                    + "ScenarioListResponse.Scenario 와 구조가 달라 이름을 분리했다(이슈 #88)")
    public record Scenario(
            String scenarioCode,
            String nameKo,
            String referenceEvent,
            String assumptionNote) {

        static Scenario from(StressRunService.ScenarioSummary summary) {
            if (summary == null) {
                return null;
            }
            return new Scenario(
                    summary.scenarioCode(),
                    summary.nameKo(),
                    summary.referenceEvent(),
                    summary.assumptionNote());
        }
    }

    /**
     * 적용한 충격값.
     *
     * @param equityShockPct 주가 충격률 (음수 = 하락)
     * @param fxShockPct     환율 충격률 (양수 = USD/KRW 상승 = 원화 약세, FR-CM-05)
     */
    @Schema(description = "적용한 충격값. 양수 fx_shock_pct = 원화 약세.")
    public record Shock(
            @Schema(example = "-0.2") double equityShockPct,
            @Schema(example = "0.1") double fxShockPct) {
    }

    /**
     * 적용 전 평가액.
     *
     * @param equityAssetKrw 해외주식 평가액 (주가 충격의 기준)
     * @param fxAssetKrw     외화자산 전체 평가액 (환율 충격의 기준)
     */
    @Schema(description = "적용 전 평가액")
    public record Before(
            @Schema(example = "20000000") long equityAssetKrw,
            @Schema(example = "24720000") long fxAssetKrw) {
    }

    /**
     * 효과 3항 (요구사항 §4.8 "주가·환율·총 평가금액 효과 분리").
     *
     * @param equityEffectKrw 주가 효과
     * @param fxEffectKrw     환율 효과
     * @param totalEffectKrw  총 평가금액 효과 (= 주가 + 환율)
     */
    @Schema(description = "효과 3항. equity + fx = total 이 정확히 성립한다.")
    public record Effects(
            @Schema(example = "-4000000") long equityEffectKrw,
            @Schema(example = "2072000") long fxEffectKrw,
            @Schema(example = "-1928000") long totalEffectKrw) {
    }

    /**
     * 적용 후 평가액.
     *
     * @param fxAssetKrw 적용 후 외화자산 평가액
     */
    @Schema(description = "적용 후 평가액")
    public record After(
            @Schema(example = "22792000") long fxAssetKrw) {
    }
}
