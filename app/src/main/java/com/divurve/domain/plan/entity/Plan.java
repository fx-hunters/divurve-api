package com.divurve.domain.plan.entity;

import com.divurve.domain.goal.entity.Goal;
import com.divurve.domain.plan.PlanStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

/**
 * 목표(Goal)에 속한 환전 계획. 버전별로 저장되며 활성 버전은 하나만 유지된다 —
 * 그 보장은 {@code uq_plans_active_per_goal} 부분 유니크 인덱스가 한다 (명세 §21-9).
 *
 * <p>안전/기회 버킷 컬럼({@code safe_ratio}·{@code split_count}·{@code opportunity_*})과
 * {@code is_active} 는 이슈 #85 에서 제거했다. 앞의 넷은 명세 §23 이 산출 근거 불명으로
 * 지목한 값이고, {@code is_active} 는 여섯 상태를 담는 {@link #status} 가 대체한다.
 */
@Entity
@Table(name = "plans")
public class Plan {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "uuid")
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "goal_id", nullable = false)
    private Goal goal;

    @Column(nullable = false)
    private int version;

    @Column
    private String reason;

    /**
     * 계획 상태 (플래너 명세 §13.1). 이전의 {@code is_active} 를 대체한다 —
     * 여섯 상태를 boolean 하나로 표현할 수 없다.
     */
    @Column(nullable = false)
    private String status;

    /** 계획 종료일 {@code targetDate - businessDayBuffer} (명세 §9.4). 정기형은 점검 종료일. */
    @Column(name = "plan_end_date")
    private LocalDate planEndDate;

    @Embedded
    private PlanCalculationMeta calculationMeta;

    @Embedded
    private PlanCostSummary costSummary;

    /** 이 계획을 대체한 새 버전 (명세 §18). 자기 참조이므로 id 만 들고 순환 로딩을 피한다. */
    @Column(name = "superseded_by", columnDefinition = "uuid")
    private UUID supersededBy;

    @Generated(event = EventType.INSERT)
    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    /** JPA 전용 기본 생성자. */
    protected Plan() {
    }

    private Plan(Builder builder) {
        this.goal = builder.goal;
        this.version = builder.version;
        this.reason = builder.reason;
        this.status = builder.status;
        this.planEndDate = builder.planEndDate;
        this.calculationMeta = builder.calculationMeta;
        this.costSummary = builder.costSummary;
    }

    /** 필드 수가 많아 빌더로 생성한다. id/created_at 은 저장 시점에 DB 가 채운다. */
    public static Builder builder(Goal goal, int version) {
        return new Builder(goal, version);
    }

    /** Plan 생성용 빌더. */
    public static final class Builder {
        private final Goal goal;
        private final int version;
        private String reason;
        private String status = PlanStatus.DRAFT;
        private LocalDate planEndDate;
        private PlanCalculationMeta calculationMeta;
        private PlanCostSummary costSummary;

        private Builder(Goal goal, int version) {
            this.goal = goal;
            this.version = version;
        }

        public Builder reason(String reason) {
            this.reason = reason;
            return this;
        }

        public Builder status(String status) {
            this.status = status;
            return this;
        }

        public Builder planEndDate(LocalDate planEndDate) {
            this.planEndDate = planEndDate;
            return this;
        }

        public Builder calculationMeta(PlanCalculationMeta calculationMeta) {
            this.calculationMeta = calculationMeta;
            return this;
        }

        public Builder costSummary(PlanCostSummary costSummary) {
            this.costSummary = costSummary;
            return this;
        }

        public Plan build() {
            return new Plan(this);
        }
    }

    public UUID getId() {
        return id;
    }

    public Goal getGoal() {
        return goal;
    }

    public int getVersion() {
        return version;
    }

    public String getReason() {
        return reason;
    }

    public String getStatus() {
        return status;
    }

    public LocalDate getPlanEndDate() {
        return planEndDate;
    }

    public PlanCalculationMeta getCalculationMeta() {
        return calculationMeta;
    }

    public PlanCostSummary getCostSummary() {
        return costSummary;
    }

    public UUID getSupersededBy() {
        return supersededBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    /**
     * 새 버전에 밀려난 계획으로 내린다 (명세 §18).
     *
     * <p>이 전이가 끝나야 새 계획을 활성으로 올릴 수 있다 — 목표당 활성 계획은 하나이고
     * {@code uq_plans_active_per_goal} 이 그것을 DB 에서 강제한다.
     */
    public void deactivate() {
        this.status = PlanStatus.SUPERSEDED;
    }

    /** 계획을 활성으로 올린다 (명세 §18 승인 후 적용). */
    public void activate() {
        this.status = PlanStatus.ACTIVE;
    }

    /**
     * 적용 시점에 버전 번호를 다시 매긴다 (명세 §21-10).
     *
     * <p>draft 는 저장될 때 "지금 적용하면 붙을 번호"를 임시로 갖는다. 그 사이 다른 변경안이
     * 적용되면 그 번호는 이미 쓰인 번호가 되므로, 승격하는 순간 다시 정한다. 생성자가 아니라
     * 별도 메서드로 두는 이유는 이것이 <b>적용 전이의 일부</b>라서다 — draft 를 만들 때 쓰라고
     * 열어 둔 문이 아니다.
     *
     * @param version 새 버전 번호 (1 이상)
     * @throws IllegalStateException draft 가 아닌 계획의 버전을 바꾸려 한 경우
     */
    public void assignVersion(int version) {
        if (!isDraft()) {
            throw new IllegalStateException(
                    "draft 가 아닌 계획의 버전은 바꿀 수 없습니다: status=" + status);
        }
        this.version = version;
    }

    /** 테스트용 id 설정 ({@code Goal.setIdForTest} 와 같은 용도). */
    public void setIdForTest(UUID testId) {
        this.id = testId;
    }

    /** 계산되었으나 아직 적용되지 않은 계획인지 (명세 §13.1). */
    public boolean isDraft() {
        return PlanStatus.DRAFT.equals(status);
    }

    /**
     * 새 버전으로 대체됐음을 기록한다 (명세 §18).
     *
     * @param newPlanId 이 계획을 대체한 계획의 ID
     */
    public void supersededBy(UUID newPlanId) {
        this.supersededBy = newPlanId;
    }

    /** 현재 적용 중인 계획인지 (명세 §13.1). */
    public boolean isActivePlan() {
        return PlanStatus.ACTIVE.equals(status);
    }
}
