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
 * {@link FredReleaseCalendarSource} — FRED 발표 일정 조회 (이슈 #163, 이슈 #187 로 방식 변경).
 *
 * <p>세 파라미터가 각각 다른 방식으로 조회를 망가뜨린다. 셋 다 <b>에러가 아니라 조용한 빈
 * 결과나 잘림</b>으로 나타나므로 요청 자체를 단정한다.
 * <ul>
 *   <li>{@code include_release_dates_with_no_data} — 기본값 false 는 미래 발표일을 뺀다</li>
 *   <li>{@code realtime_start} — 기본값이 1776-07-04 라 생략하면 과거 전부가 온다</li>
 *   <li>{@code release_id} — 지목하지 않으면 331개 릴리스가 전부 와서 상한에 잘린다</li>
 * </ul>
 */
@DisplayName("FredReleaseCalendarSource")
class FredReleaseCalendarSourceTest {

    private static final String CPI = "10";
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
    @DisplayName("지표를 지목하고 미래 일정을 포함해 요청한다")
    void 지표를_지목해_요청한다() {
        server.expect(requestTo(Matchers.containsString("/release/dates")))
            .andExpect(requestTo(Matchers.containsString("release_id=10")))
            .andExpect(requestTo(
                    Matchers.containsString("include_release_dates_with_no_data=true")))
            .andExpect(requestTo(Matchers.containsString("realtime_start=2026-09-09")))
            .andExpect(requestTo(Matchers.containsString("realtime_end=2027-03-08")))
            .andExpect(requestTo(Matchers.containsString("sort_order=asc")))
            .andExpect(requestTo(Matchers.containsString("api_key=TEST_KEY")))
            .andRespond(withSuccess("""
                {"release_dates":[
                  {"release_id":10,"date":"2026-09-11"},
                  {"release_id":10,"date":"2026-10-13"}
                ]}
                """, MediaType.APPLICATION_JSON));

        List<OfficialEvent> events = source().fetchScheduled(CPI, FROM, TO);

        assertThat(events).hasSize(2);
        assertThat(events.get(0).date()).isEqualTo(LocalDate.of(2026, 9, 11));
        assertThat(events.get(0).region()).isEqualTo("US");
        assertThat(events.get(0).sourceUrl())
                .isEqualTo("https://fred.stlouisfed.org/release?rid=10");
        server.verify();
    }

    @Test
    @DisplayName("날짜가 빠진 행은 버린다 — 반쪽 일정을 만들지 않는다")
    void 날짜가_없는_행은_버린다() {
        server.expect(requestTo(Matchers.containsString("/release/dates")))
            .andRespond(withSuccess("""
                {"release_dates":[
                  {"release_id":10,"date":null},
                  {"release_id":10,"date":"2026-09-11"}
                ]}
                """, MediaType.APPLICATION_JSON));

        assertThat(source().fetchScheduled(CPI, FROM, TO))
            .extracting(OfficialEvent::date)
            .containsExactly(LocalDate.of(2026, 9, 11));
        server.verify();
    }

    @Test
    @DisplayName("일정이 없으면 빈 목록")
    void 일정이_없으면_빈_목록이다() {
        server.expect(requestTo(Matchers.containsString("/release/dates")))
            .andRespond(withSuccess("{\"release_dates\":[]}", MediaType.APPLICATION_JSON));

        assertThat(source().fetchScheduled(CPI, FROM, TO)).isEmpty();
        server.verify();
    }

    @Test
    @DisplayName("응답 본문이 비어도 빈 목록 — 터지지 않는다")
    void 본문이_없어도_빈_목록이다() {
        server.expect(requestTo(Matchers.containsString("/release/dates")))
            .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        assertThat(source().fetchScheduled(CPI, FROM, TO)).isEmpty();
        server.verify();
    }

    @Test
    @DisplayName("API 키가 없으면 FRED 를 부르기 전에 멈춘다")
    void 키가_없으면_부르지_않는다() {
        props = new FredProperties("https://api.stlouisfed.org/fred", "  ", false, "0 10 4 * * *");

        assertThatThrownBy(() -> source().fetchScheduled(CPI, FROM, TO))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("FRED API key");
        server.verify();
    }

    /**
     * 이슈 #187 의 재발 방지선이다. 예전 구현은 상한에 잘려도 <b>아무 신호가 없어</b> 구간의
     * 5분의 1만 들어온 것을 몇 주 동안 아무도 몰랐다. 지표 하나가 이만큼 발표될 리 없으므로,
     * 이 수에 닿는 것 자체가 요청이 의도와 다르다는 뜻이다.
     */
    @Test
    @DisplayName("응답이 상한에 닿으면 받은 것을 그대로 돌려주되 신호를 남긴다")
    void 상한에_닿아도_받은_것을_돌려준다() {
        String rows = java.util.stream.IntStream.range(0, 1000)
                .mapToObj(i -> "{\"release_id\":10,\"date\":\"2026-09-11\"}")
                .collect(java.util.stream.Collectors.joining(","));
        server.expect(requestTo(Matchers.containsString("/release/dates")))
            .andRespond(withSuccess(
                    "{\"release_dates\":[" + rows + "]}", MediaType.APPLICATION_JSON));

        assertThat(source().fetchScheduled(CPI, FROM, TO)).hasSize(1000);
        server.verify();
    }
}
