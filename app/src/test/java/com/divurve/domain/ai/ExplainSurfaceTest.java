package com.divurve.domain.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link ExplainSurface} 테스트 (이슈 #135, 이슈 #153).
 *
 * <p>이 enum 이 존재하는 이유가 "허용 여부·문장 수·프롬프트 맥락이 한 곳에서 나온다" 이므로,
 * 테스트도 그 세 가지가 <b>모든 화면에</b> 갖춰져 있는지를 본다. 값 하나를 추가하면서 문장 수나
 * 초점을 빠뜨리면 실 API 응답 검증이 그 화면에서만 조용히 무의미해진다.
 */
class ExplainSurfaceTest {

    @Test
    @DisplayName("프론트가 이미 배포한 화면과 대시보드 개편이 쓸 화면을 모두 안다")
    void knowsEveryScreenTheClientNeeds() {
        assertThat(ExplainSurface.codes())
                .containsExactly(
                        "forecast_summary",
                        "home_market_summary",
                        "home_fx_status",
                        "home_goals",
                        "home_calendar",
                        "xray_exposure",
                        "xray_fitness");
    }

    @Test
    @DisplayName("forecast_summary 만 4문장이고 나머지는 카드 분량인 3문장이다")
    void forecastKeepsItsFourSentenceContract() {
        // 4문장은 요구사항이 정한 값이다(FR-FC-07·FR-AI-04). 나머지 3문장은 이 enum 이 정한
        // 규약이므로, 바꾸려면 여기만 고치면 된다.
        assertThat(ExplainSurface.FORECAST_SUMMARY.sentenceCount()).isEqualTo(4);
        assertThat(ExplainSurface.values())
                .filteredOn(s -> s != ExplainSurface.FORECAST_SUMMARY)
                .allSatisfy(s -> assertThat(s.sentenceCount()).isEqualTo(3));
    }

    @Test
    @DisplayName("모든 화면이 문장 수와 서술 초점을 갖춘다")
    void everySurfaceCarriesItsContract() {
        assertThat(ExplainSurface.values()).allSatisfy(surface -> {
            assertThat(surface.code()).isNotBlank();
            assertThat(surface.sentenceCount()).isPositive();
            assertThat(surface.focus()).isNotBlank();
        });
    }

    @Test
    @DisplayName("코드로 화면을 찾는다 — 모르는 값과 null 은 빈 결과다")
    void lookupByCode() {
        assertThat(ExplainSurface.of("xray_fitness")).contains(ExplainSurface.XRAY_FITNESS);
        assertThat(ExplainSurface.of("profile_fit")).isEmpty();
        assertThat(ExplainSurface.of(null)).isEmpty();
    }

    @Test
    @DisplayName("허용 목록은 선언 순서를 지킨다 — 거절 메시지에 그대로 실린다")
    void codesKeepDeclarationOrder() {
        // 알파벳순으로 늘어놓으면 홈·X-Ray 화면이 뒤섞여, 400 메시지를 받은 프론트 개발자가
        // 무엇이 어느 화면인지 읽어낼 수 없다.
        assertThat(List.copyOf(ExplainSurface.codes()).subList(0, 2))
                .containsExactly("forecast_summary", "home_market_summary");
    }

    @Test
    @DisplayName("valueOf 로도 같은 값에 닿는다")
    void valueOfResolvesTheSameConstant() {
        assertThat(ExplainSurface.valueOf("HOME_CALENDAR"))
                .isSameAs(ExplainSurface.HOME_CALENDAR);
    }
}
