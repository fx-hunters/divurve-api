package com.divurve.domain.ai;

import com.divurve.common.architecture.UseCase;
import com.divurve.domain.port.AiProvider;
import com.divurve.domain.settings.SettingsView;
import com.divurve.domain.settings.UserSettingsService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.transaction.annotation.Transactional;

/**
 * AI 서술 유스케이스 (API 명세 v2 §5.12, {@code docs/05-ai-usage-v2.md} §5 출력 규약, 이슈 #54(7.5)).
 *
 * <p>흐름: 사용자 설명 선호 조회 → AI 서술 요청(그라운딩은 {@code facts} 뿐) → 수치 대조 → 표현 필터
 * → 통과하면 그대로, 실패하면 <b>고정 템플릿 폴백</b>(200 유지).
 *
 * <p><b>v1 대비 바뀐 것</b> (리뷰 B 대응):
 * <ul>
 *   <li>H1 — 검증 실패 시 400 대신 <b>200 + {@code fallback:true} + 고정 템플릿</b>을 반환한다.
 *       AI 실패는 서비스 실패가 아니다(FR-AI-06, NFR-AI-03).</li>
 *   <li>H3 — {@link AiResponseValidator} 가 "서술의 숫자가 {@code facts} 에 없으면 실패"로 방향을 뒤집었다.</li>
 *   <li>H4 — {@code surface/facts} → {@code sentences/fallback} v2 스키마로 전면 교체했다.</li>
 *   <li>H6 — {@code parseGoal} 을 삭제했다(v2 W).</li>
 *   <li>M2 — {@code explain_level}·{@code explain_domain} 을 클라이언트 자유 입력이 아니라
 *       {@link UserSettingsService} 에서 읽는다(FR-CM-08) — 표현에만 쓰고 계산에는 넣지 않는다.</li>
 * </ul>
 *
 * <p><b>실 LLM 대응</b>(이슈 #73). Mock 만 있을 때는 나타나지 않던 세 가지를 여기서 처리한다.
 * <ul>
 *   <li><b>예외 격리</b> — 타임아웃·429·5xx·응답 형식 위반이 그대로 올라가면 500 이 나가 FR-AI-06 을
 *       어긴다. 어댑터가 던지는 모든 런타임 예외를 잡아 <b>즉시 폴백</b>한다.</li>
 *   <li><b>재시도 정책 분리</b> — 수치 대조 실패는 재생성할 값이 있지만, 금지 표현은 §5 4단계가
 *       "차단"이라고 규정한 것이지 "재생성"이 아니다. 금지 표현과 API 예외는 재시도 없이 폴백한다.
 *       이 구분이 없으면 실 API 에서 요금과 지연만 2배가 된다.</li>
 *   <li><b>총예산</b> — 기본 {@value #DEFAULT_TOTAL_BUDGET}. 남은 예산이 없으면 재시도를 생략한다.
 *       요청당 타임아웃(기본 5초)만으로는 재시도까지 합쳐 10초가 될 수 있어 NFR-AI-03 을 지키지 못한다.
 *       값은 {@code app.external.anthropic.total-budget} 으로 조정한다(이슈 #123 (2)) — 예전에는
 *       그 프로퍼티를 아무도 읽지 않고 여기 상수가 따로 판정해, 요청당 타임아웃만 올리면 재시도가
 *       조용히 사라지는 상태였다.</li>
 * </ul>
 *
 * <p><b>감사 기록(ERD §10 {@code audit_logs}, action='ai_explained')은 여전히 범위 밖이다</b>(이슈 #56).
 * 마스킹 범위가 정해질 때까지 어댑터가 <b>페이로드 없이 호출 메타만</b> 로그로 남긴다.
 */
@UseCase
public class AiService {

    /** {@code forecast_summary} 는 항상 4문장이다 (FR-FC-07, FR-AI-04). */
    public static final String SURFACE_FORECAST_SUMMARY = "forecast_summary";

    /**
     * 생성·검증 재시도 상한. 문서 §8 이 "재생성 횟수 상한"을 미결정으로 남겨 뒀다 — 결정론적
     * Mock 에서는 재시도가 결과를 바꾸지 못하므로(리뷰 B M6), 실 LLM 확률성을 고려해 최소값 2 로 둔다.
     * 확정되면 이 상수만 바꾸면 된다.
     */
    static final int MAX_ATTEMPTS = 2;

    /**
     * 서술 1건에 쓸 수 있는 총시간의 기본값 (이슈 #73 확정). 요청당 타임아웃은 인프라 설정
     * ({@code app.external.anthropic.request-timeout}, 기본 5초)이고, 이 값은 <b>재시도까지 포함한</b>
     * 상한이다. 남은 예산이 없으면 두 번째 호출을 하지 않고 바로 폴백한다.
     *
     * <p>운영에서는 {@code app.external.anthropic.total-budget}({@code ANTHROPIC_TOTAL_BUDGET})
     * 으로 바꾼다 — 요청당 타임아웃을 올리면서 이 값을 그대로 두면 두 번째 시도가 영영 오지 않는다.
     */
    static final String DEFAULT_TOTAL_BUDGET = "8s";

    private static final Logger log = LoggerFactory.getLogger(AiService.class);

    /**
     * 검증 실패 시의 고정 템플릿(§5 5단계). 화면과 계산 카드는 그대로 유지되고, 이 문장만
     * 대체된다 — 폴백 문장 자체는 수치를 담지 않으므로 항상 수치 대조를 통과한다.
     */
    public static final List<String> FALLBACK_SENTENCES = List.of(
            "지금은 AI 설명을 만들 수 없어 화면의 계산 결과만 안내합니다.",
            "표시된 점수·금액·범위·구간은 계산 엔진이 그대로 산출한 값이며 영향을 받지 않습니다.",
            "AI 서술 생성이 일시적으로 지연되었을 뿐이며 다른 화면 이용에는 제한이 없습니다.",
            "잠시 후 다시 시도하면 설명이 정상적으로 표시될 수 있습니다.");

