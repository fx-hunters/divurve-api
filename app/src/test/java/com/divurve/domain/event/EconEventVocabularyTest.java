package com.divurve.domain.event;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Constructor;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * {@link EconEventVocabulary} 변환표 테스트 (이슈 #162).
 *
 * <p>이 표는 저장 어휘와 응답 어휘를 잇는 유일한 지점이다 — 여기가 틀리면 화면에 엉뚱한 통화가
 * 붙는다. 특히 {@link #허용_지역은_모두_통화로_옮겨진다} 는 {@link EconEventValidator} 가 저장을
 * 허용하는 지역과 이 표의 정합을 강제한다. 한쪽만 늘리면 조회에서 값을 잃는다.
 */
class EconEventVocabularyTest {

    @ParameterizedTest
    @CsvSource({"US,USD", "EU,EUR", "JP,JPY", "KR,KRW", "GB,GBP", "CN,CNY"})
    @DisplayName("지역을 영향 통화로 옮긴다")
    void 지역을_통화로_옮긴다(String region, String expected) {
        assertThat(EconEventVocabulary.toCurrencyCode(region)).isEqualTo(expected);
    }

    @Test
    @DisplayName("소문자·공백이 섞여도 같은 통화로 옮긴다 — 저장분 표기 흔들림을 흡수한다")
    void 표기가_흔들려도_같은_통화다() {
        assertThat(EconEventVocabulary.toCurrencyCode("  us ")).isEqualTo("USD");
    }

    @ParameterizedTest
    @ValueSource(strings = {"GLOBAL", "XX"})
    @DisplayName("귀속 통화가 없거나 표에 없는 지역은 null — 없는 통화를 지어내지 않는다")
    void 귀속_통화가_없으면_null이다(String region) {
        assertThat(EconEventVocabulary.toCurrencyCode(region)).isNull();
    }

    @Test
    @DisplayName("지역이 null 이면 null")
    void 지역이_null이면_null이다() {
        assertThat(EconEventVocabulary.toCurrencyCode(null)).isNull();
    }

    @ParameterizedTest
    @CsvSource({"3,High", "2,Medium", "1,Low"})
    @DisplayName("영향도를 표시 어휘로 옮긴다 — 3 이 높음이다")
    void 영향도를_어휘로_옮긴다(short impact, String expected) {
        assertThat(EconEventVocabulary.toImportance(impact)).isEqualTo(expected);
    }

    @ParameterizedTest
    @ValueSource(shorts = {0, 4})
    @DisplayName("저장 제약을 벗어난 영향도는 null — 없는 등급을 지어내지 않는다")
    void 범위_밖_영향도는_null이다(short impact) {
        assertThat(EconEventVocabulary.toImportance(impact)).isNull();
    }

    /**
     * {@code EconEventValidator} 가 저장을 허용하는 지역은 모두 이 표가 답할 수 있어야 한다.
     * {@code GLOBAL} 만 예외로, 의도적으로 통화가 없다.
     */
    @Test
    @DisplayName("허용 지역은 GLOBAL 을 빼고 모두 통화로 옮겨진다")
    void 허용_지역은_모두_통화로_옮겨진다() {
        Set<String> allowed = Set.of("US", "EU", "JP", "KR", "CN", "GB");

        assertThat(allowed).allSatisfy(region ->
                assertThat(EconEventVocabulary.toCurrencyCode(region)).isNotNull());
    }

    @Test
    @DisplayName("인스턴스를 만들 수 없다 — 상태 없는 변환표다")
    void 인스턴스를_만들_수_없다() throws Exception {
        Constructor<EconEventVocabulary> constructor =
                EconEventVocabulary.class.getDeclaredConstructor();
        constructor.setAccessible(true);

        assertThat(constructor.newInstance()).isNotNull();
    }
}
