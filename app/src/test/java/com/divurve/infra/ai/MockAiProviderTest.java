package com.divurve.infra.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.divurve.domain.ai.ExplainSurface;
import com.divurve.domain.port.AiProvider;
import com.divurve.domain.port.AiProvider.ExplainContext;
import com.divurve.domain.settings.UserSettingsService;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * {@link MockAiProvider} 고정 스키마·그라운딩 테스트 (API 명세 v2 §5.12).
 * facts 에 없는 사실을 지어내지 않는지(리뷰 B M3), forecast_summary 가 항상 4문장인지 검증한다.
 */
class MockAiProviderTest {

    private static final Map<String, Object> FORECAST_FACTS = Map.of(
            "pair_code", "USDKRW",
            "current_rate", 1382.40,
            "interval_80", Map.of("lo", 1346.0, "hi", 1431.0),
            "vol_percentile_5y", 0.72,
            "per_1pct_krw", 157900);

    private final MockAiProvider provider = new MockAiProvider();

    @Test
    void explain_forecast_summary는_simple_수준에서_4문장을_반환한다() {
        AiProvider.ExplainResult result = provider.explain(new ExplainContext(
                ExplainSurface.FORECAST_SUMMARY.code(), FORECAST_FACTS,
                UserSettingsService.EXPLAIN_LEVEL_SIMPLE, "plain"));

        assertThat(result.sentences()).hasSize(4);
    }

    @Test
    void explain_forecast_summary는_standard_수준에서도_4문장을_반환한다() {
        AiProvider.ExplainResult result = provider.explain(new ExplainContext(
                ExplainSurface.FORECAST_SUMMARY.code(), FORECAST_FACTS,
                UserSettingsService.EXPLAIN_LEVEL_STANDARD, "finance"));

        assertThat(result.sentences()).hasSize(4);
        assertThat(String.join(" ", result.sentences())).contains("1382.40");
    }

    @Test
    void explain_forecast_summary는_detailed_수준에서도_4문장을_반환한다() {
        AiProvider.ExplainResult result = provider.explain(new ExplainContext(
                ExplainSurface.FORECAST_SUMMARY.code(), FORECAST_FACTS,
                UserSettingsService.EXPLAIN_LEVEL_DETAILED, "dev"));

        assertThat(result.sentences()).hasSize(4);
    }

    @Test
    void explain_forecast_summary는_급변_국면이면_불확실성_문장을_강화한다() {
        Map<String, Object> facts = new java.util.HashMap<>(FORECAST_FACTS);
        facts.put("regime", "elevated");

        AiProvider.ExplainResult result = provider.explain(new ExplainContext(
                ExplainSurface.FORECAST_SUMMARY.code(), facts,
                UserSettingsService.EXPLAIN_LEVEL_SIMPLE, "plain"));

        assertThat(result.sentences()).hasSize(4);
        assertThat(String.join(" ", result.sentences())).contains("변동성이 커진 구간");
    }

    @Test
    void explain_forecast_summary는_구간이_없으면_지어내지_않고_안내문을_반환한다() {
        AiProvider.ExplainResult result = provider.explain(new ExplainContext(
                ExplainSurface.FORECAST_SUMMARY.code(), Map.of(),
                UserSettingsService.EXPLAIN_LEVEL_SIMPLE, "plain"));

        assertThat(result.sentences()).hasSize(4);
        assertThat(String.join(" ", result.sentences())).doesNotContain("null");
    }

    @Test
    void explain_일반_화면은_facts에_있는_값만_문장으로_옮긴다() {
        AiProvider.ExplainResult result = provider.explain(new ExplainContext(
                "profile_fit", Map.of("grade", "balanced"),
                UserSettingsService.EXPLAIN_LEVEL_SIMPLE, "plain"));

        assertThat(result.sentences()).isNotEmpty();
        assertThat(String.join(" ", result.sentences())).contains("balanced");
    }

    @Test
    void explain_일반_화면은_facts가_비어있으면_지어내지_않고_안내문을_반환한다() {
        AiProvider.ExplainResult result = provider.explain(new ExplainContext(
                "attention", Map.of(), UserSettingsService.EXPLAIN_LEVEL_SIMPLE, "plain"));

        assertThat(result.sentences()).containsExactly("표시할 세부 수치가 아직 없습니다.");
    }

    @Test
    void explain_일반_화면은_regime이_급변이면_불확실성_문장을_추가한다() {
        AiProvider.ExplainResult result = provider.explain(new ExplainContext(
                "attention", Map.of("regime", "stress", "score", 3),
                UserSettingsService.EXPLAIN_LEVEL_SIMPLE, "plain"));

        assertThat(result.sentences()).anyMatch(s -> s.contains("변동성이 커진 구간"));
    }

