package com.divurve.domain.goal.entity;

import com.divurve.domain.goal.GoalType;
import com.divurve.domain.goal.PriorityConstraint;
import com.divurve.domain.user.entity.User;
import jakarta.persistence.Column;
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
 * 외화 목표. 소유자(User) 기준으로만 조회된다 (NFR-SE-03).
 * held_amount/suggested 등 계산값은 저장하지 않고 이후 engine·deposits 조회로 산출한다.
 */
@Entity
@Table(name = "goals")
public class Goal {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "uuid")
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "owner_id", nullable = false)
    private User owner;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String kind;

    @Column(nullable = false)
    private String purpose;

    @Column(name = "currency_code", nullable = false)
    private String currencyCode;

    @Column(name = "target_amount", nullable = false)
    private double targetAmount;

    @Column(name = "target_date")
    private LocalDate targetDate;

    @Column(name = "recur_interval")
    private String recurInterval;

    @Column(name = "budget_amount", nullable = false)
    private long budgetAmount;

    @Column(name = "budget_currency_code")
    private String budgetCurrencyCode;

    @Column(name = "budget_period")
    private String budgetPeriod;

    @Column(name = "is_speculative", nullable = false)
    private boolean isSpeculative;

    @Column(nullable = false)
    private String status;

    /** 마감형(deadline) / 정기형(recurring) — 플래너 명세 §4. */
    @Column(name = "goal_type", nullable = false)
    private String goalType;

    /**
     * 이 목표에 배정한 보유 외화 (명세 §5.1).
     * 보유 외화 전체를 자동으로 목표에 넣지 않는다 — 사용자가 배정 금액을 직접 확인한다.
     */
    @Column(name = "allocated_holding_amount", nullable = false)
    private double allocatedHoldingAmount;

    /**
     * 상황이 바뀌었을 때 우선 유지할 조건 {@code amount/date/budget} (명세 §5.1·§17).
     * 시나리오 재계산은 이 값을 기준으로 하며, 시스템이 알리지 않고 임의로 바꾸지 않는다.
     */
    @Column(name = "priority_constraint", nullable = false)
    private String priorityConstraint;

    /** 마감형이 원하는 준비 주기 {@code weekly/biweekly/monthly/custom} (명세 §5.2). */
    @Column(name = "preferred_cadence")
    private String preferredCadence;

    /** 정기형 첫 계획 시작일 (명세 §5.3). */
    @Column(name = "recur_start_date")
    private LocalDate recurStartDate;

    /** 정기형 점검 기간(개월) — Curve 의 마지막 노드가 "다음 점검"인 지점 (명세 §5.3·§10.3). */
    @Column(name = "review_horizon_months")
    private Integer reviewHorizonMonths;

    /** ETF·외화예금 등 정기형 자금의 사용 목적 (명세 §5.3). */
    @Column(name = "linked_purpose_name")
    private String linkedPurposeName;

    @Generated(event = EventType.INSERT)
    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    /** JPA 전용 기본 생성자. */
    protected Goal() {
    }

    private Goal(Builder builder) {
        this.owner = builder.owner;
        this.name = builder.name;
        this.kind = builder.kind;
        this.purpose = builder.purpose;
        this.currencyCode = builder.currencyCode;
        this.targetAmount = builder.targetAmount;
        this.targetDate = builder.targetDate;
        this.recurInterval = builder.recurInterval;
        this.budgetAmount = builder.budgetAmount;
        this.budgetCurrencyCode = builder.budgetCurrencyCode;
        this.budgetPeriod = builder.budgetPeriod;
        this.isSpeculative = builder.isSpeculative;
        this.status = builder.status;
        this.goalType = builder.goalType;
        this.allocatedHoldingAmount = builder.allocatedHoldingAmount;
        this.priorityConstraint = builder.priorityConstraint;
        this.preferredCadence = builder.preferredCadence;
        this.recurStartDate = builder.recurStartDate;
        this.reviewHorizonMonths = builder.reviewHorizonMonths;
        this.linkedPurposeName = builder.linkedPurposeName;
    }

    /** 필드 수가 많아 빌더로 생성한다. id/created_at 은 저장 시점에 DB 가 채운다. */
    public static Builder builder(User owner, String name, String kind, String purpose, String currencyCode) {
        return new Builder(owner, name, kind, purpose, currencyCode);
    }

    /** Goal 생성용 빌더. 필수값은 {@link #builder} 인자로, 선택값은 체이닝으로 지정한다. */
    public static final class Builder {
        private final User owner;
        private final String name;
        private final String kind;
        private final String purpose;
        private final String currencyCode;
        private double targetAmount;
        private LocalDate targetDate;
        private String recurInterval;
        private long budgetAmount;
        private String budgetCurrencyCode;
        private String budgetPeriod;
        private boolean isSpeculative;
        private String status;
        private String goalType = GoalType.DEADLINE;
        private double allocatedHoldingAmount;
        private String priorityConstraint = PriorityConstraint.AMOUNT;
        private String preferredCadence;
        private LocalDate recurStartDate;
        private Integer reviewHorizonMonths;
        private String linkedPurposeName;

        private Builder(User owner, String name, String kind, String purpose, String currencyCode) {
            this.owner = owner;
            this.name = name;
            this.kind = kind;
            this.purpose = purpose;
            this.currencyCode = currencyCode;
        }

        public Builder targetAmount(double targetAmount) {
            this.targetAmount = targetAmount;
            return this;
        }

        public Builder targetDate(LocalDate targetDate) {
            this.targetDate = targetDate;
            return this;
        }

        public Builder recurInterval(String recurInterval) {
            this.recurInterval = recurInterval;
            return this;
        }

        public Builder budgetAmount(long budgetAmount) {
            this.budgetAmount = budgetAmount;
            return this;
        }

        public Builder budgetCurrencyCode(String budgetCurrencyCode) {
            this.budgetCurrencyCode = budgetCurrencyCode;
            return this;
        }

        public Builder budgetPeriod(String budgetPeriod) {
            this.budgetPeriod = budgetPeriod;
            return this;
        }

        public Builder isSpeculative(boolean isSpeculative) {
            this.isSpeculative = isSpeculative;
            return this;
        }

        public Builder status(String status) {
            this.status = status;
            return this;
        }

        public Builder goalType(String goalType) {
            this.goalType = goalType;
            return this;
        }

        public Builder allocatedHoldingAmount(double allocatedHoldingAmount) {
            this.allocatedHoldingAmount = allocatedHoldingAmount;
            return this;
        }

        public Builder priorityConstraint(String priorityConstraint) {
            this.priorityConstraint = priorityConstraint;
            return this;
        }

        public Builder preferredCadence(String preferredCadence) {
            this.preferredCadence = preferredCadence;
            return this;
        }

        public Builder recurStartDate(LocalDate recurStartDate) {
            this.recurStartDate = recurStartDate;
            return this;
        }

        public Builder reviewHorizonMonths(Integer reviewHorizonMonths) {
            this.reviewHorizonMonths = reviewHorizonMonths;
            return this;
        }

        public Builder linkedPurposeName(String linkedPurposeName) {
            this.linkedPurposeName = linkedPurposeName;
            return this;
        }

        public Goal build() {
            return new Goal(this);
        }
    }

    public UUID getId() {
        return id;
    }

    public User getOwner() {
        return owner;
    }

    public String getName() {
        return name;
    }

    public String getKind() {
        return kind;
    }

    public String getPurpose() {
        return purpose;
    }

    public String getCurrencyCode() {
        return currencyCode;
    }

    public double getTargetAmount() {
        return targetAmount;
    }

    public LocalDate getTargetDate() {
        return targetDate;
    }

    public String getRecurInterval() {
        return recurInterval;
    }

    public long getBudgetAmount() {
        return budgetAmount;
    }

    public String getBudgetCurrencyCode() {
        return budgetCurrencyCode;
    }

    public String getBudgetPeriod() {
        return budgetPeriod;
    }

    public boolean isSpeculative() {
        return isSpeculative;
    }

    public String getStatus() {
        return status;
    }

    public String getGoalType() {
        return goalType;
    }

    public double getAllocatedHoldingAmount() {
        return allocatedHoldingAmount;
    }

    public String getPriorityConstraint() {
        return priorityConstraint;
    }

    public String getPreferredCadence() {
        return preferredCadence;
    }

    public LocalDate getRecurStartDate() {
        return recurStartDate;
    }

    public Integer getReviewHorizonMonths() {
        return reviewHorizonMonths;
    }

    public String getLinkedPurposeName() {
        return linkedPurposeName;
    }

    /** 정기형 목표인지 — Curve 의 마지막 노드가 "다음 점검"이다 (명세 §4·§10.3). */
    public boolean isRecurring() {
        return GoalType.RECURRING.equals(goalType);
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    /** 목표 상태를 변경한다. */
    public void setStatus(String status) {
        this.status = status;
    }

    /** 목표명을 업데이트한다. */
    public void setName(String name) {
        this.name = name;
    }

    /** 목표금액을 업데이트한다. */
    public void setTargetAmount(double targetAmount) {
        this.targetAmount = targetAmount;
    }

    /** 목표날짜를 업데이트한다. */
    public void setTargetDate(LocalDate targetDate) {
        this.targetDate = targetDate;
    }

    /** 예산액을 업데이트한다. */
    public void setBudgetAmount(long budgetAmount) {
        this.budgetAmount = budgetAmount;
    }

    /** 예산 기간을 업데이트한다. */
    public void setBudgetPeriod(String budgetPeriod) {
        this.budgetPeriod = budgetPeriod;
    }

    /** 투자성향 플래그를 업데이트한다. */
    public void setSpeculative(boolean isSpeculative) {
        this.isSpeculative = isSpeculative;
    }

    /** 이 목표에 배정한 보유 외화를 업데이트한다 (명세 §16 "추가 외화 확보"). */
    public void setAllocatedHoldingAmount(double allocatedHoldingAmount) {
        this.allocatedHoldingAmount = allocatedHoldingAmount;
    }

    /** 우선 유지 조건을 업데이트한다. 사용자의 명시적 선택으로만 바뀐다 (명세 §17). */
    public void setPriorityConstraint(String priorityConstraint) {
        this.priorityConstraint = priorityConstraint;
    }

    /** 마감형 준비 주기를 업데이트한다 (명세 §5.2). */
    public void setPreferredCadence(String preferredCadence) {
        this.preferredCadence = preferredCadence;
    }

    /** 정기형 첫 계획 시작일을 업데이트한다 (명세 §5.3). */
    public void setRecurStartDate(LocalDate recurStartDate) {
        this.recurStartDate = recurStartDate;
    }

    /** 정기형 점검 기간을 업데이트한다 (명세 §5.3). */
    public void setReviewHorizonMonths(Integer reviewHorizonMonths) {
        this.reviewHorizonMonths = reviewHorizonMonths;
    }

    /** 테스트용 id 설정 (반사를 사용한다). */
    public void setIdForTest(UUID testId) {
        this.id = testId;
    }
}
