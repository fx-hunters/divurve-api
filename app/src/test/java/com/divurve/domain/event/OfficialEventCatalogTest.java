package com.divurve.domain.event;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Constructor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * {@link OfficialEventCatalog} 대조표 테스트 (이슈 #163).
 *
 * <p>이 표가 중요도의 유일한 출처다 — 공식 캘린더는 중요도를 주지 않고, LLM 에 묻지도 않는다.
 * 표에 없는 이름을 저장하지 않는 동작이 핵심이라 그 경로를 특히 본다.
 */
@DisplayName("OfficialEventCatalog")
class OfficialEventCatalogTest {

    @Test
    @DisplayName("표에 있는 이름은 표시 제목과 중요도를 답한다")
    void 표에_있으면_답한다() {
        OfficialEventCatalog.Entry entry =
                OfficialEventCatalog.find("Consumer Price Index").orElseThrow();

        assertThat(entry.title()).isEqualTo("미국 소비자물가지수(CPI) 발표");
        assertThat(entry.impact()).isEqualTo((short) 3);
    }

    @Test
    @DisplayName("앞뒤 공백은 흡수한다")
    void 공백을_흡수한다() {
        assertThat(OfficialEventCatalog.find("  Employment Situation  ")).isPresent();
    }

    @ParameterizedTest
    @ValueSource(strings = {"Unknown Release", "consumer price index", ""})
    @DisplayName("표에 없는 이름은 빈 값 — 중요도를 지어내지 않는다")
    void 표에_없으면_빈_값이다(String name) {
        assertThat(OfficialEventCatalog.find(name)).isEmpty();
    }

    @Test
    @DisplayName("이름이 null 이면 빈 값")
    void null이면_빈_값이다() {
        assertThat(OfficialEventCatalog.find(null)).isEmpty();
    }

    @Test
    @DisplayName("모든 항목의 중요도는 econ_events CHECK 제약(1~3) 안이다")
    void 중요도는_제약_안이다() {
        for (String name : new String[] {
                "Consumer Price Index", "Employment Situation", "Gross Domestic Product",
                "Personal Income and Outlays", "Producer Price Index",
                "Advance Monthly Sales for Retail and Food Services",
                "Job Openings and Labor Turnover Survey",
                "Industrial Production and Capacity Utilization"}) {
            OfficialEventCatalog.Entry entry = OfficialEventCatalog.find(name).orElseThrow(
                    () -> new AssertionError("표에 없는 이름: " + name));
            assertThat(entry.impact()).isBetween((short) 1, (short) 3);
            assertThat(entry.title()).isNotBlank();
            // 조회 시 응답 어휘로 옮겨질 수 있어야 한다 — 표 사이에 구멍이 있으면 화면에서 사라진다.
            assertThat(EconEventVocabulary.toImportance(entry.impact())).isNotNull();
        }
    }

    @Test
    @DisplayName("인스턴스를 만들 수 없다 — 상태 없는 표다")
    void 인스턴스를_만들_수_없다() throws Exception {
        Constructor<OfficialEventCatalog> constructor =
                OfficialEventCatalog.class.getDeclaredConstructor();
        constructor.setAccessible(true);

        assertThat(constructor.newInstance()).isNotNull();
    }
}
