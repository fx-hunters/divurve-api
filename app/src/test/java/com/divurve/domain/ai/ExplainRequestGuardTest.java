package com.divurve.domain.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.divurve.common.exception.InvalidRequestException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link ExplainRequestGuard} 테스트 (이슈 #139).
 *
 * <p>두 가지를 고정한다 — (a) 상한과 어휘를 넘긴 요청은 <b>프로바이더 호출 전에</b> 400 이다,
 * (b) 내용이 같은 {@code facts} 는 키 순서가 어떻든 <b>같은 문자열</b>로 정규화된다. (b) 가 깨지면
 * 캐시가 조용히 빗나가는데, 그 상태는 지표에서 "히트율 0%" 로만 보여 원인을 찾기 어렵다.
 */
class ExplainRequestGuardTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 실제 예측 화면이 보내는 모양 그대로 — 상한이 정상 요청을 막지 않는지 보는 기준이다. */
    private static final Map<String, Object> REAL_FACTS = Map.of(
            "pair_code", "USDKRW",
            "current_rate", 1382.40,
            "interval_80", Map.of("lo", 1346.0, "hi", 1431.0),
            "vol_percentile_5y", 0.72,
            "per_1pct_krw", 157900,
            "regime", "elevated");

    private static ExplainRequestGuard defaults() {
        return new ExplainRequestGuard(MAPPER,
                Integer.parseInt(ExplainRequestGuard.DEFAULT_MAX_FACTS_CHARS),
                Integer.parseInt(ExplainRequestGuard.DEFAULT_MAX_FACTS_NODES),
                Integer.parseInt(ExplainRequestGuard.DEFAULT_MAX_FACTS_DEPTH));
    }

    private static ExplainRequestGuard guard(int chars, int nodes, int depth) {
        return new ExplainRequestGuard(MAPPER, chars, nodes, depth);
    }

    @Test
    @DisplayName("허용 목록은 프론트가 실제로 보내는 두 화면을 담는다")
    void allowedSurfacesCoverEveryScreenTheClientActuallyCalls() {
        // 백엔드 코드에는 forecast_summary 상수만 있어 하나로 보이지만, 홈 시장 요약도
        // /ai/explain 을 부른다(divurve-web market-summary-section.tsx). 하나만 허용하면
        // 홈 화면의 설명이 즉시 400 으로 깨진다 — 이 단정이 그 회귀를 막는다.
        assertThat(ExplainRequestGuard.ALLOWED_SURFACES)
                .containsExactlyInAnyOrder("forecast_summary", "home_market_summary");
    }

    @Test
    @DisplayName("기본 상한은 실제 화면의 facts 를 막지 않는다")
    void realWorldFactsPassTheDefaultLimits() {
        String canonical = defaults().canonicalize("forecast_summary", REAL_FACTS);

        assertThat(canonical).contains("\"pair_code\":\"USDKRW\"");
        assertThat(canonical.length())
                .as("실제 요청은 기본 상한(4096자)에 한참 못 미친다")
                .isLessThan(Integer.parseInt(ExplainRequestGuard.DEFAULT_MAX_FACTS_CHARS) / 4);
    }

    @Test
    @DisplayName("키 순서가 달라도 같은 문자열로 정규화된다 — 중첩까지")
    void canonicalFormIsIndependentOfMapIterationOrder() {
        Map<String, Object> nestedAscending = new LinkedHashMap<>();
        nestedAscending.put("x", 1);
        nestedAscending.put("m", 2);
        Map<String, Object> ascending = new LinkedHashMap<>();
        ascending.put("a", 1);
        ascending.put("b", nestedAscending);

        Map<String, Object> nestedDescending = new LinkedHashMap<>();
        nestedDescending.put("m", 2);
        nestedDescending.put("x", 1);
        Map<String, Object> descending = new LinkedHashMap<>();
        descending.put("b", nestedDescending);
        descending.put("a", 1);

        ExplainRequestGuard guard = defaults();

        assertThat(guard.canonicalize("forecast_summary", ascending))
                .isEqualTo(guard.canonicalize("forecast_summary", descending))
                .isEqualTo("{\"a\":1,\"b\":{\"m\":2,\"x\":1}}");
    }

    @Test
    @DisplayName("전역 매퍼가 들여쓰기를 켜도 정규화 결과는 압축된 형태다")
    void canonicalFormIgnoresGlobalIndentSetting() {
        // 캐시 키가 전역 Jackson 설정에 매달려 있으면, 무관한 설정 변경 한 줄이 캐시를 통째로
        // 무효화하고 그 사실은 아무 로그에도 남지 않는다.
        ObjectMapper indenting = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

        String canonical = new ExplainRequestGuard(indenting, 4096, 128, 4)
                .canonicalize("forecast_summary", Map.of("a", 1));

        assertThat(canonical).isEqualTo("{\"a\":1}");
    }

    @Test
    @DisplayName("허용 목록에 없는 surface 는 400 이고 메시지가 허용 값을 알려 준다")
    void unknownSurfaceIsRejectedWithTheAllowedVocabulary() {
        assertThatThrownBy(() -> defaults().canonicalize("anything", REAL_FACTS))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("anything")
                .hasMessageContaining("forecast_summary")
                .hasMessageContaining("home_market_summary");
    }

    @Test
    @DisplayName("거절 메시지는 긴 surface 를 통째로 되비추지 않는다")
    void rejectionMessageTruncatesAnOverlongSurface() {
        String overlong = "x".repeat(500);

        assertThatThrownBy(() -> defaults().canonicalize(overlong, REAL_FACTS))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("…")
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain(overlong));
    }

    @Test
    @DisplayName("정규화 길이가 상한을 넘으면 400 — 프로바이더를 부르기 전이다")
    void oversizedFactsAreRejectedBeforeAnyCall() {
        Map<String, Object> big = Map.of("blob", "y".repeat(200));

        assertThatThrownBy(() -> guard(64, 128, 4).canonicalize("forecast_summary", big))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("허용 64자")
                .satisfies(e ->
                        assertThat(((InvalidRequestException) e).getField()).isEqualTo("facts"));
    }

    @Test
    @DisplayName("길이는 통과하지만 항목이 너무 많은 넓고 얕은 맵도 막는다")
    void wideButShallowFactsAreRejectedByTheNodeLimit() {
        Map<String, Object> wide = new LinkedHashMap<>();
        IntStream.range(0, 20).forEach(i -> wide.put("k" + i, i));

        assertThatThrownBy(() -> guard(4096, 5, 4).canonicalize("forecast_summary", wide))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("허용 5개");
    }

    @Test
    @DisplayName("중첩이 허용 단계를 넘으면 400 — 경계 한 단계는 통과한다")
    void deeplyNestedFactsAreRejectedAtTheBoundary() {
        // facts 자체가 1단계다. depth=3 이면 컨테이너는 facts → a → b 까지 세 겹이 허용된다.
        Map<String, Object> justInside = Map.of("a", Map.of("b", Map.of("c", 1)));
        Map<String, Object> tooDeep = Map.of("a", Map.of("b", Map.of("c", Map.of("d", 1))));

        ExplainRequestGuard guard = guard(4096, 128, 3);

        assertThat(guard.canonicalize("forecast_summary", justInside))
                .isEqualTo("{\"a\":{\"b\":{\"c\":1}}}");
        assertThatThrownBy(() -> guard.canonicalize("forecast_summary", tooDeep))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("허용 3단계");
    }

    @Test
    @DisplayName("배열도 깊이와 항목 수에 함께 센다")
    void arraysCountTowardDepthAndNodes() {
        Map<String, Object> withArray = Map.of("xs", List.of(1, 2, 3, 4, 5));

        assertThatThrownBy(() -> guard(4096, 4, 4).canonicalize("forecast_summary", withArray))
                .as("배열을 세지 않으면 항목 상한을 배열 하나로 우회할 수 있다")
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("허용 4개");

        assertThatThrownBy(() -> guard(4096, 128, 1).canonicalize("forecast_summary", withArray))
                .as("배열도 컨테이너이므로 한 단계를 쓴다")
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("허용 1단계");
    }

    @Test
    @DisplayName("null 값을 담은 facts 도 정규화한다 — 스칼라로 센다")
    void nullValuesAreCountedAsScalars() {
        Map<String, Object> withNull = new LinkedHashMap<>();
        withNull.put("a", null);

        assertThat(defaults().canonicalize("forecast_summary", withNull))
                .isEqualTo("{\"a\":null}");
    }

    @Test
    @DisplayName("JSON 으로 직렬화할 수 없는 값은 500 이 아니라 400 이다")
    void unserializableValueIsAClientError() {
        Map<String, Object> broken = Map.of("weird", new Object());

        assertThatThrownBy(() -> defaults().canonicalize("forecast_summary", broken))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("표준 JSON");
    }

    @Test
    void 인자가_null_이면_실패한다() {
        assertThatThrownBy(() -> new ExplainRequestGuard(null, 4096, 128, 4))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> defaults().canonicalize(null, REAL_FACTS))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> defaults().canonicalize("forecast_summary", null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("기본 상한은 문서에 적은 값 그대로다")
    void defaultLimitsAreTheDocumentedOnes() {
        assertThat(ExplainRequestGuard.DEFAULT_MAX_FACTS_CHARS).isEqualTo("4096");
        assertThat(ExplainRequestGuard.DEFAULT_MAX_FACTS_NODES).isEqualTo("128");
        assertThat(ExplainRequestGuard.DEFAULT_MAX_FACTS_DEPTH).isEqualTo("4");
    }
}