    private final AiProvider aiProvider;
    private final AiResponseValidator numericValidator;
    private final NarrativeFilter narrativeFilter;
    private final UserSettingsService userSettingsService;
    private final RegimeDisclosureCheck regimeDisclosureCheck;
    private final Clock clock;
    private final Duration totalBudget;

    public AiService(
            AiProvider aiProvider,
            AiResponseValidator numericValidator,
            NarrativeFilter narrativeFilter,
            UserSettingsService userSettingsService,
            RegimeDisclosureCheck regimeDisclosureCheck,
            Clock clock,
            @Value("${app.external.anthropic.total-budget:" + DEFAULT_TOTAL_BUDGET + "}")
            Duration totalBudget) {
        this.aiProvider = Objects.requireNonNull(aiProvider, "aiProvider");
        this.numericValidator = Objects.requireNonNull(numericValidator, "numericValidator");
        this.narrativeFilter = Objects.requireNonNull(narrativeFilter, "narrativeFilter");
        this.userSettingsService = Objects.requireNonNull(userSettingsService, "userSettingsService");
        this.regimeDisclosureCheck = Objects.requireNonNull(regimeDisclosureCheck, "regimeDisclosureCheck");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.totalBudget = Objects.requireNonNull(totalBudget, "totalBudget");
    }

    /**
     * 엔진 결과를 사용자의 설명 선호에 맞춰 서술한다.
     *
     * @param userId  사용자 ID — {@code explain_level}·{@code explain_domain} 조회에만 쓴다
     * @param surface 서술 대상 화면 (예: {@code forecast_summary})
     * @param facts   엔진이 계산한 검증된 사실. AI 의 유일한 그라운딩 소스다(FR-AI-02)
     * @return 서술 결과 — 검증 실패해도 {@code null} 을 반환하지 않는다(H1)
     */
    @Transactional(readOnly = true)
    public ExplainOutcome explain(UUID userId, String surface, Map<String, Object> facts) {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(surface, "surface");
        Objects.requireNonNull(facts, "facts");

        SettingsView settings = userSettingsService.getSettings(userId);
        String explainLevel = settings.explainLevel();
        String explainDomain = settings.explainDomain();

        Instant deadline = clock.instant().plus(totalBudget);

        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            if (attempt > 0 && !clock.instant().isBefore(deadline)) {
                log.warn("AI 서술 예산({}) 소진 — 재시도 없이 폴백한다. surface={}",
                        totalBudget, surface);
                break;
            }

            List<String> sentences;
            try {
                sentences = aiProvider.explain(
                        new AiProvider.ExplainContext(surface, facts, explainLevel, explainDomain))
                        .sentences();
            } catch (RuntimeException e) {
                // 타임아웃·429·5xx·응답 형식 위반. 재시도해도 같은 이유로 실패할 가능성이 크고,
                // 남은 예산을 쓰는 동안 사용자는 계속 기다린다 — 즉시 폴백한다(FR-AI-06).
                log.warn("AI 서술 호출 실패 — 폴백한다. surface={} attempt={} cause={}",
                        surface, attempt, e.toString());
                break;
            }

            // TODO(#56): 여기서 프롬프트(surface·facts·explainLevel·explainDomain)와 원본 응답을
            //  audit_logs(action='ai_explained') 에 남긴다(ERD v3.0 §10). user_id 는 위 파라미터로 이미 있다.
            //  마스킹 범위가 확정될 때까지는 어댑터가 페이로드 없이 호출 메타만 로그로 남긴다(이슈 #73).

            List<String> blockedPhrases = narrativeFilter.detect(String.join(" ", sentences));
            if (!blockedPhrases.isEmpty()) {
                // §5 4단계는 "차단"이지 "재생성"이 아니다 — 다시 물어도 같은 어조가 나올 뿐이다.
                log.warn("AI 서술에서 금지 표현 발견 — 재시도 없이 폴백한다. surface={} count={}",
                        surface, blockedPhrases.size());
                break;
            }

            boolean numericMatch = numericValidator.verify(sentences, facts);
            boolean regimeDisclosed = regimeDisclosureCheck.verify(sentences, facts);
            if (numericMatch && regimeDisclosed) {
                return new ExplainOutcome(sentences, explainLevel, explainDomain, false, true, List.of());
            }
            // 수치 날조(§5 3단계) 또는 급변 구간 안내 누락(§5.1) — 재생성할 여지가 있으므로 재시도한다.
        }

        // 폐기하고 고정 템플릿으로 폴백한다. 200 을 유지한다(FR-AI-06, NFR-AI-03).
        return new ExplainOutcome(FALLBACK_SENTENCES, explainLevel, explainDomain, true, true, List.of());
    }

    /**
     * 서술 결과 (명세 §5.12 {@code explanation} + {@code verification} 의 원본).
     *
     * @param sentences      서술 문장 목록. {@code fallback} 이면 고정 템플릿
     * @param explainLevel   반영된 설명 선호
     * @param explainDomain  반영된 익숙한 설명 분야
     * @param fallback       검증 실패로 고정 템플릿을 냈는지
     * @param numericMatch   최종 반환된 문장의 수치가 {@code facts} 와 일치하는지
     * @param blockedPhrases 최종 반환된 문장에서 발견된 금지 표현(폴백이면 항상 빈 목록)
     */
    public record ExplainOutcome(
            List<String> sentences,
            String explainLevel,
            String explainDomain,
            boolean fallback,
            boolean numericMatch,
            List<String> blockedPhrases) {
    }
}
