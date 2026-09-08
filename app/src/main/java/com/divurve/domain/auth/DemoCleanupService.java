package com.divurve.domain.auth;

import com.divurve.common.architecture.UseCase;
import com.divurve.domain.user.UserRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.transaction.annotation.Transactional;

/**
 * 오래된 데모 계정과 그 소유 데이터를 정리하는 유스케이스 (이슈 #138).
 *
 * <p><b>왜 필요한가</b> — {@link AuthDemoService#createDemoSession} 은 호출마다 <b>무조건</b> 새
 * 유저를 만들고 전체 샘플을 시드한다. 상한도 만료도 없어서 시연·프론트 개발이 진행될수록
 * {@code users} 와 소유 테이블 전체에 더미 행이 무한히 쌓이고, 관리자 사용자 목록도 데모 행에
 * 파묻힌다.
 *
 * <p><b>소유 데이터를 직접 지우지 않는다.</b> {@code delete from users} 한 번으로 끝난다 —
 * V25(이슈 #137)가 소유자 FK 에 {@code on delete cascade} 를 걸었기 때문이다. 여기에 삭제 순서
 * 목록을 하드코딩하지 않는 이유가 그 이슈의 요지였다: 이 레포는 여러 세션이 병렬로 새 소유자
 * 테이블을 붙이고(V24 {@code notifications} 가 그 직전 예다), 목록은 그때 갱신되지 않아 정리가
 * FK 위반으로 조용히 실패한다. 그 실패는 "더미 데이터가 계속 쌓인다" 라는 증상으로만 보인다.
 *
 * <p><b>기준을 {@code last_login_at} 단독으로 두지 않는다.</b> 데모는 발급 시점에 한 번 찍히고
 * ({@code AuthDemoService}) 갱신 때 다시 찍히므로 재방문이 없으면 사실상 {@code created_at} 이다.
 * 게다가 그 컬럼은 nullable 이라 {@code last_login_at < ...} 조건은 NULL 행을 영구히 남긴다.
 * 그래서 {@code coalesce(last_login_at, created_at)} 로 판정한다.
 *
 * <p><b>{@code is_demo} 만 본다.</b> {@code sample_data_seeded} 가 아니다 — 실연동 도착 전까지
 * 일반 가입 계정도 같은 샘플을 받으므로(이슈 #108, {@code User#sampleDataSeeded} javadoc) 그것을
 * 기준으로 삼으면 실제 회원을 지운다.
 *
 * <p><b>살아 있는 세션을 지우지 않는 근거</b> — 보존 기간(기본 1일)이 액세스 토큰 TTL(30분)보다
 * 훨씬 길고, 토큰 갱신도 {@code last_login_at} 을 갱신하므로(그것이
 * {@code AuthService#refreshAccessToken} 이 접속을 세는 이유다) 앱을 계속 쓰는 데모 세션은 판정
 * 대상에 들어오지 않는다. 반대로 판정 대상이 된 계정의 액세스 토큰은 이미 한참 전에 만료됐다.
 * 리프레시 토큰(14일)은 삭제 뒤에도 남는데, 그 경로는 {@code AuthService} 가 401 로 막는다.
 */
@UseCase
public class DemoCleanupService {

    /** 기본 보존 기간. 액세스 토큰 TTL(30분)보다 충분히 길어야 시연 중 계정이 사라지지 않는다. */
    public static final String DEFAULT_RETENTION = "P1D";

    /**
     * 1회 실행 상한의 기본값. 상한을 두는 이유는 사고 방어다 — 기준이 잘못 설정돼 대상이 폭증하면
     * 한 트랜잭션이 수만 행을 cascade 삭제하며 DB 를 붙잡는다. 남은 것은 다음 실행이 가져간다.
     */
    public static final int DEFAULT_BATCH_LIMIT = 500;

    private static final Logger log = LoggerFactory.getLogger(DemoCleanupService.class);

    private final UserRepository userRepository;
    private final Clock clock;
    private final Duration retention;
    private final int batchLimit;

    public DemoCleanupService(
            UserRepository userRepository,
            Clock clock,
            @Value("${app.demo.cleanup.retention:" + DEFAULT_RETENTION + "}") Duration retention,
            @Value("${app.demo.cleanup.batch-limit:" + DEFAULT_BATCH_LIMIT + "}") int batchLimit) {
        this.userRepository = Objects.requireNonNull(userRepository, "userRepository");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.retention = Objects.requireNonNull(retention, "retention");
        if (batchLimit < 1) {
            throw new IllegalArgumentException("batchLimit 은 1 이상이어야 한다: " + batchLimit);
        }
        this.batchLimit = batchLimit;
    }

    /**
     * 마지막 접속이 보존 기간을 넘긴 데모 계정을 지운다.
     *
     * <p>대상 조회와 삭제를 두 단계로 나눈 이유 — JPQL 은 서브쿼리에 {@code limit} 을 쓸 수 없고,
     * 상한 없는 벌크 삭제는 위 {@link #DEFAULT_BATCH_LIMIT} 의 사고 방어를 포기하는 것이다.
     * 조회한 id 를 그대로 삭제하므로 두 단계 사이에 대상이 바뀌어도 지우는 범위는 조회 시점에
     * 고정된다.
     *
     * @return 지운 계정 수
     */
    @Transactional
    public int cleanUp() {
        Instant threshold = Instant.now(clock).minus(retention);
        List<UUID> expired = userRepository.findExpiredDemoUserIds(
                threshold,
                PageRequest.of(0, batchLimit, Sort.by(Sort.Direction.ASC, "createdAt")));

        if (expired.isEmpty()) {
            log.info("demo_cleanup_completed threshold={} deleted=0", threshold);
            return 0;
        }

        userRepository.deleteAllByIdInBatch(expired);
        // 몇 건을 지웠는지 남기지 않으면, 이 배치가 도는지 아니면 조용히 아무것도 못 하는지
        // 구분할 방법이 없다 — 정확히 그 구분이 이 이슈의 출발점이었다.
        log.info("demo_cleanup_completed threshold={} deleted={} batch_limit={}",
                threshold, expired.size(), batchLimit);
        return expired.size();
    }
}
