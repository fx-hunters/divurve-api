package com.divurve.db;

import static org.assertj.core.api.Assertions.assertThat;

import com.divurve.domain.RepositoryTestBase;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code V25__owner_fk_cascade.sql} 이 소유자 FK 의 삭제 동작을 실제로 바꿨는지 검증한다(이슈 #137).
 *
 * <p>이 테스트가 있는 이유는 <b>cascade 누락이 조용히 통과하기 때문</b>이다. 마이그레이션이
 * 적용되기만 하면 기동은 성공하고, 삭제 동작이 없다는 사실은 이슈 #138 의 정리 배치가 운영에서
 * FK 위반으로 실패할 때에야 드러난다. 그 실패는 "더미 데이터가 계속 쌓인다" 라는 증상으로만 보인다.
 *
 * <p>검증을 두 층으로 나눈 이유 —
 * <ul>
 *   <li><b>카탈로그 검사</b>({@link #everyUsersFkDeclaresDeleteAction()},
 *       {@link #deleteActionsMatchIntent()})는 <b>모든</b> 간선을 빠짐없이 본다. 행을 심어
 *       실제로 지워 보는 방식은 심는 것을 잊은 테이블을 검사하지 못한다.</li>
 *   <li><b>실제 전파</b>({@link #deletingUserRemovesOwnedRows()})는 가장 깊은 경로
 *       (users → goals → plans → plan_steps)가 끝까지 이어지는지를 본다. 카탈로그가 맞아도
 *       중간 간선 하나가 빠지면 전파는 거기서 멈춘다.</li>
 * </ul>
 *
 * <p>{@link #everyUsersFkDeclaresDeleteAction()} 는 <b>미래의 테이블</b>까지 잡는 회귀 가드다.
 * 병렬 세션이 새 소유자 테이블을 추가하면서 삭제 동작을 빠뜨리면 여기서 실패한다 — 이슈 #137 이
 * cascade 를 고른 이유가 바로 "새 테이블이 자동으로 따라오게" 하는 것이었고, 이 테스트가 그
 * 전제를 지킨다.
 */
@DisplayName("소유자 FK 삭제 동작 (V25)")
class OwnerFkCascadeTest extends RepositoryTestBase {

    /** {@code pg_constraint.confdeltype} — 'c' = cascade, 'n' = set null, 'a' = no action. */
    private static final String CASCADE = "c";
    private static final String SET_NULL = "n";

    /**
     * V25 가 선언한 간선 전체. 키는 {@code <자식 테이블>.<자식 컬럼>}.
     *
     * <p>{@code risk_profile_answers} 는 없다 — 이슈 본문의 "확인 필요" 항목이었는데 V9 가 그
     * 테이블을 drop 하고 응답을 {@code risk_profiles.answers} jsonb 로 옮겼다.
     */
    private static final Map<String, String> EXPECTED_DELETE_ACTIONS = expected();

    private static Map<String, String> expected() {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("holdings.owner_id", CASCADE);
        map.put("fx_deposits.owner_id", CASCADE);
        map.put("goals.owner_id", CASCADE);
        map.put("krw_assets.owner_id", CASCADE);
        map.put("notifications.owner_id", CASCADE);
        map.put("risk_profiles.owner_id", CASCADE);
        map.put("user_settings.owner_id", CASCADE);
        map.put("stress_test_runs.user_id", CASCADE);
        map.put("plans.goal_id", CASCADE);
        map.put("plan_steps.plan_id", CASCADE);
        map.put("plans.superseded_by", SET_NULL);
        return map;
    }

    @PersistenceContext
    private EntityManager entityManager;

    @Test
    @DisplayName("users 를 참조하는 모든 외래키에 삭제 동작이 명시돼 있다 — 새 소유자 테이블을 추가하면서 빠뜨리면 여기서 걸린다")
    void everyUsersFkDeclaresDeleteAction() {
        Map<String, String> actual = deleteActionsReferencing("users");

        assertThat(actual)
                .as("users 를 참조하는 FK 가 하나도 없다 — 조회가 조용히 빈 통과를 하고 있다")
                .isNotEmpty();
        assertThat(actual).allSatisfy((edge, action) ->
                assertThat(action)
                        .as("%s 에 삭제 동작이 없다 — cascade(소유 데이터) 또는 set null(비소유 참조) "
                                + "중 하나를 명시해야 한다", edge)
                        .isIn(CASCADE, SET_NULL));
    }

    @Test
    @DisplayName("각 외래키의 삭제 동작이 V25 의 의도와 정확히 일치한다")
    void deleteActionsMatchIntent() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.putAll(deleteActionsReferencing("users"));
        actual.putAll(deleteActionsReferencing("goals"));
        actual.putAll(deleteActionsReferencing("plans"));
        actual.putAll(deleteActionsReferencing("risk_profiles"));

        assertThat(actual).containsAllEntriesOf(EXPECTED_DELETE_ACTIONS);
    }

    @Test
    @DisplayName("유저를 지우면 소유 데이터가 함께 사라진다 — users → goals → plans → plan_steps 까지")
    void deletingUserRemovesOwnedRows() {
        UUID userId = insertUser("cascade-owner@example.com");
        UUID goalId = insertGoal(userId);
        UUID planId = insertPlan(goalId, "active", 1);
        insertPlanStep(planId, 1);
        insertHolding(userId);

        assertThat(countOwned(userId, goalId, planId)).isEqualTo(4);

        entityManager.createNativeQuery("delete from users where id = :id")
                .setParameter("id", userId)
                .executeUpdate();
        entityManager.flush();
        entityManager.clear();

        assertThat(countOwned(userId, goalId, planId)).isZero();
    }

    @Test
    @DisplayName("plans.superseded_by 는 후속 계획이 지워지면 NULL 이 된다 — 가리키던 계획을 함께 지우지 않는다")
    void supersededByIsClearedRatherThanCascaded() {
        UUID userId = insertUser("superseded-owner@example.com");
        UUID goalId = insertGoal(userId);
        UUID oldPlanId = insertPlan(goalId, "superseded", 1);
        UUID newPlanId = insertPlan(goalId, "active", 2);

        entityManager.createNativeQuery(
                        "update plans set superseded_by = :newPlan where id = :oldPlan")
                .setParameter("newPlan", newPlanId)
                .setParameter("oldPlan", oldPlanId)
                .executeUpdate();

        entityManager.createNativeQuery("delete from plans where id = :id")
                .setParameter("id", newPlanId)
                .executeUpdate();
        entityManager.flush();
        entityManager.clear();

        assertThat(count("select count(*) from plans where id = :id", "id", oldPlanId))
                .as("cascade 였다면 대체된 옛 계획까지 함께 지워졌을 것이다")
                .isEqualTo(1);
        assertThat(count(
                "select count(*) from plans where id = :id and superseded_by is null",
                "id", oldPlanId))
                .isEqualTo(1);
    }

    /** {@code parentTable} 을 참조하는 단일 컬럼 FK 의 삭제 동작을 {@code <표>.<컬럼> -> 동작} 으로 읽는다. */
    private Map<String, String> deleteActionsReferencing(String parentTable) {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = entityManager.createNativeQuery("""
                        select child.relname, att.attname, con.confdeltype::text
                          from pg_constraint con
                          join pg_class child on child.oid = con.conrelid
                          join pg_attribute att
                            on att.attrelid = con.conrelid
                           and att.attnum = con.conkey[1]
                         where con.contype = 'f'
                           and con.confrelid = cast(:parentTable as regclass)
                           and array_length(con.conkey, 1) = 1
                        """)
                .setParameter("parentTable", parentTable)
                .getResultList();

        Map<String, String> actions = new LinkedHashMap<>();
        for (Object[] row : rows) {
            actions.put(row[0] + "." + row[1], String.valueOf(row[2]));
        }
        return actions;
    }

    private long countOwned(UUID userId, UUID goalId, UUID planId) {
        return count("select count(*) from goals where owner_id = :id", "id", userId)
                + count("select count(*) from holdings where owner_id = :id", "id", userId)
                + count("select count(*) from plans where goal_id = :id", "id", goalId)
                + count("select count(*) from plan_steps where plan_id = :id", "id", planId);
    }

    private long count(String sql, String param, UUID value) {
        return ((Number) entityManager.createNativeQuery(sql)
                .setParameter(param, value)
                .getSingleResult()).longValue();
    }

    // 아래 삽입은 엔티티 대신 네이티브 SQL 을 쓴다 — 이 테스트가 검증하는 것은 DB 제약이고,
    // 엔티티 API 를 거치면 어느 계층이 막았는지가 흐려진다. id 를 Java 에서 만들어 넘기는 이유는
    // `insert ... returning id` 의 Hibernate 처리에 기대지 않기 위해서다.

    private UUID insertUser(String email) {
        return insert("""
                insert into users (id, email, name, is_demo)
                values (:id, :email, '전파 검증', true)
                """, Map.of("email", email));
    }

    private UUID insertGoal(UUID userId) {
        return insert("""
                insert into goals (id, owner_id, name, kind, purpose, currency_code, target_amount,
                                   budget_amount, is_speculative, status)
                values (:id, :ownerId, '학비', 'deadline', 'tuition', 'USD', 10000, 1000000, false, 'active')
                """, Map.of("ownerId", userId));
    }

    private UUID insertPlan(UUID goalId, String status, int version) {
        return insert("""
                insert into plans (id, goal_id, version, status)
                values (:id, :goalId, :version, :status)
                """, Map.of("goalId", goalId, "version", version, "status", status));
    }

    private UUID insertPlanStep(UUID planId, int seq) {
        return insert("""
                insert into plan_steps (id, plan_id, seq, amount, executed_amount, status)
                values (:id, :planId, :seq, 1000, 0, 'scheduled')
                """, Map.of("planId", planId, "seq", seq));
    }

    private UUID insertHolding(UUID userId) {
        return insert("""
                insert into holdings (id, owner_id, ticker, currency_code, quantity, avg_price)
                values (:id, :ownerId, 'VOO', 'USD', 10, 400)
                """, Map.of("ownerId", userId));
    }

    /** 새 id 를 만들어 삽입하고 그 id 를 돌려준다. */
    private UUID insert(String sql, Map<String, Object> params) {
        UUID id = UUID.randomUUID();
        var query = entityManager.createNativeQuery(sql).setParameter("id", id);
        params.forEach(query::setParameter);
        query.executeUpdate();
        return id;
    }
}
