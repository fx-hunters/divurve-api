package com.divurve.api.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import io.swagger.v3.oas.annotations.media.Schema;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * 회귀 방지 — {@code api/dto} 의 상태·코드류 {@code String} 필드가 값 목록 안내 없이 나가는 것을
 * 막는다(이슈 #91). 이름이 {@code Status}·{@code Code}·{@code State}·{@code Badge} 로 끝나는
 * 필드를 대상으로 한다 — 넷 다 이 레포에서 실제로 관측되는 접미사이고(예: {@code concentrationStatus},
 * {@code headlineCode}, {@code budgetState}, {@code regimeBadge}), {@code state}·{@code badge} 를
 * 정확히 그 이름으로만 좁히면 {@code regimeBadge}·{@code budgetState} 처럼 접두어가 붙은 실제 필드를
 * 놓친다.
 *
 * <p><b>판정 기준</b>: 값이 고정 집합이면 {@link Schema#allowableValues()} 를, 동적으로 조합되거나
 * 마스터데이터를 참조해 열거가 원리적으로 불가능하면(예: {@code headlineCode}·{@code currencyCode})
 * {@link Schema#description()} 에 생성·값 규칙을 설명한다 — 어느 쪽도 없으면 위반이다.
 *
 * <p><b>그레이스 목록(신규 위반 금지, 기존만 예외)</b>: 이 테스트를 처음 도입하는 시점에 이미 위반
 * 상태였던 필드는 {@link #GOAL_PLAN_STATUS_VOCAB_PENDING}·{@link #OUT_OF_SCOPE_UNOWNED} 두 목록에
 * 명시적으로 올려 눈에 보이게 예외 처리한다. <b>두 목록에 없는 필드는 그대로 실패한다</b> — 새 DTO 를
 * 추가하며 이 규칙을 어기면 그레이스 목록에 조용히 끼워 넣지 말고 해당 DTO 를 직접 고쳐야 한다.
 */
class DtoEnumerableFieldDocumentationTest {

    /**
     * 목표(Goal)·계획(Plan) 상태·예산가능상태 어휘. 이슈 #84·#85 가 goal/plan 상태값을 재정의할
     * 예정이라 지금 값을 확정해 문서화하면 그 이슈가 끝나기 전에 틀린 문서가 된다(이슈 #91 작업
     * 지시사항의 "goal/plan 계열 DTO 수정 금지" — {@code GoalResponse.status}·{@code PlanStep.status}
     * 가 대표 예시다). {@code HomeSummaryResponse.ActiveGoalDto#status} 는 #91 소유 파일 안에
     * 있지만 같은 {@code Goal.status} 값을 그대로 노출하는 필드라 같은 이유로 미룬다.
     */
    private static final Set<String> GOAL_PLAN_STATUS_VOCAB_PENDING = Set.of(
            "com.divurve.api.dto.goal.GoalResponse#status",
            "com.divurve.api.dto.plan.PlanResponse$Step#status",
            "com.divurve.api.dto.plan.PlanResponse$Summary#status",
            "com.divurve.api.dto.plan.PlanResponse$Summary#budgetState",
            "com.divurve.api.dto.plan.ScenarioPreviewResponse#budgetState",
            "com.divurve.api.dto.plan.PlanVersionListResponse$Version#status",
            "com.divurve.api.dto.plan.StepCompleteResponse#status",
            "com.divurve.api.dto.admin.AdminUserDataResponse$AdminPlanStep#status",
            "com.divurve.api.dto.admin.AdminUserDataResponse$AdminPlan#status",
            "com.divurve.api.dto.admin.AdminUserDataResponse$AdminPlan#budgetState",
            "com.divurve.api.dto.admin.AdminUserDataResponse$AdminGoal#status",
            "com.divurve.api.dto.home.HomeSummaryResponse$ActiveGoalDto#status");

    /**
     * 이슈 #91 이 손댈 수 있는 파일은 {@code HomeSummaryResponse}·{@code FactorsResponse} 뿐이다
     * (작업 지시사항 "소유 파일" 절). 아래는 이 회귀 테스트를 도입하며 스캔한 결과 같은 규칙을
     * 위반하지만 소유 파일 밖이라 고치지 않고 그대로 둔 필드다 — 통화·은행·통화쌍 코드처럼 마스터데이터를
     * 참조하는 동적 식별자이거나(예: {@code currencyCode}·{@code pairCode}·{@code bankCode}),
     * goal/plan 과 무관한 다른 도메인의 상태 코드(예: 위험성향 진단 상태를 그대로 노출하는 admin/route
     * 미러 필드)다. 열거 가능 여부와 무관하게 이 이슈 범위에서는 고치지 않으며, 각 파일의 담당 도메인
     * 에서 후속 이슈로 문서화해야 한다.
     */
    private static final Set<String> OUT_OF_SCOPE_UNOWNED = Set.of(
            // 통화쌍 코드 (base+quote ISO 4217 조합, 마스터데이터 참조 — admin/master/forecast/route)
            "com.divurve.api.dto.admin.AdminFxRateSeriesResponse#pairCode",
            "com.divurve.api.dto.admin.AdminFxRateCoverageResponse$PairCoverage#pairCode",
            "com.divurve.api.dto.admin.AdminCurrencyResponse$AdminCurrencyPair#pairCode",
            "com.divurve.api.dto.admin.AdminFxRateBackfillResponse$PairBackfill#pairCode",
            "com.divurve.api.dto.admin.AdminRefreshResponse$PairResult#pairCode",
            "com.divurve.api.dto.route.RouteContextResponse$Forecast#pairCode",
            "com.divurve.api.dto.forecast.ModelPerformanceResponse#pairCode",
            // 통화 코드 (ISO 4217, 마스터데이터 참조 — admin/asset/goal/plan/master/forecast)
            "com.divurve.api.dto.goal.GoalCreateRequest#currencyCode",
            "com.divurve.api.dto.goal.GoalCreateRequest#budgetCurrencyCode",
            "com.divurve.api.dto.goal.GoalResponse#currencyCode",
            "com.divurve.api.dto.goal.GoalResponse#budgetCurrencyCode",
            "com.divurve.api.dto.admin.AdminUserDataResponse$AdminHolding#currencyCode",
            "com.divurve.api.dto.admin.AdminUserDataResponse$AdminDeposit#currencyCode",
            "com.divurve.api.dto.admin.AdminUserDataResponse$AdminGoal#currencyCode",
            "com.divurve.api.dto.admin.AdminUserDataResponse$AdminGoal#budgetCurrencyCode",
            "com.divurve.api.dto.admin.AdminCurrencyResponse$AdminCurrencyPair#baseCurrencyCode",
            "com.divurve.api.dto.admin.AdminCurrencyResponse$AdminCurrencyPair#quoteCurrencyCode",
            "com.divurve.api.dto.admin.AdminCurrencyResponse$AdminCurrency#currencyCode",
            "com.divurve.api.dto.plan.PlanResponse$CalculationMeta#currencyCode",
            "com.divurve.api.dto.plan.PlanResponse$GoalSummary#currencyCode",
            "com.divurve.api.dto.plan.PlanRequest#currencyCode",
            "com.divurve.api.dto.forecast.EventsResponse$Event#currencyCode",
            "com.divurve.api.dto.asset.DepositResponse#currencyCode",
            "com.divurve.api.dto.asset.DepositCreateRequest#currencyCode",
            "com.divurve.api.dto.asset.HoldingCreateRequest#currencyCode",
            "com.divurve.api.dto.asset.HoldingResponse#currencyCode",
            "com.divurve.api.dto.master.FxTermsResponse$Term#currencyCode",
            "com.divurve.api.dto.master.CurrencyListResponse$Currency#currencyCode",
            // 은행 코드 (마스터데이터 참조 — admin/master/me)
            "com.divurve.api.dto.master.FxTermsResponse#bankCode",
            "com.divurve.api.dto.admin.AdminUserDataResponse$AdminUserSettings#defaultBankCode",
            // 시나리오·변경사유 코드 (stress/plan 도메인 — 값 목록 별도 확정 필요)
            "com.divurve.api.dto.stress.StressRunResponse$Scenario#scenarioCode",
            "com.divurve.api.dto.admin.AdminUserDataResponse$AdminStressTestRun#scenarioCode",
            "com.divurve.api.dto.plan.ScenarioPreviewResponse#changeReasonCode",
            // goal/plan 과 무관한 상태 코드 — 위험성향 진단 상태를 그대로 노출하는 미러 필드
            "com.divurve.api.dto.route.RouteContextResponse$Diagnosis#status",
            "com.divurve.api.dto.admin.AdminUserDataResponse$AdminRiskProfile#status");

    /** 대상으로 삼는 필드명 접미사 — CLAUDE.md 는 명시하지 않지만 이 레포에서 실제로 쓰는 4종. */
    private static final Set<String> TARGET_SUFFIXES = Set.of("status", "code", "state", "badge");

    @Test
    void 상태_코드류_필드는_allowableValues나_값_규칙_설명이_있다() {
        JavaClasses classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.divurve.api.dto");

        List<String> violations = new ArrayList<>();
        for (JavaClass javaClass : classes) {
            collectViolations(javaClass.reflect(), violations);
        }

        assertThat(violations)
                .as("""
                        아래 DTO 필드는 이름이 Status/Code/State/Badge 로 끝나는데 @Schema 에 \
                        allowableValues 도, 값 규칙을 설명하는 description 도 없다. 고정 값 집합이면 \
                        allowableValues 를, 동적 조합·마스터데이터 참조라 열거가 안 되면 description 에 \
                        규칙을 적어라(이슈 #91). 기존 위반을 미루려면 조용히 넘기지 말고 \
                        GOAL_PLAN_STATUS_VOCAB_PENDING/OUT_OF_SCOPE_UNOWNED 에 사유와 함께 올려라.""")
                .isEmpty();
    }

    private void collectViolations(Class<?> reflected, List<String> violations) {
        if (!reflected.isRecord()) {
            return;
        }
        for (RecordComponent component : reflected.getRecordComponents()) {
            if (component.getType() != String.class || !isTargetName(component.getName())) {
                continue;
            }
            String id = reflected.getName() + "#" + component.getName();
            if (isGrandfathered(id) || isDocumented(component)) {
                continue;
            }
            violations.add(id);
        }
    }

    private boolean isTargetName(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return TARGET_SUFFIXES.stream().anyMatch(lower::endsWith);
    }

    private boolean isGrandfathered(String id) {
        return GOAL_PLAN_STATUS_VOCAB_PENDING.contains(id) || OUT_OF_SCOPE_UNOWNED.contains(id);
    }

    private boolean isDocumented(RecordComponent component) {
        Schema schema = component.getAccessor().getAnnotation(Schema.class);
        if (schema == null) {
            return false;
        }
        return schema.allowableValues().length > 0 || !schema.description().isBlank();
    }
}
