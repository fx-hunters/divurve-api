package com.divurve.domain.event;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * 중앙은행 통화정책회의 일정 표 (이슈 #191).
 *
 * <p><b>왜 이 표가 따로 있는가</b> — 환율을 가장 크게 움직이는 일정은 정책금리를 정하는
 * 회의인데, FRED 에는 이 일정이 없다. FRED 는 통계 릴리스 캘린더이고 회의체 일정은 다루지
 * 않는다(2026-09-09 기준 릴리스 331개 중 해당 없음). 그래서 {@link OfficialEventCatalog} 가
 * 채우지 못하는 구멍을 이 표가 메운다.
 *
 * <p><b>왜 파서가 아니라 표인가</b> — 네 기관 모두 일정을 연 단위로 미리 공표하지만 ICS·API 를
 * 주지 않는다. 남는 방법은 HTML 파싱인데, 연 32건 남짓을 위해 파서 넷을 두면 페이지 구조가
 * 바뀌는 순간 <b>조용히 빈 결과</b>가 된다 — 에러가 아니라 매칭 실패라 아무도 눈치채지
 * 못한다(이슈 #187 에서 이미 겪은 실패다). 사람이 확정한 고정 표는 그 실패를 만들지 않고,
 * CLAUDE.md §1 이 요구하는 방식이기도 하다.
 *
 * <p><b>표의 약점은 소진이다.</b> 그래서 {@link #coveredThrough()} 가 표의 유효 한계를 값으로
 * 내고, {@link OfficialEventIngestionService} 가 그것이 조회 구간에 못 미치면 경고를 남긴다.
 * 표가 조용히 말라붙는 것을 막는 장치다.
 *
 * <p><b>날짜는 결과 발표일이다.</b> FOMC·ECB·BOJ 는 이틀에 걸쳐 열리는데 환율이 움직이는 날은
 * 결과를 공표하는 둘째 날이므로 그 날짜만 담는다. 첫날은 화면에 두 줄을 만들 뿐 정보가 없다.
 *
 * <p>모든 날짜는 <b>2026-09-09 에 각 기관 공식 페이지에서 그대로 옮겼다</b>. 지어내지
 * 않는다(FR-CM-10) — {@code sourceUrl} 은 그 페이지를 가리킨다.
 */
public final class CentralBankMeetingCatalog {

    /** 미국 연방준비제도. 정책금리 결정이 달러 전반을 움직인다. */
    private static final Bank FOMC = new Bank(
            "US", "미국 연방공개시장위원회(FOMC) 결과 발표", (short) 3,
            "https://www.federalreserve.gov/monetarypolicy/fomccalendars.htm");

    /** 유럽중앙은행. */
    private static final Bank ECB = new Bank(
            "EU", "유럽중앙은행(ECB) 통화정책회의", (short) 3,
            "https://www.ecb.europa.eu/press/calendars/mgcgc/html/index.en.html");

    /**
     * 일본은행. 중요도를 2 로 둔 것은 원화-달러 축이 주 관심사인 이 서비스에서 엔화가
     * 부차적이기 때문이며, 등급은 사람이 정한다(CLAUDE.md §1).
     */
    private static final Bank BOJ = new Bank(
            "JP", "일본은행 금융정책결정회의", (short) 2,
            "https://www.boj.or.jp/en/mopo/mpmsche_minu/index.htm");

    /** 한국은행 금융통화위원회 통화정책방향 결정회의. */
    private static final Bank BOK = new Bank(
            "KR", "한국은행 금융통화위원회", (short) 2,
            "https://www.bok.or.kr/portal/singl/crncyPolicyDrcMtg/listYear.do?mtgSe=A&menuNo=200755");

    /** 결과 발표일 (FOMC 는 이틀 회의의 둘째 날). 2027년까지 공표돼 있다. */
    private static final List<LocalDate> FOMC_DATES = dates(
            "2026-01-28", "2026-03-18", "2026-04-29", "2026-06-17",
            "2026-07-29", "2026-09-16", "2026-10-28", "2026-12-09",
            "2027-01-27", "2027-03-17", "2027-04-28", "2027-06-09",
            "2027-07-28", "2027-09-15", "2027-10-27", "2027-12-08");

    /**
     * 통화정책회의 둘째 날(기자회견이 열리는 날). ECB 는 비통화정책 회의도 같은 페이지에
     * 싣는데 그쪽은 금리를 다루지 않으므로 담지 않는다. 2028년까지 공표돼 있다.
     */
    private static final List<LocalDate> ECB_DATES = dates(
            "2026-09-10", "2026-10-29", "2026-12-17",
            "2027-02-04", "2027-03-18", "2027-04-29", "2027-06-10",
            "2027-07-22", "2027-09-09", "2027-10-28", "2027-12-16",
            "2028-02-03", "2028-03-23", "2028-05-04", "2028-06-08",
            "2028-07-20", "2028-09-07", "2028-10-12", "2028-12-07");

    /** 금융정책결정회의 둘째 날(결과 공표일). 2027년까지 공표돼 있다. */
    private static final List<LocalDate> BOJ_DATES = dates(
            "2026-01-23", "2026-03-19", "2026-04-28", "2026-06-16",
            "2026-07-31", "2026-09-18", "2026-10-30", "2026-12-18",
            "2027-01-22", "2027-03-18", "2027-04-28", "2027-06-11",
            "2027-07-22", "2027-09-22", "2027-10-29", "2027-12-17");

    /**
     * 통화정책방향 결정회의. 한국은행은 다음 해 일정을 연말에 공표하므로 2026-11-26 이
     * 현재 마지막이다 — 표 전체의 유효 한계를 여기가 정한다({@link #coveredThrough()}).
     */
    private static final List<LocalDate> BOK_DATES = dates(
            "2026-01-15", "2026-02-26", "2026-04-10", "2026-05-28",
            "2026-07-16", "2026-08-27", "2026-10-22", "2026-11-26");

    private static final List<Meeting> MEETINGS = Stream.of(
                    meetingsOf(FOMC, FOMC_DATES),
                    meetingsOf(ECB, ECB_DATES),
                    meetingsOf(BOJ, BOJ_DATES),
                    meetingsOf(BOK, BOK_DATES))
            .flatMap(List::stream)
            .sorted(Comparator.comparing(Meeting::date).thenComparing(Meeting::region))
            .toList();

    /**
     * 표가 모든 기관에 대해 유효한 마지막 날. 기관별 마지막 날의 <b>최솟값</b>이다 — 한 기관이
     * 먼저 소진되면 그 시점부터 표는 이미 불완전하고, 가장 먼 기관의 날짜를 내면 그 사실이
     * 가려진다.
     */
    private static final LocalDate COVERED_THROUGH = Stream.of(
                    FOMC_DATES, ECB_DATES, BOJ_DATES, BOK_DATES)
            .map(list -> list.get(list.size() - 1))
            .min(Comparator.naturalOrder())
            .orElseThrow();

    private CentralBankMeetingCatalog() {
    }

    /**
     * 회의 전체. 날짜 오름차순이며 구간 자르기는 호출자가 한다.
     *
     * @return 회의 목록 (불변)
     */
    public static List<Meeting> meetings() {
        return MEETINGS;
    }

    /**
     * 표를 믿을 수 있는 마지막 날.
     *
     * @return 기관별 공표 한계 중 가장 이른 날
     */
    public static LocalDate coveredThrough() {
        return COVERED_THROUGH;
    }

    private static List<Meeting> meetingsOf(Bank bank, List<LocalDate> dates) {
        return dates.stream()
                .map(date -> new Meeting(
                        date, bank.region(), bank.title(), bank.impact(), bank.sourceUrl()))
                .toList();
    }

    private static List<LocalDate> dates(String... isoDates) {
        return Stream.of(isoDates).map(LocalDate::parse).toList();
    }

    /**
     * 회의 한 건.
     *
     * @param date      결과 발표일
     * @param region    지역 ({@link EconEventValidator} 허용 어휘)
     * @param title     화면·API 에 나갈 제목
     * @param impact    1(낮음)~3(높음)
     * @param sourceUrl 일정을 공표한 공식 페이지
     */
    public record Meeting(
            LocalDate date, String region, String title, short impact, String sourceUrl) {
    }

    /** 기관 하나의 고정 정보. 회의마다 반복되는 값을 한 곳에 모은다. */
    private record Bank(String region, String title, short impact, String sourceUrl) {
    }
}
