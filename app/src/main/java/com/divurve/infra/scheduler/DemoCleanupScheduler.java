package com.divurve.infra.scheduler;

import com.divurve.common.architecture.ExternalAdapter;
import com.divurve.domain.auth.DemoCleanupService;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * 오래된 데모 계정을 주기적으로 정리하는 배치 스케줄러 (이슈 #138).
 *
 * <p>레이어 어노테이션 근거는 {@link EconEventIngestionScheduler} 와 같다 — 아무도 호출하지 않는
 * 진입점이고 {@link DemoCleanupService}(UseCase)만 호출하므로 "External → UseCase" 방향이며
 * ArchUnit 의 incoming 제약을 위반하지 않는다.
 *
 * <p><b>기본은 꺼짐</b>({@code app.demo.cleanup.enabled}). 이 레포는 "스케줄링 인프라는 항상 켜고
 * 작업별로 각자 끈다" 를 규약으로 삼는다({@code SchedulingConfig} javadoc) — 서로 다른 일을 하는
 * 배치가 한 스위치에 묶이면 하나를 켜려고 다른 하나까지 켜야 한다. <b>데이터를 지우는 배치</b>라
 * 특히 명시적으로 켜야 한다.
 *
 * <p><b>예외를 삼킨다.</b> 스케줄러 스레드로 예외가 전파되면 다음 트리거부터 스케줄이 통째로 멈출
 * 수 있어, 정리 한 번의 실패가 이후 모든 실행을 막는다 — 그러면 원래 문제(더미 데이터 누적)가
 * 조용히 돌아온다.
 */
@ExternalAdapter
@ConditionalOnProperty(prefix = "app.demo.cleanup", name = "enabled", havingValue = "true")
public class DemoCleanupScheduler {

    private static final Logger log = LoggerFactory.getLogger(DemoCleanupScheduler.class);

    private final DemoCleanupService demoCleanupService;

    public DemoCleanupScheduler(DemoCleanupService demoCleanupService) {
        this.demoCleanupService = Objects.requireNonNull(demoCleanupService, "demoCleanupService");
    }

    /** 배치 진입점. 주기는 {@code app.demo.cleanup.cron} 으로 뺀다. */
    @Scheduled(cron = "${app.demo.cleanup.cron}")
    public void cleanUp() {
        try {
            int deleted = demoCleanupService.cleanUp();
            log.info("demo_cleanup_scheduled_run_completed deleted={}", deleted);
        } catch (RuntimeException e) {
            log.error("demo_cleanup_scheduled_run_failed message={}", e.getMessage(), e);
        }
    }
}
