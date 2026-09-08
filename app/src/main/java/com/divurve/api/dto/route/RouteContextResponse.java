package com.divurve.api.dto.route;

import com.divurve.domain.route.RouteContext;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;

/**
 * {@code GET /api/v1/route/context} 응답 (API 명세 v2 §6.1, FR-RT-01) — <b>P(구조만 준비)</b>.
 *
 * <p>RouteContext 를 직렬화만 한다. 계산 결과는 하나도 들어 있지 않으며, 값이 확정되기 전까지
 * 스칼라 필드는 전부 {@code null} 이다 (전역 {@code non_null} 설정에 따라 JSON 에서 생략된다).
 *
 * <p>🔒 <b>{@code model_path} 와 {@code forecast_factors} 는 이 계약에 없다.</b> 방향 전망을 Route 의
 * 계산 입력으로 전달하지 않는다는 <b>FR-FC-12</b> 를 API 계약 수준에서 강제한다 (명세 v2 §6.1 의
 * 잠금 항목). 필드를 추가해 달라는 요청이 오면 요구사항 개정이 선행되어야 한다.
 *
 * <p>{@code interval_80}·{@code vol_30d} 는 전역 SNAKE_CASE 전략이 숫자 앞에 밑줄을 넣지 않으므로
 * {@link JsonProperty} 로 명세의 키를 그대로 고정한다.
 *
 * @param asOf      데이터 기준 시각(UTC)
 * @param diagnosis 진단 요약
 * @param portfolio 자산 요약
 * @param forecast  기준 환율 요약
 * @param stress    스트레스 결과 요약
 * @param regime    시장 국면
 */
public record RouteContextResponse(
        Instant asOf,
        Diagnosis diagnosis,
        Portfolio portfolio,
        Forecast forecast,
        Stress stress,
        String regime) {

    /** 도메인 계약 → 응답 DTO. 값 변환·계산 없이 그대로 옮긴다. */
    public static RouteContextResponse from(RouteContext context) {
        return new RouteContextResponse(
                context.asOf(),
                Diagnosis.from(context.diagnosis()),
                Portfolio.from(context.portfolio()),
                Forecast.from(context.forecast()),
                Stress.from(context.stress()),
                context.regime());
    }

    /** 진단 요약. */
    public record Diagnosis(
            String status,
            String grade,
            Integer score,
            Double concentrationThreshold) {

        static Diagnosis from(RouteContext.Diagnosis source) {
            return new Diagnosis(
                    source.status(), source.grade(), source.score(), source.concentrationThreshold());
        }
    }

    /** 자산 요약. */
    public record Portfolio(
            Long totalAssetKrw,
            Long fxAssetKrw,
            Double fxRatio,
            Map<String, Double> exposure) {

        static Portfolio from(RouteContext.Portfolio source) {
            return new Portfolio(
                    source.totalAssetKrw(), source.fxAssetKrw(), source.fxRatio(), source.exposure());
        }
    }

    /** 기준 환율 요약. 방향 전망(모델 경로·요인)은 담지 않는다 — FR-FC-12. */
    public record Forecast(
            String pairCode,
            Double baseRate,
            @JsonProperty("interval_80") Interval interval80,
            @JsonProperty("vol_30d") Double vol30d,
            LocalDate baseDate) {

        static Forecast from(RouteContext.Forecast source) {
            return new Forecast(
                    source.pairCode(),
                    source.baseRate(),
                    Interval.from(source.interval80()),
                    source.vol30d(),
                    source.baseDate());
        }

        /** 구간 하단·상단. */
        @Schema(name = "RouteForecastInterval",
                description = "경로 컨텍스트가 참고하는 예측 구간 하단·상단. 폭 비율까지 담는 "
                        + "ForecastResponse.Interval 과 구조가 달라 이름을 분리했다(이슈 #88)")
        public record Interval(Double lo, Double hi) {

            static Interval from(RouteContext.Forecast.Interval source) {
                return new Interval(source.lo(), source.hi());
            }
        }
    }

    /** 스트레스 결과 요약. */
    public record Stress(String lastRunId, Long totalEffectKrw) {

        static Stress from(RouteContext.Stress source) {
            return new Stress(source.lastRunId(), source.totalEffectKrw());
        }
    }
}
