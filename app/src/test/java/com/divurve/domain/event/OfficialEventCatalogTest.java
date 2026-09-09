package com.divurve.domain.event;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Constructor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link OfficialEventCatalog} 대조표 테스트 (이슈 #163, 이슈 #187 로 키 변경).
 *
 * <p>이 표가 중요도의 유일한 출처다 — 공식 캘린더는 중요도를 주지 않고, LLM 에 묻지도 않는다.
 * 그리고 이제 <b>조회 대상 목록</b>이기도 하다: 표에 없는 지표는 아예 요청하지 않는다.
 */
@DisplayName("OfficialEventCatalog")
class OfficialEventCatalogTest {

    @Test
    @DisplayName("가져올 지표를 목록으로 낸다")
    void 지표_목록을_낸다() {
        assertThat(OfficialEventCatalog.entries()).isNotEmpty();
    }

    @Test
    @DisplayName("캘린더 식별자가 서로 겹치지 않는다 — 같은 지표를 두 번 조회하지 않는다")
    void 식별자가_겹치지_않는다() {
        assertThat(OfficialEventCatalog.entries())
                .extracting(OfficialEventCatalog.Entry::calendarKey)
                .doesNotHaveDuplicates();
    }

    /**
     * 이름이 아니라 식별자로 조회하는 것이 이슈 #187 의 요점이다. 식별자에 이름이 섞여 들어오면
     * FRED 가 이름을 바꾸는 순간 그 지표가 조용히 사라진다 — 예전에 산업생산이 그랬다.
     */
    @Test
    @DisplayName("식별자는 숫자다 — 이름이 아니다")
    void 식별자는_숫자다() {
        assertThat(OfficialEventCatalog.entries())
                .allSatisfy(entry ->
                        assertThat(entry.calendarKey()).matches("\\d+"));
    }

    @Test
    @DisplayName("모든 항목의 중요도는 econ_events CHECK 제약(1~3) 안이고 응답 어휘로 옮겨진다")
    void 중요도가_제약_안이다() {
        assertThat(OfficialEventCatalog.entries()).allSatisfy(entry -> {
            assertThat(entry.impact()).isBetween((short) 1, (short) 3);
            // 표 사이에 구멍이 있으면 화면에서 중요도가 통째로 사라진다.
            assertThat(EconEventVocabulary.toImportance(entry.impact())).isNotNull();
        });
    }

    @Test
    @DisplayName("표시 제목이 비어 있지 않고 서로 겹치지 않는다")
    void 제목이_고유하다() {
        assertThat(OfficialEventCatalog.entries())
                .extracting(OfficialEventCatalog.Entry::title)
                .doesNotHaveDuplicates()
                .allSatisfy(title -> assertThat(title).isNotBlank());
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
