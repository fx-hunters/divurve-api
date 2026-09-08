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
 * FRED 발표 일정 캘린더 어댑터 (이슈 #163).
 *
 * <p>{@code fred/releases/dates} 는 미국 통계기관의 <b>확정 공표된 발표 일정</b>을 구조화된
 * JSON 으로 준다. LLM 을 거치지 않으므로 {@code OFFICIAL_PARSER} 경로다.
 *
 * <p><b>{@code include_release_dates_with_no_data=true} 가 핵심이다.</b> 기본값 false 는 아직
 * 데이터가 없는 <b>미래 발표일을 제외</b>한다 — 우리가 원하는 것이 정확히 그 미래 일정이므로
 * 이 값을 끄면 응답이 과거로만 채워진다.
 *
 * <p>어댑터는 이름·날짜·URL 만 옮긴다. 중요도와 표시 제목은 domain 의
 * {@code OfficialEventCatalog} 가 정한다 — 외부 어댑터가 등급을 만들지 않는다(CLAUDE.md §1).
 */
@ExternalAdapter
class FredReleaseCalendarSource implements OfficialEventCalendarSource {

    private static final Logger log = LoggerFactory.getLogger(FredReleaseCalendarSource.class);

    /** FRED 는 미국 통계기관의 발표만 다룬다. */
    private static final String REGION_US = "US";

    /** 응답 상한. FRED 문서상 최대 1000 이다. 180일치 발표 일정은 이 안에 들어온다. */
    private static final int PAGE_LIMIT = 1000;

    private final RestClient restClient;
    private final FredProperties props;

    FredReleaseCalendarSource(RestClient externalRestClient, FredProperties props) {
        this.restClient = externalRestClient.mutate().baseUrl(props.baseUrl()).build();
        this.props = Objects.requireNonNull(props, "props");
    }

    // 캐시를 붙이지 않는다. 이 호출은 하루 한 번 도는 배치에서만 나오므로 아낄 호출이 없고,
    // 6시간 TTL 을 걸면 배치가 실패해 재시도할 때 캐시된 빈 결과를 그대로 다시 받는다.
    @Override
    public List<OfficialEvent> fetchScheduled(LocalDate from, LocalDate to) {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");

        String apiKey = Optional.ofNullable(props.apiKey())
            .filter(key -> !key.isBlank())
            .orElseThrow(() -> new IllegalStateException(
                "FRED API key is not configured (app.external.fred.api-key)"));

        ReleaseDatesResponse body = restClient.get()
            .uri(uri -> uri.path("/releases/dates")
                .queryParam("api_key", apiKey)
                .queryParam("file_type", "json")
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
        if (dates.isEmpty()) {
            log.info("fred_release_dates_empty from={} to={}", from, to);
        }

        return dates.stream()
            .filter(date -> date.date() != null && date.releaseName() != null)
            .map(date -> new OfficialEvent(
                LocalDate.parse(date.date()),
                REGION_US,
                date.releaseName(),
                releaseUrl(date.releaseId())))
            .toList();
    }

    /** FRED 릴리스 페이지. 실존하는 URL 이며 지어내지 않는다(FR-CM-10). */
    private static String releaseUrl(Integer releaseId) {
        return releaseId == null ? null : "https://fred.stlouisfed.org/releases/" + releaseId;
    }

    record ReleaseDatesResponse(@JsonProperty("release_dates") List<ReleaseDate> releaseDates) {
    }

    record ReleaseDate(
        @JsonProperty("release_id") Integer releaseId,
        @JsonProperty("release_name") String releaseName,
        @JsonProperty("date") String date
    ) {
    }
}
