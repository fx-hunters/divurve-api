package com.divurve.infra.macro;

import com.divurve.common.architecture.ExternalAdapter;
import com.divurve.domain.port.OfficialEventCalendarSource;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;

/**
 * FRED 발표 일정 캘린더 어댑터 (이슈 #163, 이슈 #187 로 조회 방식 변경).
 *
 * <p><b>지표 하나씩 조회한다.</b> 예전에는 {@code releases/dates} 로 캘린더 전체를 한 번에
 * 받았는데, FRED 에 등록된 릴리스가 331개라 180일치가 수천 건이 되고 응답 상한 1000 에서
 * 잘렸다 — 실제 배포에서 요청 구간의 5분의 1(36일치)만 들어왔다. {@code release/dates} 로
 * 필요한 지표만 지목하면 릴리스당 6~12건이라 잘릴 일이 없고 버리는 양도 없다.
 *
 * <p><b>{@code include_release_dates_with_no_data=true} 가 핵심이다.</b> 기본값 false 는 아직
 * 데이터가 없는 <b>미래 발표일을 제외</b>한다 — 우리가 원하는 것이 정확히 그 미래 일정이므로
 * 이 값을 끄면 응답이 과거로만 채워진다.
 *
 * <p><b>{@code realtime_start} 를 반드시 넘긴다.</b> 이 파라미터의 기본값은 {@code 1776-07-04}
 * 라, 생략하면 건국 이래 전부를 받는다.
 *
 * <p>어댑터는 날짜와 출처 URL 만 옮긴다. 중요도와 표시 제목은 domain 의
 * {@code OfficialEventCatalog} 가 정한다 — 외부 어댑터가 등급을 만들지 않는다(CLAUDE.md §1).
 */
@ExternalAdapter
class FredReleaseCalendarSource implements OfficialEventCalendarSource {

    private static final Logger log = LoggerFactory.getLogger(FredReleaseCalendarSource.class);

    /** FRED 는 미국 통계기관의 발표만 다룬다. */
    private static final String REGION_US = "US";

    /**
     * 응답 상한. 지표 하나의 180일치는 12건 안팎이라 닿을 일이 없다 — 닿는다면 그 자체가
     * 신호이므로 아래에서 경고를 남긴다. 예전 구현은 상한에 잘려도 아무 신호가 없었다(이슈 #187).
     */
    private static final int PAGE_LIMIT = 1000;

    private final RestClient restClient;
    private final FredProperties props;

    FredReleaseCalendarSource(RestClient externalRestClient, FredProperties props) {
        this.restClient = externalRestClient.mutate().baseUrl(props.baseUrl()).build();
        this.props = Objects.requireNonNull(props, "props");
    }

    // 캐시를 붙이지 않는다. 이 호출은 하루 한 번 도는 배치에서만 나오므로 아낄 호출이 없고,
    // TTL 을 걸면 배치가 실패해 재시도할 때 캐시된 빈 결과를 그대로 다시 받는다.
    @Override
    public List<OfficialEvent> fetchScheduled(String calendarKey, LocalDate from, LocalDate to) {
        Objects.requireNonNull(calendarKey, "calendarKey");
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");

        String apiKey = Optional.ofNullable(props.apiKey())
            .filter(key -> !key.isBlank())
            .orElseThrow(() -> new IllegalStateException(
                "FRED API key is not configured (app.external.fred.api-key)"));

        ReleaseDatesResponse body = restClient.get()
            .uri(uri -> uri.path("/release/dates")
                .queryParam("release_id", calendarKey)
                .queryParam("api_key", apiKey)
                .queryParam("file_type", "json")
                // 기본값이 1776-07-04 라 생략하면 과거 전부가 온다.
                .queryParam("realtime_start", from.toString())
                .queryParam("realtime_end", to.toString())
                // 기본값 false 는 미래 발표일을 통째로 뺀다 — 이 어댑터의 존재 이유가 그 미래다.
                .queryParam("include_release_dates_with_no_data", "true")
                .queryParam("sort_order", "asc")
                .queryParam("limit", PAGE_LIMIT)
                .build())
            .retrieve()
            .body(ReleaseDatesResponse.class);

        List<ReleaseDate> dates = Optional.ofNullable(body)
            .map(ReleaseDatesResponse::releaseDates)
            .orElse(List.of());

        if (dates.size() >= PAGE_LIMIT) {
            // 잘렸을 수 있다. 지표 하나가 이만큼 발표될 리 없으므로 요청이 의도와 다르다는 뜻이다.
            log.warn("fred_release_dates_hit_limit key={} count={} limit={} from={} to={}",
                calendarKey, dates.size(), PAGE_LIMIT, from, to);
        }

        return dates.stream()
            .filter(date -> date.date() != null)
            .map(date -> new OfficialEvent(
                LocalDate.parse(date.date()), REGION_US, releaseUrl(calendarKey)))
            .toList();
    }

    /** FRED 릴리스 페이지. 실존하는 URL 이며 지어내지 않는다(FR-CM-10). */
    private static String releaseUrl(String calendarKey) {
        return "https://fred.stlouisfed.org/release?rid=" + calendarKey;
    }

    record ReleaseDatesResponse(@JsonProperty("release_dates") List<ReleaseDate> releaseDates) {
    }

    /** 단건 조회 응답은 이름을 주지 않는다 — 표시 제목은 domain 의 표가 붙인다. */
    record ReleaseDate(
        @JsonProperty("release_id") Integer releaseId,
        @JsonProperty("date") String date
    ) {
    }
}
