package com.divurve.domain.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.divurve.domain.RepositoryTestBase;
import com.divurve.domain.user.UserRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;

/**
 * 데모 정리가 실제 Postgres 에서 무엇을 지우고 무엇을 남기는지 검증한다 (이슈 #138).
 *
 * <p>목으로는 검증할 수 없는 것들이다 —
 * <ul>
 *   <li><b>cascade 전파</b>. {@code delete from users} 한 번으로 소유 데이터가 함께 사라지는 것은
 *       V25(이슈 #137)의 FK 삭제 동작이고, 그것이 없으면 이 배치는 FK 위반으로 실패한다.</li>
 *   <li><b>일반 계정이 살아남는 것</b>. 잘못된 조건 하나가 실제 회원을 지우는 사고가 된다.</li>
 *   <li><b>{@code coalesce} 판정</b>. {@code last_login_at} 이 NULL 인 행이 대상에 들어오는지는
 *       SQL 이 실행돼야 드러난다.</li>
 * </ul>
 */
@DisplayName("데모 계정 정리 (통합)")
class DemoCleanupIntegrationTest extends RepositoryTestBase {

    private static final Instant NOW = Instant.parse("2026-09-08T04:10:00Z");
    private static final Duration RETENTION = Duration.ofDays(1);

    @Autowired
    private UserRepository userRepository;

    @PersistenceContext
    private EntityManager entityManager;

    private DemoCleanupService sut(int batchLimit) {
        return new DemoCleanupService(
                userRepository, Clock.fixed(NOW, ZoneOffset.UTC), RETENTION, batchLimit);
    }

    @Test
    @DisplayName("오래된 데모 계정과 그 소유 데이터가 함께 사라진다")
    void deletesExpiredDemoUserWithOwnedData() {
        UUID expiredId = insertUser("old-demo@example.com", true, NOW.minus(Duration.ofDays(3)));
        insertHolding(expiredId);
        insertGoalWithPlan(expiredId);

        assertThat(sut(500).cleanUp()).isEqualTo(1);
        entityManager.flush();
        entityManager.clear();

        assertThat(userRepository.findById(expiredId)).isEmpty();
        assertThat(count("select count(*) from holdings where owner_id = :id", expiredId)).isZero();
        assertThat(count("select count(*) from goals where owner_id = :id", expiredId)).isZero();
        assertThat(count("""
                select count(*) from plans p
                  join goals g on g.id = p.goal_id
                 where g.owner_id = :id
                """, expiredId))
                .as("V25 cascade 가 goals → plans 까지 전파돼야 한다")
                .isZero();
    }

    @Test
    @DisplayName("일반 가입 계정은 아무리 오래돼도 지우지 않는다 — is_demo 만 본다")
    void neverDeletesRegularAccounts() {
        UUID regularId = insertUser("member@example.com", false, NOW.minus(Duration.ofDays(400)));

        assertThat(sut(500).cleanUp()).isZero();

        assertThat(userRepository.findById(regularId)).isPresent();
    }

    @Test
    @DisplayName("보존 기간 안의 데모 계정은 남는다 — 시연 중 계정이 사라지지 않는다")
    void keepsRecentDemoAccounts() {
        UUID recentId = insertUser("live-demo@example.com", true, NOW.minus(Duration.ofHours(2)));

        assertThat(sut(500).cleanUp()).isZero();

        assertThat(userRepository.findById(recentId)).isPresent();
    }

    @Test
    @DisplayName("경계에서 정확히 판정한다 — 보존 기간을 막 넘긴 계정만 대상이다")
    void boundaryIsExclusive() {
        UUID justInside = insertUser("inside@example.com", true, NOW.minus(RETENTION).plusSeconds(1));
        UUID justOutside = insertUser("outside@example.com", true, NOW.minus(RETENTION).minusSeconds(1));

        assertThat(sut(500).cleanUp()).isEqualTo(1);

        assertThat(userRepository.findById(justInside)).isPresent();
        assertThat(userRepository.findById(justOutside)).isEmpty();
    }

    @Test
    @DisplayName("last_login_at 이 NULL 이면 created_at 으로 판정한다 — 그 행이 영구히 남지 않는다")
    void nullLastLoginFallsBackToCreatedAt() {
        UUID neverLoggedIn = insertUserWithoutLogin("no-login@example.com", NOW.minus(Duration.ofDays(3)));

        assertThat(sut(500).cleanUp()).isEqualTo(1);

        assertThat(userRepository.findById(neverLoggedIn)).isEmpty();
    }

    @Test
    @DisplayName("배치 상한을 넘는 대상은 남기고 다음 실행이 가져간다 — 오래된 것부터 지운다")
    void batchLimitLeavesRemainderForNextRun() {
        insertUser("a@example.com", true, NOW.minus(Duration.ofDays(5)));
        insertUser("b@example.com", true, NOW.minus(Duration.ofDays(4)));
        insertUser("c@example.com", true, NOW.minus(Duration.ofDays(3)));

        assertThat(sut(2).cleanUp()).isEqualTo(2);
        assertThat(sut(2).cleanUp()).isEqualTo(1);
        assertThat(sut(2).cleanUp()).isZero();
    }

