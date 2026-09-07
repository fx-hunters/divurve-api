package com.divurve.domain.plan;

import com.divurve.domain.plan.entity.Plan;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 계획 접근 리포지토리. Spring Data JPA 가 런타임 구현을 주입한다.
 * 목표 기준으로 계획 버전을 조회한다.
 *
 * <p>{@code findByGoal_IdAndIsActiveTrue} 는 이슈 #85 에서 {@link #findByGoal_IdAndStatus} 로
 * 대체했다 — 계획 상태가 boolean 이 아니라 여섯 값이 됐다 (명세 §13.1).
 */
public interface PlanRepository extends JpaRepository<Plan, UUID> {

    /** 목표의 모든 계획 버전을 조회한다. */
    List<Plan> findByGoal_Id(UUID goalId);

    /** 목표의 계획을 버전 내림차순으로 조회한다 — 이력 화면은 최신이 위다 (명세 §18). */
    List<Plan> findByGoal_IdOrderByVersionDesc(UUID goalId);

    /**
     * 목표의 특정 상태 계획을 조회한다.
     *
     * <p>활성 계획은 하나뿐이지만({@code uq_plans_active_per_goal}) 목록으로 받는다 —
     * 인덱스가 보장하는 것과 별개로, 조회 자체가 둘 이상을 만나도 예외로 터지지 않아야
     * 이전 버전을 내리는 정리 경로가 막히지 않는다.
     */
    List<Plan> findByGoal_IdAndStatus(UUID goalId, String status);

    /** 목표의 활성 계획 하나. */
    Optional<Plan> findFirstByGoal_IdAndStatus(UUID goalId, String status);

    /** 목표의 최신 계획 버전(가장 높은 버전 번호)을 조회한다. */
    Optional<Plan> findTopByGoal_IdOrderByVersionDesc(UUID goalId);

    /**
     * 특정 상태를 제외한 최신 계획 버전 (명세 §21-10).
     *
     * <p>버전 번호를 매길 때 {@code draft} 를 빼는 데 쓴다 — 시나리오 미리보기가 draft 를
     * 저장하므로, 그것까지 세면 미리보기를 누른 횟수만큼 버전이 건너뛴다.
     */
    Optional<Plan> findTopByGoal_IdAndStatusNotOrderByVersionDesc(UUID goalId, String status);
}
