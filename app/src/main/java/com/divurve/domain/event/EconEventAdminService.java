package com.divurve.domain.event;

import com.divurve.common.architecture.UseCase;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;

/**
 * 경제 일정 적재를 관리자가 직접 돌리고 현황을 확인하는 유스케이스 (이슈 #176).
 *
 * <p><b>왜 필요한가</b> — #163 의 공식 캘린더 적재는 진입점이 스케줄러뿐이라, 배포 직후 연동이
 * 살아 있는지 보려면 다음 크론(기본 매일 04:10)까지 기다려야 했다. 크론을 임시로 줄여 로그를
 * 보는 우회가 유일했는데, 검증 목적으로 배포 설정을 흔드는 것은 좋지 않다.
 *
 * <p><b>실패를 값으로 낸다.</b> 외부 호출이 터져도 500 이 아니라 {@code failureReason} 을 담아
 * 200 으로 답한다 — {@code POST /admin/ai/extract-preview}(이슈 #122)가 세운 규약과 같다.
 * 이 화면의 목적이 "왜 안 되는가" 를 보는 것인데 스택트레이스만 남기면 목적을 잃는다.
 */
@UseCase
public class EconEventAdminService {

    private static final Logger log = LoggerFactory.getLogger(EconEventAdminService.class);

    private final OfficialEventIngestionService ingestionService;
    private final EconEventRepository repository;
    private final Clock clock;

    public EconEventAdminService(
            OfficialEventIngestionService ingestionService,
            EconEventRepository repository,
            Clock clock) {
        this.ingestionService = Objects.requireNonNull(ingestionService, "ingestionService");
        this.repository = Objects.requireNonNull(repository, "repository");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * 공식 캘린더 적재를 지금 돌린다.
     *
     * <p>스케줄러가 부르는 것과 <b>같은</b> 유스케이스다 — 관리자용 경로가 따로 계산하지 않는다.
     *
     * @return 적재 결과. 외부 호출이 실패하면 집계 없이 {@code failureReason} 만 담긴다
     */
    public RefreshResult refresh() {
        Instant startedAt = clock.instant();
        try {
            OfficialEventIngestionService.IngestionReport report = ingestionService.ingest();
            return new RefreshResult(report, null, startedAt, elapsedMsSince(startedAt));
        } catch (RuntimeException e) {
            // 외부 연동 실패는 이 화면이 보려는 것 자체다 — 값으로 낸다.
            log.warn("admin_econ_event_refresh_failed message={}", e.getMessage(), e);
            return new RefreshResult(null, failureReason(e), startedAt, elapsedMsSince(startedAt));
        }
    }

    /**
     * 출처별 적재 현황.
     *
     * @return 출처별 건수·마지막 적재 시각·가장 먼 일정 날짜
     */
    @Transactional(readOnly = true)
    public StatusResult status() {
        return new StatusResult(
                repository.statsBySourceKind().stream()
                        .map(stat -> new SourceStatus(
                                stat.getSourceKind(),
                                stat.getTotal(),
                                stat.getLastFetchedAt(),
                                stat.getLastEventDate()))
                        .toList(),
                clock.instant());
    }

    /**
     * 외부 시스템 메시지를 그대로 노출하지 않는다 — ECOS·FRED URL 경로에 API 키가 들어 있다
     * (이슈 #74 가 같은 이유로 원문 메시지를 로그에만 남긴다).
     */
    private static String failureReason(RuntimeException e) {
        return e.getClass().getSimpleName();
    }

    private long elapsedMsSince(Instant startedAt) {
        return clock.instant().toEpochMilli() - startedAt.toEpochMilli();
    }

    /**
     * 수동 적재 결과.
     *
     * @param report        적재 집계. 실패면 {@code null}
     * @param failureReason 실패 사유 요약. 성공이면 {@code null}
     * @param refreshedAt   호출 시각
     * @param elapsedMs     소요 시간
     */
    public record RefreshResult(
            OfficialEventIngestionService.IngestionReport report, String failureReason,
            Instant refreshedAt, long elapsedMs) {
    }

    /**
     * 적재 현황.
     *
     * @param sources    출처별 현황
     * @param checkedAt  조회 시각
     */
    public record StatusResult(List<SourceStatus> sources, Instant checkedAt) {
    }

    /**
     * 출처 하나의 현황.
     *
     * @param sourceKind    {@code OFFICIAL_PARSER} · {@code AI_EXTRACTED} · {@code DEMO_SAMPLE}
     * @param total         건수
     * @param lastFetchedAt 마지막 적재 시각
     * @param lastEventDate 가장 먼 일정 날짜 — 얼마나 앞까지 채워져 있는가
     */
    public record SourceStatus(
            String sourceKind, long total, Instant lastFetchedAt, LocalDate lastEventDate) {
    }
}