    @Test
    @DisplayName("조회는 만료된 데모 계정만 돌려준다")
    void queryReturnsOnlyExpiredDemoUsers() {
        UUID expired = insertUser("expired@example.com", true, NOW.minus(Duration.ofDays(2)));
        insertUser("fresh@example.com", true, NOW);
        insertUser("regular@example.com", false, NOW.minus(Duration.ofDays(9)));

        List<UUID> found = userRepository.findExpiredDemoUserIds(
                NOW.minus(RETENTION), PageRequest.of(0, 100));

        assertThat(found).containsExactly(expired);
    }

    @Test
    @DisplayName("정리가 AI 비용 이력을 지우지 않는다 — user_id 만 비고 토큰·is_demo 는 남는다")
    void cleanupKeepsAiCostHistory() {
        UUID expiredId = insertUser("cost@example.com", true, NOW.minus(Duration.ofDays(3)));
        insertAiCallLog(expiredId);

        assertThat(sut(500).cleanUp()).isEqualTo(1);
        entityManager.flush();
        entityManager.clear();

        // 데모 트래픽이 AI 비용의 대부분일 가능성이 큰데, 정리가 매일 돌면서 정확히 그 부분을
        // 지우면 "이번 주 비용이 왜 늘었는가" 에 답할 근거가 매일 증발한다. ai_call_logs.user_id 가
        // V25 소유자 FK 규칙의 예외(on delete set null)인 이유가 이것이다(이슈 #143).
        assertThat(count("select count(*) from ai_call_logs where is_demo = true"))
                .as("행 자체는 남아야 한다")
                .isEqualTo(1);
        assertThat(count("select count(*) from ai_call_logs where user_id = :id", expiredId))
                .as("사라진 사용자를 가리키는 참조만 비어야 한다")
                .isZero();
        assertThat(count("""
                select coalesce(sum(input_tokens), 0) from ai_call_logs where is_demo = true
                """))
                .as("토큰 수가 남지 않으면 비용 집계가 성립하지 않는다")
                .isEqualTo(120);
    }

    private void insertAiCallLog(UUID userId) {
        entityManager.createNativeQuery("""
                        insert into ai_call_logs (id, requested_at, user_id, is_demo, purpose,
                                                  surface, model, input_tokens, output_tokens, outcome)
                        values (gen_random_uuid(), :requestedAt, :userId, true, 'narrate',
                                'forecast_summary', 'claude-opus-5', 120, 45, 'success')
                        """)
                .setParameter("requestedAt", NOW.minus(Duration.ofDays(3)))
                .setParameter("userId", userId)
                .executeUpdate();
    }

    /** 파라미터 없는 집계 — 소유자가 사라진 뒤 남은 행을 세는 데 쓴다. */
    private long count(String sql) {
        return ((Number) entityManager.createNativeQuery(sql).getSingleResult()).longValue();
    }

    private long count(String sql, UUID id) {
        return ((Number) entityManager.createNativeQuery(sql)
                .setParameter("id", id)
                .getSingleResult()).longValue();
    }

    private UUID insertUser(String email, boolean demo, Instant lastLoginAt) {
        UUID id = UUID.randomUUID();
        entityManager.createNativeQuery("""
                        insert into users (id, email, name, is_demo, last_login_at, created_at)
                        values (:id, :email, '정리 검증', :demo, :lastLoginAt, :lastLoginAt)
                        """)
                .setParameter("id", id)
                .setParameter("email", email)
                .setParameter("demo", demo)
                .setParameter("lastLoginAt", lastLoginAt)
                .executeUpdate();
        return id;
    }

    /** {@code last_login_at} 을 비운 계정 — coalesce 판정이 실제로 필요한 경우다. */
    private UUID insertUserWithoutLogin(String email, Instant createdAt) {
        UUID id = UUID.randomUUID();
        entityManager.createNativeQuery("""
                        insert into users (id, email, name, is_demo, created_at)
                        values (:id, :email, '정리 검증', true, :createdAt)
                        """)
                .setParameter("id", id)
                .setParameter("email", email)
                .setParameter("createdAt", createdAt)
                .executeUpdate();
        return id;
    }

    private void insertHolding(UUID ownerId) {
        entityManager.createNativeQuery("""
                        insert into holdings (id, owner_id, ticker, currency_code, quantity, avg_price)
                        values (gen_random_uuid(), :ownerId, 'VOO', 'USD', 10, 400)
                        """)
                .setParameter("ownerId", ownerId)
                .executeUpdate();
    }

    private void insertGoalWithPlan(UUID ownerId) {
        UUID goalId = UUID.randomUUID();
        entityManager.createNativeQuery("""
                        insert into goals (id, owner_id, name, kind, purpose, currency_code,
                                           target_amount, budget_amount, is_speculative, status)
                        values (:id, :ownerId, '학비', 'deadline', 'tuition', 'USD',
                                10000, 1000000, false, 'active')
                        """)
                .setParameter("id", goalId)
                .setParameter("ownerId", ownerId)
                .executeUpdate();
        entityManager.createNativeQuery("""
                        insert into plans (id, goal_id, version, status)
                        values (gen_random_uuid(), :goalId, 1, 'active')
                        """)
                .setParameter("goalId", goalId)
                .executeUpdate();
    }
}