    @Test
    void explain_context가_null이면_NullPointerException을_던진다() {
        assertThatThrownBy(() -> provider.explain(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void explain_facts가_null이면_NullPointerException을_던진다() {
        assertThatThrownBy(() -> provider.explain(
                new ExplainContext("profile_fit", null, "simple", "plain")))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void explain_여러_번_호출해도_같은_facts에는_같은_구조를_반환한다() {
        ExplainContext context = new ExplainContext(
                ExplainSurface.FORECAST_SUMMARY.code(), FORECAST_FACTS,
                UserSettingsService.EXPLAIN_LEVEL_STANDARD, "finance");

        AiProvider.ExplainResult first = provider.explain(context);
        AiProvider.ExplainResult second = provider.explain(context);

        assertThat(first.sentences()).isEqualTo(second.sentences());
    }

    @Test
    void explain_숫자가_있는_facts는_모두_서술에_반영된다() {
        AiProvider.ExplainResult result = provider.explain(new ExplainContext(
                "xray_attribution", Map.of("total_return", 5.5),
                UserSettingsService.EXPLAIN_LEVEL_SIMPLE, "plain"));

        assertThat(String.join(" ", result.sentences())).contains("5.5");
    }

    @Test
    void explain_정수형_숫자는_소수점_없이_서술된다() {
        AiProvider.ExplainResult result = provider.explain(new ExplainContext(
                "xray_attribution", Map.of("holding_count", 3.0),
                UserSettingsService.EXPLAIN_LEVEL_SIMPLE, "plain"));

        assertThat(String.join(" ", result.sentences())).contains("3").doesNotContain("3.0");
    }

    @Test
    void explain_중첩된_맵_사실도_지어내지_않고_그대로_옮긴다() {
        AiProvider.ExplainResult result = provider.explain(new ExplainContext(
                "xray_attribution",
                Map.of("interval_80", Map.of("lo", 1346.0, "code", "USDKRW")),
                UserSettingsService.EXPLAIN_LEVEL_SIMPLE, "plain"));

        String joined = String.join(" ", result.sentences());
        assertThat(joined).contains("1346").contains("USDKRW");
    }

    @Test
    void explain_facts에_값이_null인_키는_건너뛴다() {
        Map<String, Object> facts = new java.util.HashMap<>();
        facts.put("grade", "balanced");
        facts.put("unresolved", null);

        AiProvider.ExplainResult result = provider.explain(new ExplainContext(
                "profile_fit", facts, UserSettingsService.EXPLAIN_LEVEL_SIMPLE, "plain"));

        assertThat(result.sentences()).hasSize(1);
        assertThat(result.sentences().get(0)).contains("balanced");
    }

    @Test
    void explain_forecast_summary는_detailed_수준_급변에서도_4문장을_반환한다() {
        Map<String, Object> facts = new java.util.HashMap<>(FORECAST_FACTS);
        facts.put("regime", "stress");

        AiProvider.ExplainResult result = provider.explain(new ExplainContext(
                ExplainSurface.FORECAST_SUMMARY.code(), facts,
                UserSettingsService.EXPLAIN_LEVEL_DETAILED, "dev"));

        assertThat(result.sentences()).hasSize(4);
        assertThat(String.join(" ", result.sentences())).contains("변동성이 커진 구간");
    }

    @Test
    void explain_forecast_summary는_pair_code가_공백이면_기본값으로_대체한다() {
        Map<String, Object> facts = new java.util.HashMap<>(FORECAST_FACTS);
        facts.put("pair_code", "   ");

        AiProvider.ExplainResult result = provider.explain(new ExplainContext(
                ExplainSurface.FORECAST_SUMMARY.code(), facts,
                UserSettingsService.EXPLAIN_LEVEL_SIMPLE, "plain"));

        assertThat(String.join(" ", result.sentences())).contains("환율은(는) 현재");
    }

    @Test
    void explain_forecast_summary는_구간의_한쪽만_없어도_지어내지_않는다() {
        Map<String, Object> facts = new java.util.HashMap<>(FORECAST_FACTS);
        facts.put("interval_80", Map.of("lo", 1346.0));

        AiProvider.ExplainResult result = provider.explain(new ExplainContext(
                ExplainSurface.FORECAST_SUMMARY.code(), facts,
                UserSettingsService.EXPLAIN_LEVEL_SIMPLE, "plain"));

        assertThat(result.sentences()).hasSize(4);
        assertThat(String.join(" ", result.sentences())).contains("아직 확인할 수 없습니다");
    }
}
