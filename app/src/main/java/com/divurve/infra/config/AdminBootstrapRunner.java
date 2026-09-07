package com.divurve.infra.config;

import com.divurve.common.architecture.ExternalAdapter;
import com.divurve.domain.user.AdminBootstrapService;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 기동 시 관리자 계정을 준비한다 (이슈 #111).
 *
 * <p><b>왜 마이그레이션이 아니라 여기인가</b> — 마이그레이션에 관리자 계정을 시드하려면 BCrypt
 * 해시를 SQL 파일에 박아 레포에 커밋해야 한다. 레포에 있는 관리자 비밀번호는 그 자체로 사고다.
 * 여기서는 값을 환경변수({@code ADMIN_EMAIL}/{@code ADMIN_PASSWORD})로만 받는다.
 *
 * <p>{@code app.admin.bootstrap-email} 이 비어 있으면 이 빈 자체가 만들어지지 않는다 — 로컬에서
 * 아무 설정 없이 띄우면 관리자 계정이 생기지 않고, 그것이 기본값이다.
 *
 * <p>멱등하다. 이미 있는 계정이면 role 만 올리고 비밀번호는 건드리지 않는다 — 기동할 때마다
 * 비밀번호를 덮어쓰면, 운영자가 바꾼 비밀번호가 재배포 때 조용히 되돌아간다.
 *
 * <p>이 클래스는 트리거일 뿐이고 판단·저장은 {@link AdminBootstrapService} 가 한다
 * ({@code EconEventIngestionScheduler} 와 같은 구조). 실패해도 기동을 막지 않는다 — 관리자 계정이
 * 없는 것은 서비스 사용자에게 영향이 없고, 그 때문에 앱 전체가 안 뜨는 편이 더 나쁘다.
 */
@ExternalAdapter
@Component
@ConditionalOnProperty(prefix = "app.admin", name = "bootstrap-email")
public class AdminBootstrapRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrapRunner.class);

    private final AdminBootstrapService adminBootstrapService;

    public AdminBootstrapRunner(AdminBootstrapService adminBootstrapService) {
        this.adminBootstrapService = Objects.requireNonNull(adminBootstrapService, "adminBootstrapService");
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            AdminBootstrapService.BootstrapOutcome outcome = adminBootstrapService.bootstrap();
            log.info("관리자 부트스트랩 완료: {}", outcome);
        } catch (RuntimeException e) {
            log.error("관리자 부트스트랩 실패 — 관리자 계정 없이 기동한다", e);
        }
    }
}
