package com.divurve.infra.ai;

import com.divurve.common.architecture.ExternalAdapter;
import com.divurve.domain.ai.ExplainSurface;
import com.divurve.domain.port.AiProvider;
import com.divurve.domain.settings.UserSettingsService;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Mock AI 제공자 (@ExternalAdapter, API 명세 v2 §5.12).
 * 프로덕션에서는 실 LLM 어댑터로 대체된다(문서 §8 "실제 LLM 연결 여부"는 S 우선순위 미결정).
 *
 * <p><b>{@link AiProvider.ExplainContext#facts()} 에 없는 사실을 지어내지 않는다</b>(리뷰 B M3 대응).
 * v1 Mock 은 "위험도는 중간 수준입니다", "다양한 자산군에 분산되어 있으며" 처럼 입력에 없는 값을
 * 임의로 채웠다. 이 구현은 {@code facts} 에 실제로 있는 값만 문장에 담는다 — 없는 키는 언급하지 않는다.
 *
 * <p><b>실 LLM 이 켜지면 이 클래스는 사용자 요청을 받지 않는다</b>(이슈 #135).
 * {@link ClaudeAiProvider} 는 {@code app.external.anthropic.enabled=true} 일 때만 만들어지고
 * {@code @Primary} 를 갖는다 — 빈이 2개여도 주입은 그쪽으로 간다(이슈 #38 유형의 기동 실패는
 * 일어나지 않는다). 예전에는 실 LLM 이 켜진 뒤에도 이 클래스가 {@code forecast_summary} 이외
 * 화면을 위임받아 계속 담당했고, 아래 {@link #genericSentences} 의 키-값 나열이 그대로 사용자
 * 화면에 나갔다(이슈 #135). 지금 실 LLM 은 {@link com.divurve.domain.ai.ExplainSurface} 가 아는
 * 화면 전부를 처리하므로, 이 클래스는 <b>실 LLM 이 꺼진 로컬·테스트 환경</b>의 provider 로만 남는다.
 */
@ExternalAdapter
public class MockAiProvider implements AiProvider {

    private static final String REGIME_ELEVATED = "elevated";
    private static final String REGIME_STRESS = "stress";

    private static final String WIDEN_UNCERTAINTY_SENTENCE =
            "최근 시장 변동성이 커진 구간이라 안내한 수치와 구간의 오차가 평소보다 커질 수 있습니다.";

    @Override
    public ExplainResult explain(ExplainContext context) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(context.surface(), "surface");
        Objects.requireNonNull(context.facts(), "facts");

        List<String> sentences = ExplainSurface.FORECAST_SUMMARY.code().equals(context.surface())
                ? forecastSummarySentences(context)
                : genericSentences(context);
        // 템플릿이므로 LLM 을 부르지 않았다 — 토큰 0 을 명시한다(이슈 #143).
        return ExplainResult.withoutLlm(sentences);
    }

    /**
     * {@code forecast_summary} — 항상 4문장(FR-FC-07). {@code explain_level} 은 4문장에 담는
     * 내용만 바꾸고 문장 수는 바꾸지 않는다(문서 §3.2).
     */
    private List<String> forecastSummarySentences(ExplainContext context) {
        Map<String, Object> facts = context.facts();
        String pairCode = string(facts.get("pair_code"), "환율");
        Double currentRate = number(facts.get("current_rate"));
        Map<?, ?> interval = facts.get("interval_80") instanceof Map<?, ?> map ? map : null;
        Double lo = interval == null ? null : number(interval.get("lo"));
        Double hi = interval == null ? null : number(interval.get("hi"));
        Double volPercentile = number(facts.get("vol_percentile_5y"));
        Double per1pctKrw = number(facts.get("per_1pct_krw"));
        boolean widen = isWidenRegime(facts.get("regime"));

        List<String> sentences = new ArrayList<>(4);
        String level = context.explainLevel();
        if (UserSettingsService.EXPLAIN_LEVEL_STANDARD.equals(level)) {
            sentences.add(sentenceOrFallback(currentRate,
                    v -> "%s 현재 환율은 %.2f원입니다.".formatted(pairCode, v)));
            sentences.add(intervalSentence(lo, hi, "80%% 구간은 %.2f원에서 %.2f원 사이로 추정됩니다."));
            sentences.add(sentenceOrFallback(volPercentile,
                    v -> "최근 변동성은 5년 분포 기준 백분위 %.0f%% 구간입니다.".formatted(v * 100)));
            sentences.add(sentenceOrFallback(per1pctKrw,
                    v -> "환율이 1%% 움직이면 보유 자산 평가액이 약 %,d원 바뀝니다.".formatted(v.longValue())));
        } else if (UserSettingsService.EXPLAIN_LEVEL_DETAILED.equals(level)) {
            sentences.add(intervalSentence(lo, hi, "제시된 구간(%.2f원~%.2f원)은 80%% 신뢰수준의 참고 범위입니다."));
            sentences.add(sentenceOrFallback(volPercentile,
                    v -> "변동성 지표는 5년 백분위 %.0f%%에 해당합니다.".formatted(v * 100)));
            sentences.add(sentenceOrFallback(per1pctKrw,
                    v -> "환율 1%% 변동 시 평가액 민감도는 약 %,d원입니다.".formatted(v.longValue())));
            sentences.add("이 모델은 방향을 예측하지 않으며 실제 환율은 제시된 구간을 벗어날 수 있습니다."
                    + (widen ? " " + WIDEN_UNCERTAINTY_SENTENCE : ""));
        } else {
            // simple(기본) — 결론 · 내 돈 영향 · 주의 · 읽는 법.
            sentences.add(sentenceOrFallback(currentRate,
                    v -> "%s은(는) 현재 %.2f원 수준에서 움직이고 있습니다.".formatted(pairCode, v)));
            sentences.add(sentenceOrFallback(per1pctKrw,
                    v -> "환율이 1%% 움직이면 보유 자산 평가액이 약 %,d원 변합니다.".formatted(v.longValue())));
            sentences.add(widen
                    ? WIDEN_UNCERTAINTY_SENTENCE
                    : "지금은 특별히 주의할 변동 신호가 없습니다.");
            sentences.add(intervalSentence(lo, hi,
                    "위 범위(%.2f원~%.2f원)는 참고용 예측 구간이며 투자 권유가 아닙니다."));
        }
        return sentences;
    }

    /**
     * {@code forecast_summary} 이외 화면 — {@code facts} 에 실제로 있는 값만 문장으로 옮긴다.
     * 문장 수 규약이 없으므로 값이 없는 키는 생략한다(문서 §1 원칙, FR-CM-10).
     */
    private List<String> genericSentences(ExplainContext context) {
        Map<String, Object> facts = context.facts();
        List<String> sentences = new ArrayList<>();

        facts.forEach((key, value) -> {
            if ("regime".equals(key) || value == null) {
                return;
            }
            sentences.add(describe(key, value));
        });

        if (sentences.isEmpty()) {
            sentences.add("표시할 세부 수치가 아직 없습니다.");
        }
        if (isWidenRegime(facts.get("regime"))) {
            sentences.add(WIDEN_UNCERTAINTY_SENTENCE);
        }
        return sentences;
    }

    private String describe(String key, Object value) {
        String label = humanize(key);
        if (value instanceof Number number) {
            return "%s는(은) %s입니다.".formatted(label, formatNumber(number));
        }
        if (value instanceof Map<?, ?> nested) {
            List<String> parts = new ArrayList<>();
            nested.forEach((nestedKey, nestedValue) -> parts.add(
                    "%s %s".formatted(humanize(String.valueOf(nestedKey)), formatValue(nestedValue))));
            return "%s는(은) %s입니다.".formatted(label, String.join(", ", parts));
        }
        return "%s는(은) %s입니다.".formatted(label, formatValue(value));
    }

    private String formatValue(Object value) {
        return value instanceof Number number ? formatNumber(number) : String.valueOf(value);
    }

    private String formatNumber(Number number) {
        double value = number.doubleValue();
        return value == Math.floor(value) ? String.valueOf((long) value) : String.valueOf(value);
    }

    private String humanize(String key) {
        return key.replace('_', ' ');
    }

    private boolean isWidenRegime(Object regime) {
        String value = regime instanceof String s ? s.toLowerCase(Locale.ROOT) : null;
        return REGIME_ELEVATED.equals(value) || REGIME_STRESS.equals(value);
    }

    private Double number(Object value) {
        return value instanceof Number number ? number.doubleValue() : null;
    }

    private String string(Object value, String fallback) {
        return value instanceof String s && !s.isBlank() ? s : fallback;
    }

    private String sentenceOrFallback(Double value, java.util.function.Function<Double, String> format) {
        return value == null ? "관련 수치를 아직 확인할 수 없습니다." : format.apply(value);
    }

    private String intervalSentence(Double lo, Double hi, String template) {
        return (lo == null || hi == null)
                ? "예측 구간을 아직 확인할 수 없습니다."
                : template.formatted(lo, hi);
    }
}
