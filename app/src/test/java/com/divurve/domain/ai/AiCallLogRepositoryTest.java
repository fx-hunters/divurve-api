package com.divurve.domain.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.divurve.domain.RepositoryTestBase;
import com.divurve.domain.ai.entity.AiCallLog;
import com.divurve.domain.port.TokenUsage;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

/**
 * {@code V26__ai_call_logs.sql} 이 실제로 적용되고, 엔티티 매핑·CHECK 제약·FK 삭제 동작·조회 쿼리가
 * 의도대로 동작하는지 실제 Postgres(Testcontainers)로 검증한다 (이슈 #143).
 *
 * <p>가장 중요한 것은 {@link #userDeletionKeepsCostHistory()} 다 — 이 표의 {@code user_id} 는
 * 소유자 FK 규칙(V25, 이슈 #137)의 <b>유일한 예외</b>로 {@code on delete set null} 이어야 한다.
 * cascade 로 잘못 걸리면 이슈 #138 의 데모 정리가 매일 도는 동안 데모 계정이 쓴 AI 비용 이력이
 * 함께 사라지고, 그 사실은 비용을 확인하려 할 때에야 드러난다.
 */
@DisplayName("AI 호출 로그 스키마·조회 (V26)")
class AiCallLogRepositoryTest extends RepositoryTestBase {

    private static final Instant DAY_1 = Instant.parse("2026-09-07T10:00:00Z");
    private static final Instant DAY_2 = Instant.parse("2026-09-08T10:00:00Z");

    /**
     * 열린 구간의 경계. {@code null} 을 넘기지 않는 이유는 {@code searchForAdmin} javadoc 에 있다 —
     * Hibernate 가 null {@code Instant} 를 PostgreSQL 이 {@code timestamp} 로 캐스트할 수 없는
     * 타입으로 바인딩한다. 실제 호출자인 {@link AiCallLogQueryService} 도 같은 값을 채워 넘긴다.
     */
    private static final Instant OPEN_START = AiCallLogQueryService.OPEN_START;
    private static final Instant OPEN_END = AiCallLogQueryService.OPEN_END;

    @Autowired
    private AiCallLogRepository aiCallLogRepository;

    @PersistenceContext
    private EntityManager entityManager;

    @Test
    @DisplayName("사용자를 지워도 비용 이력은 남고 user_id 만 비워진다 — V25 cascade 규칙의 예외")
    void userDeletionKeepsCostHistory() {
        UUID userId = insertUser("ai-cost@example.com");
        AiCallLog saved = aiCallLogRepository.saveAndFlush(AiCallLog.narrate(
                DAY_1, userId, true, "forecast_summary", "claude-opus-5",
                TokenUsage.of(100, 40), AiCallOutcome.SUCCESS, null, 500, null));

        entityManager.createNativeQuery("delete from users where id = :id")
                .setParameter("id", userId)
                .executeUpdate();
        entityManager.flush();
        entityManager.clear();

        AiCallLog found = aiCallLogRepository.findById(saved.getId()).orElseThrow();
        assertThat(found.getUserId())
                .as("사용자는 사라졌으므로 참조만 비어야 한다")
                .isNull();
        assertThat(found.isDemo())
                .as("데모 트래픽이었다는 사실은 남아야 집계가 성립한다")
                .isTrue();
        assertThat(found.getInputTokens()).isEqualTo(100);
        assertThat(found.getModel()).isEqualTo("claude-opus-5");
    }

    @Test
    @DisplayName("LLM 을 부르지 않은 요청은 model 이 비고 토큰이 0 이다")
    void requestWithoutLlmHasNoModel() {
        AiCallLog saved = aiCallLogRepository.saveAndFlush(AiCallLog.narrate(
                DAY_1, insertUser("template@example.com"), false, "profile_fit", null,
                TokenUsage.NONE, AiCallOutcome.SUCCESS, null, 3, null));

        AiCallLog found = aiCallLogRepository.findById(saved.getId()).orElseThrow();
        assertThat(found.getModel()).isNull();
        assertThat(found.getInputTokens()).isZero();
        assertThat(found.getOutputTokens()).isZero();
        assertThat(found.getCacheReadInputTokens())
                .as("측정되지 않은 캐시 토큰은 0 이 아니라 null 이다")
                .isNull();
    }

