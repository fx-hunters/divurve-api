package com.divurve.infra.event;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link NoOpRawArticleSource} 테스트 (이슈 #184).
 *
 * <p>이 클래스의 값어치는 <b>아무것도 돌려주지 않는다</b>는 것 하나다 — 실 원문 소스가 없는
 * 동안 배치를 켜도 가짜 일정이 적재되지 않아야 한다. 예전 구현은 시연용 예시 기사를 돌려줬고,
 * 그것이 사용자 화면에 공식 일정처럼 나갔다.
 */
@DisplayName("NoOpRawArticleSource")
class NoOpRawArticleSourceTest {

    @Test
    @DisplayName("원문을 공급하지 않는다 — 배치를 켜도 적재될 것이 없다")
    void 원문을_공급하지_않는다() {
        assertThat(new NoOpRawArticleSource().fetchRecent()).isEmpty();
    }
}
