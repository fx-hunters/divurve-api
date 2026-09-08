package com.divurve.infra.macro;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.divurve.domain.port.OfficialEventCalendarSource.OfficialEvent;
import java.time.LocalDate;
import java.util.List;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * {@link FredReleaseCalendarSource} — FRED 발표 일정 조회 (이슈 #163).
 *
 * <p>가장 중요한 단정은 {@link #미래_일정을_포함해_요청한다} 다. {@code
 * include_release_dates_with_no_data} 의 기본값 false 는 <b>미래 발표일을 제외</b>하는데,
 * 이 어댑터가 원하는 것이 정확히 그 미래 일정이다. 이 파라미터가 빠지면 응답이 과거로만
 * 채워지고 아무도 눈치채지 못한다 — 에러가 아니라 빈 결과이기 때문이다.
 */
@DisplayName("FredReleaseCalendarSource")
class FredReleaseCalendarSourceTest {

    private static final LocalDate FROM = LocalDate.of(2026, 9, 9);
    private static final LocalDate TO = FROM.plusDays(180);

    private RestClient.Builder builder;
    private MockRestServiceServer server;
    private FredProperties props;

    @BeforeEach
    void setUp() {
        builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        props = new FredProperties(
                "https://api.stlouisfed.org/fred", "TEST_KEY", false, "0 10 4 * * *");
    }

    private FredReleaseCalendarSource source() {
        return new FredReleaseCalendarSource(builder.build(), props);
    }

    @Test
    @DisplayName("미래 일정을 포함해 요청한다 — 기본값은 미래를 빼버린다")
    void 미래_일정을_포함해_요청한다() {
        server.expect(requestTo(
                Matchers.containsString("include_release_dates_with_no_data=true")))
            .andExpect(requestTo(Matchers.containsString("realtime_start=2026-09-09")))
            .andExpect(requestTo(Matchers.containsString("realtime_end=2027-03-08")))
            .andExpect(requestTo(Matchers.containsString("sort_order=asc")))
            .andExpect(requestTo(Matchers.containsString("api_key=TEST_KEY")))
            .andRespond(withSuccess("""
                {"release_dates":[
                  {"release_id":10,"release_name":"Consumer Price Index","date":"2026-09-15"}
                ]}
                """, MediaType.APPLICATION_JSON));

        List<OfficialEvent> events = source().fetchScheduled(FROM, TO);

        assertThat(events).hasSize(1);
        assertThat(events.get(0).date()).isEqualTo(LocalDate.of(2026, 9, 15));
        assertThat(events.get(0).region()).isEqualTo("US");
        assertThat(events.get(0).name()).isEqualTo("Consumer Price Index");
        assertThat(events.get(0).sourceUrl()).isEqualTo("https://fred.stlouisfed.org/releases/10");
        server.verify();
    }

    @Test
    @DisplayName("이름이나 날짜가 빠진 행은 버린다 — 반쪽 일정을 만들지 않는다")
    void 불완전한_행은_버린다() {
        server.expect(requestTo(Matchers.containsString("/releases/dates")))
            .andRespond(withSuccess("""
                {"release_dates":[
                  {"release_id":1,"release_name":null,"date":"2026-09-15"},
                  {"release_id":2,"release_name":"Employment Situation","date":null},
                  {"release_id":10,"release_name":"Consumer Price Index","date":"2026-09-15"}
                ]}
                """, MediaType.APPLICATION_JSON));

        assertThat(source().fetchScheduled(FROM, TO))
            .extracting(OfficialEvent::name)
            .containsExactly("Consumer Price Index");
        server.verify();
    }

    @Test
    @DisplayName("release_id 가 없으면 출처 URL 은 비운다 — 지어내지 않는다")
    void release_id가_없으면_URL이_없다() {
        server.expect(requestTo(Matchers.containsString("/releases/dates")))
            .andRespond(withSuccess("""
                {"release_dates":[
                  {"release_name":"Consumer Price Index","date":"2026-09-15"}
                ]}
                """, MediaType.APPLICATION_JSON));

        assertThat(source().fetchScheduled(FROM, TO).get(0).sourceUrl()).isNull();
        server.verify();
    }

    @Test
    @DisplayName("일정이 없으면 빈 목록")
    void 일정이_없으면_빈_목록이다() {
        server.expect(requestTo(Matchers.containsString("/releases/dates")))
            .andRespond(withSuccess("{\"release_dates\":[]}", MediaType.APPLICATION_JSON));

        assertThat(source().fetchScheduled(FROM, TO)).isEmpty();
        server.verify();
    }

    @Test
    @DisplayName("응답 본문이 비어도 빈 목록 — 터지지 않는다")
    void 본문이_없어도_빈_목록이다() {
        server.expect(requestTo(Matchers.containsString("/releases/dates")))
            .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        assertThat(source().fetchScheduled(FROM, TO)).isEmpty();
        server.verify();
    }

    @Test
    @DisplayName("API 키가 없으면 FRED 를 부르기 전에 멈춘다")
    void 키가_없으면_부르지_않는다() {
        props = new FredProperties("https://api.stlouisfed.org/fred", "  ", false, "0 10 4 * * *");

        assertThatThrownBy(() -> source().fetchScheduled(FROM, TO))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("FRED API key");
        server.verify();
    }
}