    @Test
    @DisplayName("extract 경로는 user_id 와 surface 가 없다 — 배치에서 돈다")
    void extractHasNoUserOrSurface() {
        AiCallLog saved = aiCallLogRepository.saveAndFlush(AiCallLog.extract(
                DAY_1, null, "claude-opus-5", TokenUsage.of(900, 300),
                AiCallOutcome.SUCCESS, 2100, null));

        AiCallLog found = aiCallLogRepository.findById(saved.getId()).orElseThrow();
        assertThat(found.getPurpose()).isEqualTo("extract");
        assertThat(found.getUserId()).isNull();
        assertThat(found.getSurface()).isNull();
        assertThat(found.getFallbackReason()).isNull();
    }

    // CHECK 위반 두 건을 한 테스트에 넣지 않는다 — 첫 위반으로 트랜잭션이 abort 되어 두 번째
    // insert 는 제약이 아니라 "current transaction is aborted" 로 실패한다. 그러면 무엇을
    // 검증했는지가 흐려진다.

    @Test
    @DisplayName("어휘 밖의 purpose 는 CHECK 제약이 막는다")
    void checkConstraintGuardsPurpose() {
        // 네이티브 쿼리 예외는 Spring 의 DataAccessException 으로 번역되지 않는다 —
        // Hibernate 예외가 그대로 올라온다.
        assertThatThrownBy(() -> insertRaw("bogus", "success"))
                .isInstanceOf(ConstraintViolationException.class)
                .hasMessageContaining("chk_ai_call_logs_purpose");
    }

    @Test
    @DisplayName("어휘 밖의 outcome 은 CHECK 제약이 막는다")
    void checkConstraintGuardsOutcome() {
        assertThatThrownBy(() -> insertRaw("narrate", "bogus"))
                .isInstanceOf(ConstraintViolationException.class)
                .hasMessageContaining("chk_ai_call_logs_outcome");
    }

    @Test
    @DisplayName("필터 없이 조회하면 전체가 나온다 — 타입 없는 바인드 파라미터로 깨지지 않는다")
    void searchWithoutFiltersReturnsAll() {
        seedThree();

        assertThat(aiCallLogRepository.searchForAdmin(
                OPEN_START, OPEN_END, null, null, null, null, PageRequest.of(0, 10)).getTotalElements())
                .isEqualTo(3);
    }

    @Test
    @DisplayName("purpose·outcome·is_demo·기간으로 걸러낸다")
    void searchAppliesFilters() {
        seedThree();
        PageRequest page = PageRequest.of(0, 10, Sort.by(Sort.Direction.DESC, "requestedAt"));

        assertThat(aiCallLogRepository.searchForAdmin(
                OPEN_START, OPEN_END, "extract", null, null, null, page).getContent())
                .singleElement()
                .satisfies(log -> assertThat(log.getPurpose()).isEqualTo("extract"));
        assertThat(aiCallLogRepository.searchForAdmin(
                OPEN_START, OPEN_END, null, null, "fallback", null, page).getContent())
                .singleElement()
                .satisfies(log -> assertThat(log.getFallbackReason()).isEqualTo("provider_error"));
        assertThat(aiCallLogRepository.searchForAdmin(
                OPEN_START, OPEN_END, null, "forecast_summary", null, null, page).getTotalElements())
                .isEqualTo(2);
        assertThat(aiCallLogRepository.searchForAdmin(
                OPEN_START, OPEN_END, null, null, null, true, page).getTotalElements())
                .as("시드에 데모 트래픽은 없다")
                .isZero();
        assertThat(aiCallLogRepository.searchForAdmin(
                OPEN_START, OPEN_END, null, null, null, false, page).getTotalElements())
                .as("extract 경로는 사용자 세션이 아니므로 항상 is_demo=false 다")
                .isEqualTo(3);
        assertThat(aiCallLogRepository.searchForAdmin(
                DAY_2, OPEN_END, null, null, null, null, page).getTotalElements())
                .isEqualTo(1);
        assertThat(aiCallLogRepository.searchForAdmin(
                OPEN_START, DAY_1, null, null, null, null, page).getTotalElements())
                .isEqualTo(2);
    }

