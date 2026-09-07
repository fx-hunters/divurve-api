package com.divurve.api.dto.admin;

import com.divurve.domain.goal.entity.Goal;
import com.divurve.domain.holding.entity.Deposit;
import com.divurve.domain.holding.entity.Holding;
import com.divurve.domain.holding.entity.KrwAsset;
import com.divurve.domain.plan.entity.Plan;
import com.divurve.domain.plan.entity.PlanStep;
import com.divurve.domain.settings.entity.RiskProfile;
import com.divurve.domain.settings.entity.UserSettings;
import com.divurve.domain.stress.entity.StressTestRun;
import com.divurve.domain.user.AdminDataQueryService;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 사용자 한 명의 전 도메인 데이터 (이슈 #111).
 *
 * <p><b>엔티티를 그대로 직렬화하지 않는 이유</b> — 모든 자산·목표 엔티티가 {@code owner}(또는
 * {@code goal} → {@code owner}) 참조를 갖는다. 그대로 내보내면 {@code password_hash} 가 응답에
 * 실리고, 지연 로딩 프록시가 직렬화 시점에 터진다. 그래서 소유자 참조는 <b>id 로만</b> 담는다.
 *
 * <p>그 한 가지를 빼면 <b>모든 컬럼</b>을 담는다 — 운영 점검이 목적이라 표시용으로 고르지 않는다.
 */
public record AdminUserDataResponse(
        List<AdminHolding> holdings,
        List<AdminDeposit> fxDeposits,
        List<AdminKrwAsset> krwAssets,
        List<AdminGoal> goals,
        List<AdminPlan> plans,
        List<AdminPlanStep> planSteps,
        AdminRiskProfile riskProfile,
        AdminUserSettings userSettings,
        List<AdminStressTestRun> stressTestRuns) {

    /** 서비스가 모은 데이터를 응답 형태로 옮긴다. */
    public static AdminUserDataResponse from(AdminDataQueryService.UserDataBundle bundle) {
        return new AdminUserDataResponse(
                bundle.holdings().stream().map(AdminHolding::from).toList(),
                bundle.deposits().stream().map(AdminDeposit::from).toList(),
                bundle.krwAssets().stream().map(AdminKrwAsset::from).toList(),
                bundle.goals().stream().map(AdminGoal::from).toList(),
                bundle.plans().stream().map(AdminPlan::from).toList(),
                bundle.planSteps().stream().map(AdminPlanStep::from).toList(),
                bundle.riskProfile() == null ? null : AdminRiskProfile.from(bundle.riskProfile()),
                bundle.userSettings() == null ? null : AdminUserSettings.from(bundle.userSettings()),
                bundle.stressTestRuns().stream().map(AdminStressTestRun::from).toList());
    }

    /** {@code holdings} 전 컬럼. */
    public record AdminHolding(
            UUID id, UUID ownerId, String ticker, String currencyCode, double quantity,
            double avgPrice, LocalDate purchasedAt, BigDecimal purchaseFxRateKrw,
            String purchaseFxRateSource, LocalDate purchaseFxRateAsOf, Instant createdAt) {

        static AdminHolding from(Holding h) {
            return new AdminHolding(
                    h.getId(), h.getOwner().getId(), h.getTicker(), h.getCurrencyCode(),
                    h.getQuantity(), h.getAvgPrice(), h.getPurchasedAt(), h.getPurchaseFxRateKrw(),
                    h.getPurchaseFxRateSource(), h.getPurchaseFxRateAsOf(), h.getCreatedAt());
        }
    }

    /** {@code fx_deposits} 전 컬럼. */
    public record AdminDeposit(
            UUID id, UUID ownerId, String currencyCode, BigDecimal amount, LocalDate purchasedAt,
            BigDecimal purchaseFxRateKrw, String purchaseFxRateSource,
            LocalDate purchaseFxRateAsOf, Instant createdAt) {

        static AdminDeposit from(Deposit d) {
            return new AdminDeposit(
                    d.getId(), d.getOwner().getId(), d.getCurrencyCode(), d.getAmount(),
                    d.getPurchasedAt(), d.getPurchaseFxRateKrw(), d.getPurchaseFxRateSource(),
                    d.getPurchaseFxRateAsOf(), d.getCreatedAt());
        }
    }

    /** {@code krw_assets} 전 컬럼. */
    public record AdminKrwAsset(
            UUID id, UUID ownerId, String kind, String label, long amountKrw,
            Instant createdAt, Instant updatedAt) {

        static AdminKrwAsset from(KrwAsset a) {
            return new AdminKrwAsset(
                    a.getId(), a.getOwner().getId(), a.getKind(), a.getLabel(), a.getAmountKrw(),
                    a.getCreatedAt(), a.getUpdatedAt());
        }
    }

    /** {@code goals} 전 컬럼. */
    public record AdminGoal(
            UUID id, UUID ownerId, String name, String kind, String purpose, String currencyCode,
            double targetAmount, LocalDate targetDate, String recurInterval, long budgetAmount,
            String budgetCurrencyCode, String budgetPeriod, boolean isSpeculative, String status,
            String goalType, double allocatedHoldingAmount, String priorityConstraint,
            String preferredCadence, LocalDate recurStartDate, Integer reviewHorizonMonths,
            String linkedPurposeName, Instant createdAt) {

        static AdminGoal from(Goal g) {
            return new AdminGoal(
                    g.getId(), g.getOwner().getId(), g.getName(), g.getKind(), g.getPurpose(),
                    g.getCurrencyCode(), g.getTargetAmount(), g.getTargetDate(),
                    g.getRecurInterval(), g.getBudgetAmount(), g.getBudgetCurrencyCode(),
                    g.getBudgetPeriod(), g.isSpeculative(), g.getStatus(), g.getGoalType(),
                    g.getAllocatedHoldingAmount(), g.getPriorityConstraint(),
                    g.getPreferredCadence(), g.getRecurStartDate(), g.getReviewHorizonMonths(),
                    g.getLinkedPurposeName(), g.getCreatedAt());
        }
    }

    /** {@code plans} 전 컬럼 — 임베디드({@code PlanCalculationMeta}·{@code PlanCostSummary}) 포함. */
    public record AdminPlan(
            UUID id, UUID goalId, int version, String reason, String status,
            LocalDate planEndDate, String policyVersion, Instant rateAsOf, Instant forecastAsOf,
            Double baseRate, Double rateLow, Double rateHigh, Double spreadRatio, Long feeKrw,
            Integer quoteUnit, String budgetState, Long lowCostKrw, Long baseCostKrw,
            Long highCostKrw, UUID supersededBy, Instant createdAt) {

        static AdminPlan from(Plan p) {
            var meta = p.getCalculationMeta();
            var cost = p.getCostSummary();
            return new AdminPlan(
                    p.getId(), p.getGoal().getId(), p.getVersion(), p.getReason(), p.getStatus(),
                    p.getPlanEndDate(),
                    meta == null ? null : meta.getPolicyVersion(),
                    meta == null ? null : meta.getRateAsOf(),
                    meta == null ? null : meta.getForecastAsOf(),
                    meta == null ? null : meta.getBaseRate(),
                    meta == null ? null : meta.getRateLow(),
                    meta == null ? null : meta.getRateHigh(),
                    meta == null ? null : meta.getSpreadRatio(),
                    meta == null ? null : meta.getFeeKrw(),
                    meta == null ? null : meta.getQuoteUnit(),
                    cost == null ? null : cost.getBudgetState(),
                    cost == null ? null : cost.getLowCostKrw(),
                    cost == null ? null : cost.getBaseCostKrw(),
                    cost == null ? null : cost.getHighCostKrw(),
                    p.getSupersededBy(), p.getCreatedAt());
        }
    }

    /** {@code plan_steps} 전 컬럼. */
    public record AdminPlanStep(
            UUID id, UUID planId, int seq, LocalDate scheduledDate, double amount,
            double executedAmount, String status, Long budgetKrw, Double baseRate,
            Long lowCostKrw, Long highCostKrw, Double executedRate, LocalDate executedDate,
            String executionKey, Instant createdAt) {

        static AdminPlanStep from(PlanStep s) {
            return new AdminPlanStep(
                    s.getId(), s.getPlan().getId(), s.getSeq(), s.getScheduledDate(),
                    s.getAmount(), s.getExecutedAmount(), s.getStatus(), s.getBudgetKrw(),
                    s.getBaseRate(), s.getLowCostKrw(), s.getHighCostKrw(), s.getExecutedRate(),
                    s.getExecutedDate(), s.getExecutionKey(), s.getCreatedAt());
        }
    }

    /** {@code risk_profiles} 전 컬럼. */
    public record AdminRiskProfile(
            UUID id, UUID ownerId, String status, String riskType, Integer score,
            BigDecimal concentrationThreshold, BigDecimal safeRatioAdjust,
            Map<String, String> answers, Map<String, String> detailAnswers,
            Map<String, String> detailProgress, LocalDate diagnosedOn, boolean isManual,
            Instant createdAt, Instant updatedAt) {

        static AdminRiskProfile from(RiskProfile r) {
            return new AdminRiskProfile(
                    r.getId(), r.getOwner().getId(), r.getStatus(), r.getRiskType(), r.getScore(),
                    r.getConcentrationThreshold(), r.getSafeRatioAdjust(), r.getAnswers(),
                    r.getDetailAnswers(), r.getDetailProgress(), r.getDiagnosedOn(),
                    r.isManual(), r.getCreatedAt(), r.getUpdatedAt());
        }
    }

    /** {@code user_settings} 전 컬럼. */
    public record AdminUserSettings(
            UUID id, UUID ownerId, String defaultBankCode, double fxDiscountRatio,
            String explainLevel, String explainDomain, boolean notifyStepDue,
            boolean notifyRegimeShift, boolean notifyDeadlineNear, boolean notifyTargetZone,
            boolean notifyConcentration, Instant createdAt) {

        static AdminUserSettings from(UserSettings s) {
            return new AdminUserSettings(
                    s.getId(), s.getOwner().getId(), s.getDefaultBankCode(),
                    s.getFxDiscountRatio(), s.getExplainLevel(), s.getExplainDomain(),
                    s.isNotifyStepDue(), s.isNotifyRegimeShift(), s.isNotifyDeadlineNear(),
                    s.isNotifyTargetZone(), s.isNotifyConcentration(), s.getCreatedAt());
        }
    }

    /** {@code stress_test_runs} 전 컬럼. */
    public record AdminStressTestRun(
            UUID id, UUID ownerId, String scenarioCode, LocalDate baseDate,
            BigDecimal equityShockPct, BigDecimal fxShockPct, BigDecimal equityEffectKrw,
            BigDecimal fxEffectKrw, BigDecimal totalEffectKrw, LocalDate snapshotDate,
            Instant createdAt) {

        static AdminStressTestRun from(StressTestRun r) {
            return new AdminStressTestRun(
                    r.getId(), r.getOwner().getId(), r.getScenarioCode(), r.getBaseDate(),
                    r.getEquityShockPct(), r.getFxShockPct(), r.getEquityEffectKrw(),
                    r.getFxEffectKrw(), r.getTotalEffectKrw(), r.getSnapshotDate(),
                    r.getCreatedAt());
        }
    }
}
