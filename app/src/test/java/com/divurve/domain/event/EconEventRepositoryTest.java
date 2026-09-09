package com.divurve.domain.event;

import static org.assertj.core.api.Assertions.assertThat;

import com.divurve.domain.RepositoryTestBase;
import com.divurve.domain.event.entity.EconEvent;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * {@code econ_events} 구간 조회·집계 (이슈 #162, 이슈 #191 로 시드 제거).
 *
 * <p><b>예전에는 V28 시연용 시드를 읽었다.</b> #191 이 그 시드를 지웠으므로 이제 각 테스트가
 * 필요한 행을 직접 넣는다 — 마이그레이션이 심어 준 데이터에 기대면, 그 데이터가 사라지는 날
 * 테스트가 무엇을 검증하려 했는지도 함께 사라진다.
 *
 * <p>실제 Postgres 로만 검증되는 것들이다. 구간 조회의 정렬·경계와 {@code group by} 집계는
 * 인메모리 대체 DB 로는 같은 것을 보증하지 못한다.
 */
@DisplayName("econ_events 조회")
class EconEventRepositoryTest extends RepositoryTestBase {

    private static final Instant FETCHED_AT = Instant.parse("2026-09-09T04:10:00Z");

    @Autowired
    private EconEventRepository repository;

    private EconEvent official(LocalDate date, String region, String title) {
        return repository.save(EconEvent.official(
                date, region, title, (short) 3,
                "https://www.federalreserve.gov/monetarypolicy/fomccalendars.htm", FETCHED_AT));
    }

    /** #191 이 지운 시연용 행이 되살아나면 신뢰도 혼합 금지(이슈 #74 제약 4)가 다시 깨진다. */
    @Test
    @DisplayName("마이그레이션을 모두 적용하면 시연용 행이 남아 있지 않다")
    void 시연용_행이_없다() {
        assertThat(repository.findAll())
                .noneSatisfy(event ->
                        assertThat(event.getSourceKind()).isEqualTo("DEMO_SAMPLE"));
    }

    @Test
    @DisplayName("구간 조회는 창 안의 일정만 날짜 오름차순으로 돌려준다")
    void 구간_조회는_날짜순이다() {
        LocalDate from = LocalDate.of(2026, 9, 1);
        LocalDate to = LocalDate.of(2026, 12, 31);
        official(LocalDate.of(2026, 12, 9), "US", "미국 연방공개시장위원회(FOMC) 결과 발표");
        official(LocalDate.of(2026, 9, 16), "US", "미국 소비자물가지수(CPI) 발표");
        official(LocalDate.of(2026, 10, 29), "EU", "유럽중앙은행(ECB) 통화정책회의");

        List<EconEvent> found = repository.findByEventDateBetweenOrderByEventDateAsc(from, to);

        assertThat(found).hasSize(3);
        assertThat(found).isSortedAccordingTo(Comparator.comparing(EconEvent::getEventDate));
        assertThat(found).allSatisfy(event ->
                assertThat(event.getEventDate()).isBetween(from, to));
    }

    @Test
    @DisplayName("창 밖은 읽지 않는다 — 서비스가 다시 거를 필요가 없다")
    void 창_밖은_읽지_않는다() {
        official(LocalDate.of(2026, 9, 16), "US", "미국 소비자물가지수(CPI) 발표");
        LocalDate past = LocalDate.of(2000, 1, 1);

        assertThat(repository.findByEventDateBetweenOrderByEventDateAsc(past, past.plusDays(30)))
                .isEmpty();
    }

    @Test
    @DisplayName("같은 날짜·지역·제목은 한 건으로 찾는다 — 적재가 중복을 만들지 않는 근거다")
    void 키로_한_건을_찾는다() {
        LocalDate date = LocalDate.of(2026, 9, 16);
        official(date, "US", "미국 소비자물가지수(CPI) 발표");

        assertThat(repository.findByEventDateAndRegionAndTitle(
                date, "US", "미국 소비자물가지수(CPI) 발표")).isPresent();
        assertThat(repository.findByEventDateAndRegionAndTitle(
                date, "EU", "미국 소비자물가지수(CPI) 발표")).isEmpty();
    }

    /** 이슈 #176 — 관리자 화면의 "마지막 갱신" 을 채우는 집계. */
    @Test
    @DisplayName("출처별 집계는 건수·마지막 적재 시각·가장 먼 일정을 낸다")
    void 출처별_집계가_나온다() {
        official(LocalDate.of(2026, 9, 16), "US", "미국 소비자물가지수(CPI) 발표");
        official(LocalDate.of(2026, 12, 9), "US", "미국 연방공개시장위원회(FOMC) 결과 발표");

        List<EconEventRepository.SourceKindStat> stats = repository.statsBySourceKind();

        EconEventRepository.SourceKindStat official = stats.stream()
                .filter(stat -> "OFFICIAL_PARSER".equals(stat.getSourceKind()))
                .findFirst()
                .orElseThrow();
        assertThat(official.getTotal()).isEqualTo(2);
        assertThat(official.getLastFetchedAt()).isEqualTo(FETCHED_AT);
        assertThat(official.getLastEventDate()).isEqualTo(LocalDate.of(2026, 12, 9));
    }
}