    @Test
    @DisplayName("집계는 일자·용도·모델별로 합산하고 최신 일자를 먼저 낸다")
    void summarizeGroupsByDayPurposeModel() {
        seedThree();

        List<Object[]> rows = aiCallLogRepository.summarize(null, null);

        // 칸은 둘이다 — DAY_1 의 narrate 두 건이 한 칸으로 합쳐지고, DAY_2 의 extract 가 한 칸이다.
        assertThat(rows).hasSize(2);
        assertThat(((java.sql.Date) rows.get(0)[0]).toLocalDate())
                .isEqualTo(LocalDate.of(2026, 9, 8));
        // DAY_1 의 narrate/claude-opus-5 두 건이 한 칸으로 합쳐진다.
        Object[] day1Narrate = rows.stream()
                .filter(row -> ((java.sql.Date) row[0]).toLocalDate().equals(LocalDate.of(2026, 9, 7)))
                .filter(row -> "narrate".equals(row[1]))
                .findFirst()
                .orElseThrow();
        assertThat(((Number) day1Narrate[3]).longValue()).isEqualTo(2);
        assertThat(((Number) day1Narrate[4]).longValue()).isEqualTo(150);
        assertThat(((Number) day1Narrate[5]).longValue()).isEqualTo(60);
    }

    @Test
    @DisplayName("집계도 기간으로 걸러낸다")
    void summarizeAppliesRange() {
        seedThree();

        assertThat(aiCallLogRepository.summarize(DAY_2, null)).hasSize(1);
        assertThat(aiCallLogRepository.summarize(null, DAY_1)).hasSize(1);
    }

    /**
     * DAY_1 에 narrate 2건(성공 1 + 폴백 1, 같은 모델), DAY_2 에 extract 1건. 총 3행이고 집계 칸은
     * 2개다 — 같은 일자·용도·모델의 두 건이 한 칸으로 합쳐지는지가 이 구성으로 드러난다.
     */
    private void seedThree() {
        UUID userId = insertUser("seed@example.com");
        aiCallLogRepository.save(AiCallLog.narrate(DAY_1, userId, false, "forecast_summary",
                "claude-opus-5", TokenUsage.of(100, 40), AiCallOutcome.SUCCESS, null, 500, null));
        aiCallLogRepository.save(AiCallLog.narrate(DAY_1, userId, false, "forecast_summary",
                "claude-opus-5", TokenUsage.of(50, 20), AiCallOutcome.FALLBACK, "provider_error",
                5000, "IOException: timeout"));
        aiCallLogRepository.save(AiCallLog.extract(DAY_2, null, "claude-opus-5",
                TokenUsage.of(900, 300), AiCallOutcome.SUCCESS, 2100, null));
        aiCallLogRepository.flush();
    }

    private void insertRaw(String purpose, String outcome) {
        entityManager.createNativeQuery("""
                        insert into ai_call_logs (requested_at, purpose, model, outcome)
                        values (:requestedAt, :purpose, 'claude-opus-5', :outcome)
                        """)
                .setParameter("requestedAt", DAY_1)
                .setParameter("purpose", purpose)
                .setParameter("outcome", outcome)
                .executeUpdate();
        entityManager.flush();
    }

    private UUID insertUser(String email) {
        UUID id = UUID.randomUUID();
        entityManager.createNativeQuery("""
                        insert into users (id, email, name, is_demo)
                        values (:id, :email, '기록 검증', true)
                        """)
                .setParameter("id", id)
                .setParameter("email", email)
                .executeUpdate();
        return id;
    }
}
