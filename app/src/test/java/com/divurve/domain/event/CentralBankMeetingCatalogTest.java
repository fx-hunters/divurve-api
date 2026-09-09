package com.divurve.domain.event;

import static org.assertj.core.api.Assertions.assertThat;

import com.divurve.domain.event.CentralBankMeetingCatalog.Meeting;
import java.lang.reflect.Constructor;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link CentralBankMeetingCatalog} 대조표 테스트 (이슈 #191).
 *
 * <p>이 표는 사람이 공식 페이지에서 옮겨 적은 값이다. 그래서 여기서 검증할 수 있는 것은
 * 날짜의 진위가 아니라 <b>표가 스스로 모순되지 않는가</b>다 — 중복, 어휘 이탈, 값이 없는
 * 출처처럼 옮겨 적다가 나는 실수를 잡는다.
 *
 * <p>{@link #유효_한계는_가장_이른_기관이_정한다()} 는 그중 유일하게 성격이 다르다. 표가
 * 말라붙는 것을 막는 장치가 제대로 계산되는지를 본다.
 */
@DisplayName("CentralBankMeetingCatalog")
class CentralBankMeetingCatalogTest {

    /** {@code EconEventValidator} 허용 어휘 중 이 표가 쓰는 것들. */
    private static final List<String> REGIONS = List.of("US", "EU", "JP", "KR");

    @Test
    @DisplayName("네 기관의 회의를 모두 담는다")
    void 네_기관을_담는다() {
        assertThat(CentralBankMeetingCatalog.meetings())
                .extracting(Meeting::region)
                .containsAll(REGIONS);
    }

    /**
     * 같은 날 같은 기관이 두 줄이면 유니크 제약 {@code (event_date, region, title)} 에 걸려
     * 적재가 조용히 한 건으로 줄어든다 — 표에서 미리 잡는다.
     */
    @Test
    @DisplayName("같은 기관의 같은 날짜가 두 번 나오지 않는다")
    void 중복이_없다() {
        assertThat(CentralBankMeetingCatalog.meetings())
                .extracting(m -> m.region() + "|" + m.date())
                .doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("지역은 econ_events 허용 어휘이고 통화로 옮겨진다")
    void 지역이_어휘_안이다() {
        assertThat(CentralBankMeetingCatalog.meetings()).allSatisfy(meeting -> {
            assertThat(REGIONS).contains(meeting.region());
            // 통화가 비면 보유 통화 기준 걸러내기(이슈 #166)에서 이 회의가 사라진다.
            assertThat(EconEventVocabulary.toCurrencyCode(meeting.region())).isNotNull();
        });
    }

    @Test
    @DisplayName("중요도는 CHECK 제약(1~3) 안이고 응답 어휘로 옮겨진다")
    void 중요도가_제약_안이다() {
        assertThat(CentralBankMeetingCatalog.meetings()).allSatisfy(meeting -> {
            assertThat(meeting.impact()).isBetween((short) 1, (short) 3);
            assertThat(EconEventVocabulary.toImportance(meeting.impact())).isNotNull();
        });
    }

    /** 출처를 지어내지 않는다(FR-CM-10) — 각 줄은 일정을 공표한 기관 페이지를 가리켜야 한다. */
    @Test
    @DisplayName("모든 회의가 공식 페이지 URL 을 달고 있다")
    void 출처가_있다() {
        assertThat(CentralBankMeetingCatalog.meetings()).allSatisfy(meeting -> {
            assertThat(meeting.sourceUrl()).startsWith("https://");
            assertThat(meeting.title()).isNotBlank();
        });
    }

    /** 기관마다 제목이 하나여야 같은 회의가 화면에 두 이름으로 나뉘지 않는다. */
    @Test
    @DisplayName("한 기관은 한 제목만 쓴다")
    void 기관마다_제목이_하나다() {
        Map<String, List<String>> titlesByRegion = CentralBankMeetingCatalog.meetings().stream()
                .collect(Collectors.groupingBy(
                        Meeting::region, Collectors.mapping(Meeting::title, Collectors.toList())));

        assertThat(titlesByRegion).allSatisfy(
                (region, titles) -> assertThat(titles).containsOnly(titles.get(0)));
    }

    @Test
    @DisplayName("날짜 오름차순으로 낸다 — 적재 순서가 곧 달력 순서다")
    void 날짜순이다() {
        assertThat(CentralBankMeetingCatalog.meetings())
                .extracting(Meeting::date)
                .isSortedAccordingTo(Comparator.naturalOrder());
    }

    /**
     * 가장 먼 기관의 날짜를 내면 먼저 소진된 기관의 공백이 가려진다. 한국은행은 다음 해
     * 일정을 연말에야 공표하므로 실제로 가장 먼저 마른다.
     */
    @Test
    @DisplayName("유효 한계는 가장 먼저 소진되는 기관이 정한다")
    void 유효_한계는_가장_이른_기관이_정한다() {
        LocalDate covered = CentralBankMeetingCatalog.coveredThrough();

        Map<String, LocalDate> lastByRegion = CentralBankMeetingCatalog.meetings().stream()
                .collect(Collectors.toMap(
                        Meeting::region, Meeting::date,
                        (left, right) -> right.isAfter(left) ? right : left));

        assertThat(covered).isEqualTo(
                lastByRegion.values().stream().min(Comparator.naturalOrder()).orElseThrow());
        assertThat(lastByRegion.values()).allSatisfy(
                last -> assertThat(last).isAfterOrEqualTo(covered));
    }

    @Test
    @DisplayName("인스턴스를 만들 수 없다 — 상태 없는 표다")
    void 인스턴스를_만들_수_없다() throws Exception {
        Constructor<CentralBankMeetingCatalog> constructor =
                CentralBankMeetingCatalog.class.getDeclaredConstructor();
        constructor.setAccessible(true);

        assertThat(constructor.newInstance()).isNotNull();
    }
}
