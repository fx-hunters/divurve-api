package com.divurve.domain.ai;

import com.divurve.common.architecture.UseCase;
import com.divurve.domain.ai.entity.AiCallLog;
import com.divurve.domain.port.AiProvider;
import com.divurve.domain.port.ExplainResultCache;
import com.divurve.domain.port.TokenUsage;
import com.divurve.domain.settings.SettingsView;
import com.divurve.domain.settings.UserSettingsService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
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
 * <p><b>비용 방어</b>(이슈 #139). 실 API 를 켜는 순간 비용은 입력 크기 × 호출 횟수다. 두 축을
 * 각각 막는다 — {@link ExplainRequestGuard} 가 {@code surface} 어휘와 {@code facts} 크기를
 * <b>호출 전에</b> 잘라내고(초과는 400), {@link ExplainResultCache} 가 같은 입력의 재요청을
 * 흡수한다. 특히 데모 계정은 {@code DemoSampleData} 템플릿 하나를 복제하므로 {@code facts} 가
 * 문자 그대로 같아, 데모 트래픽 대부분이 캐시 한 건에 모인다.
 *
 * <p>그 위에 <b>총량 상한</b>을 얹는다(이슈 #140) — {@link AiCallQuota} 가 사용자·IP·전역 세 층을
 * 보고, 넘으면 프로바이더를 아예 부르지 않고 폴백한다. 쿼터 검사는 <b>캐시 조회 뒤</b>다: 캐시
 * 히트는 비용이 0 이므로 쿼터를 소모해서도, 쿼터에 막혀서도 안 된다.
 *
 * <p><b>감사 기록(ERD §10 {@code audit_logs}, action='ai_explained')은 여전히 범위 밖이다</b>(이슈 #56).
 * 마스킹 범위가 정해질 때까지 어댑터가 <b>페이로드 없이 호출 메타만</b> 로그로 남긴다.
 */
@UseCase
public class AiService {

    /**
     * 캐시 키를 만들 때 재료 사이에 끼우는 구분자 — 재료 값에는 나타날 수 없는 문자를 쓴다. */
    private static final char KEY_SEPARATOR = '\u0000';

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
    private final ExplainRequestGuard explainRequestGuard;
    private final AiCallQuota aiCallQuota;
    private final ExplainResultCache explainResultCache;
    private final AiCallLogRecorder aiCallLogRecorder;
    private final AiResponseValidator numericValidator;
    private final NarrativeFilter narrativeFilter;
    private final UserSettingsService userSettingsService;
    private final RegimeDisclosureCheck regimeDisclosureCheck;
    private final Clock clock;
    private final Duration totalBudget;

    public AiService(
            AiProvider aiProvider,
            ExplainRequestGuard explainRequestGuard,
            AiCallQuota aiCallQuota,
            ExplainResultCache explainResultCache,
            AiCallLogRecorder aiCallLogRecorder,
            AiResponseValidator numericValidator,
            NarrativeFilter narrativeFilter,
            UserSettingsService userSettingsService,
            RegimeDisclosureCheck regimeDisclosureCheck,
            Clock clock,
            @Value("${app.external.anthropic.total-budget:" + DEFAULT_TOTAL_BUDGET + "}")
            Duration totalBudget) {
        this.aiProvider = Objects.requireNonNull(aiProvider, "aiProvider");
        this.explainRequestGuard =
                Objects.requireNonNull(explainRequestGuard, "explainRequestGuard");
        this.aiCallQuota = Objects.requireNonNull(aiCallQuota, "aiCallQuota");
        this.explainResultCache = Objects.requireNonNull(explainResultCache, "explainResultCache");
        this.aiCallLogRecorder = Objects.requireNonNull(aiCallLogRecorder, "aiCallLogRecorder");
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
     * @param userId  사용자 ID — {@code explain_level}·{@code explain_domain} 조회와 호출 기록에 쓴다
     * @param demo    데모 세션의 요청인지 (이슈 #143) — 데모 트래픽 비중이 비용 분석의 핵심 축이다.
     *                토큰의 {@code is_demo} 클레임이 유일한 근거이므로 컨트롤러가 넘겨준다.
     *                쿼터에서는 더 낮은 상한을 고르는 축이 된다(이슈 #140)
     * @param clientIp 요청 출처 IP (이슈 #140) — IP당 쿼터의 근거이자 기록의 한 컬럼.
     *                알 수 없으면 {@code null} 이고 그때 IP 층은 건너뛴다
     * @param surface 서술 대상 화면 (예: {@code forecast_summary})
     * @param facts   엔진이 계산한 검증된 사실. AI 의 유일한 그라운딩 소스다(FR-AI-02)
     * @return 서술 결과 — 검증 실패해도 {@code null} 을 반환하지 않는다(H1)
     * @throws com.divurve.common.exception.InvalidRequestException
     *         {@code surface} 가 허용 화면이 아니거나 {@code facts} 가 상한을 넘었을 때 (400,
     *         이슈 #139). 이 경우는 폴백하지 않는다 — 호출해 볼 가치가 없는 요청이다
     */
    @Transactional(readOnly = true)
    public ExplainOutcome explain(
            UUID userId,
            boolean demo,
            String clientIp,
            String surface,
            Map<String, Object> facts) {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(surface, "surface");
        Objects.requireNonNull(facts, "facts");

        Instant startedAt = clock.instant();

        // 상한·어휘 검사가 가장 먼저다(이슈 #139). 거절할 요청 때문에 설정 조회로 DB 를 치지 않고,
        // 프롬프트에 실릴 크기를 프로바이더 호출 <b>전에</b> 확정한다 — 입력 크기가 곧 청구액이다.
        // 여기서 나가는 InvalidRequestException 은 400 이며 폴백 대상이 아니다: 잘못된 요청은
        // AI 실패가 아니므로 FR-AI-06 과 충돌하지 않는다.
        String canonicalFacts = explainRequestGuard.canonicalize(surface, facts);

        SettingsView settings = userSettingsService.getSettings(userId);
        String explainLevel = settings.explainLevel();
        String explainDomain = settings.explainDomain();

        // 설명 선호까지 키에 넣는다 — 같은 facts 라도 explain_level·explain_domain 이 다르면
        // 문장이 다르다. 이것을 빼면 처음 물어본 사용자의 어투가 모두에게 나간다.
        String cacheKey = cacheKey(surface, explainLevel, explainDomain, canonicalFacts);
        Optional<List<String>> cached = explainResultCache.find(cacheKey);
        if (cached.isPresent()) {
            // 검증값을 true 로 싣는 것은 상수를 채워 넣는 것이 아니다(이슈 #122 와 다른 경우다):
            // 담긴 문장은 저장될 때 두 검사를 통과했고, 두 검사는 (sentences, facts) 만의 함수인데
            // 그 둘을 키가 고정한다 — 지금 다시 돌려도 같은 결과다.
            record(startedAt, userId, demo, clientIp, surface, null, TokenUsage.NONE,
                    AiCallOutcome.CACHE_HIT, null, null);
            return new ExplainOutcome(cached.get(), explainLevel, explainDomain, false,
                    true, true, List.of(), null);
        }

        // 쿼터는 캐시 조회 <b>뒤</b>다(이슈 #140). 순서가 규약이다 — 캐시 히트는 비용이 0 이므로
        // 쿼터를 소모해서도 안 되고, 쿼터에 막혀서도 안 된다. 담아 둔 문장을 그냥 주는 것이
        // 폴백 템플릿을 주는 것보다 언제나 낫다.
        Optional<AiCallQuota.Layer> exceeded = aiCallQuota.exceededLayer(userId, demo, clientIp);
        if (exceeded.isPresent()) {
            FallbackReason reason = reasonOf(exceeded.get());
            // 프로바이더를 부르지 않았으므로 비용은 0 이다. 그래도 행을 남긴다 — 남기지 않으면
            // 관리자 화면에서 호출량이 줄어든 것이 "쿼터가 듣는다" 인지 "트래픽이 없다" 인지
            // 구분되지 않는다. outcome 이 quota_blocked 여서 이 행은 쿼터 카운트에도 빠진다.
            record(startedAt, userId, demo, clientIp, surface, null, TokenUsage.NONE,
                    AiCallOutcome.QUOTA_BLOCKED, reason.code(), null);
            return new ExplainOutcome(FALLBACK_SENTENCES, explainLevel, explainDomain, true,
                    null, null, List.of(), reason);
        }

        Instant deadline = startedAt.plus(totalBudget);

        // 호출 기록용 누적값 (이슈 #143). 재시도가 있으므로 사용량은 합산하고, 모델은 마지막으로
        // 실제 호출한 값을 남긴다 — 재시도 중 설정이 바뀌지 않으므로 둘은 같다.
        TokenUsage totalUsage = TokenUsage.NONE;
        String model = null;

        // 검증 단계까지 도달한 마지막 시도의 측정값. 한 번도 도달하지 못했으면 null 로 남겨
        // "측정되지 않았다" 를 그대로 응답에 싣는다(이슈 #122) — 폴백에 true 를 채워 넣지 않는다.
        Boolean numericMatch = null;
        Boolean regimeDisclosed = null;
        List<String> blockedPhrases = List.of();
        FallbackReason fallbackReason = null;
        RuntimeException providerError = null;

        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            if (attempt > 0 && !clock.instant().isBefore(deadline)) {
                log.warn("AI 서술 예산({}) 소진 — 재시도 없이 폴백한다. surface={}",
                        totalBudget, surface);
                fallbackReason = FallbackReason.BUDGET_EXHAUSTED;
                break;
            }

            List<String> sentences;
            try {
                AiProvider.ExplainResult result = aiProvider.explain(
                        new AiProvider.ExplainContext(surface, facts, explainLevel, explainDomain));
                sentences = result.sentences();
                totalUsage = totalUsage.plus(result.usage());
                if (result.model() != null) {
                    model = result.model();
                }
            } catch (RuntimeException e) {
                // 타임아웃·429·5xx·응답 형식 위반. 재시도해도 같은 이유로 실패할 가능성이 크고,
                // 남은 예산을 쓰는 동안 사용자는 계속 기다린다 — 즉시 폴백한다(FR-AI-06).
                //
                // 예외를 마지막 인자로 넘겨 <b>cause 체인 전체</b>를 남긴다(이슈 #122). e.toString()
                // 만 찍으면 "AnthropicIoException: Request failed" 에서 끊겨 타임아웃인지 DNS
                // 실패인지 구분할 수 없다 — 대응이 완전히 다른데도.
                log.warn("AI 서술 호출 실패 — 폴백한다. surface={} attempt={}", surface, attempt, e);
                fallbackReason = FallbackReason.PROVIDER_ERROR;
                providerError = e;
                break;
            }

            // TODO(#56): 여기서 프롬프트(surface·facts·explainLevel·explainDomain)와 원본 응답을
            //  audit_logs(action='ai_explained') 에 남긴다(ERD v3.0 §10). user_id 는 위 파라미터로 이미 있다.
            //  마스킹 범위가 확정될 때까지는 어댑터가 페이로드 없이 호출 메타만 로그로 남긴다(이슈 #73).

            List<String> detected = narrativeFilter.detect(String.join(" ", sentences));
            if (!detected.isEmpty()) {
                // §5 4단계는 "차단"이지 "재생성"이 아니다 — 다시 물어도 같은 어조가 나올 뿐이다.
                log.warn("AI 서술에서 금지 표현 발견 — 재시도 없이 폴백한다. surface={} count={}",
                        surface, detected.size());
                // 검출된 표현을 그대로 응답에 싣는다(이슈 #122). 빈 목록으로 내보내면 무엇에
                // 걸렸는지가 사라져, 차단한 표현을 응답이 오히려 숨기게 된다.
                blockedPhrases = detected;
                fallbackReason = FallbackReason.BLOCKED_PHRASES;
                break;
            }

            numericMatch = numericValidator.verify(sentences, facts);
            regimeDisclosed = regimeDisclosureCheck.verify(sentences, facts);
            if (numericMatch && regimeDisclosed) {
                // 통과한 것만 담는다 — 폴백을 담으면 일시적 provider 장애가 TTL 동안 고정된다.
                explainResultCache.put(cacheKey, sentences);
                record(startedAt, userId, demo, clientIp, surface, model, totalUsage,
                        AiCallOutcome.SUCCESS, null, null);
                return new ExplainOutcome(
                        sentences, explainLevel, explainDomain, false, true, true, List.of(), null);
            }
            // 수치 날조(§5 3단계) 또는 급변 구간 안내 누락(§5.1) — 재생성할 여지가 있으므로 재시도한다.
            // 이 경로는 이슈 #122 이전까지 로그도 응답 단서도 없어 유일하게 완전히 무음이었다.
            log.warn("AI 서술 검증 실패 — 재시도한다. surface={} attempt={} numericMatch={} "
                            + "regimeDisclosed={}", surface, attempt, numericMatch, regimeDisclosed);
            fallbackReason = FallbackReason.VERIFICATION_FAILED;
        }

        // 폐기하고 고정 템플릿으로 폴백한다. 200 을 유지한다(FR-AI-06, NFR-AI-03).
        //
        // 폴백도 기록한다(이슈 #143) — 토큰은 이미 소모됐을 수 있고(검증 실패 경로), 실패가 어느
        // 사유로 몇 번 일어났는지가 비용 판단의 절반이다. 남기지 않으면 관리자 화면에서 호출량이
        // 줄어든 것이 "캐시가 잘 듣는다" 인지 "장애로 폴백 중" 인지 구분되지 않는다.
        // fallbackReason 은 여기서 항상 non-null 이다 — 이 지점에 이르는 네 경로(예산 소진 ·
        // provider 예외 · 금지 표현 · 검증 실패)가 모두 사유를 설정하고, MAX_ATTEMPTS 가 1 이상이라
        // 루프 본문은 최소 한 번 돈다. 그래서 null 검사를 두지 않는다.
        record(startedAt, userId, demo, clientIp, surface, model, totalUsage,
                AiCallOutcome.FALLBACK, fallbackReason.code(),
                AiCallLogRecorder.summarize(providerError));

        return new ExplainOutcome(FALLBACK_SENTENCES, explainLevel, explainDomain, true,
                numericMatch, regimeDisclosed, blockedPhrases, fallbackReason);
    }

    /**
     * 호출 한 건을 {@code ai_call_logs} 에 남긴다 (이슈 #143).
     *
     * <p><b>여기가 기록 지점인 이유</b> — 어댑터가 아니라 이 유스케이스다. 폴백은 어댑터를 아예
     * 타지 않으므로 어댑터에 기록을 두면 정확히 그 경로가 사라진다. 그리고 ArchUnit 이
     * {@code @ExternalAdapter} → {@code @PersistenceAdapter} 를 막는다(CLAUDE.md 4장).
     *
     * <p>{@code model} 이 {@code null} 이면 <b>이 요청에서</b> LLM 을 부르지 않았다는 뜻이다 —
     * 규약이 확정된 화면({@code forecast_summary}) 밖은 템플릿으로 응답하고,
     * {@code ANTHROPIC_ENABLED} 가 꺼진 기본 설정에서는 모든 서술이 그렇고,
     * <b>캐시 히트도 그렇다</b>(이슈 #139 — 담아 둔 문장을 만든 모델을 적으면 관리자 화면에서
     * 토큰 0 짜리 호출로 보여 모델별 호출량이 부풀려진다. 히트임은 {@code outcome} 이 말한다).
     * 그 행도 남긴다: 요청은 있었고 비용은 0 이었다는 사실이며, 이슈 #140 의 사용자별 쿼터는
     * LLM 호출 수가 아니라 <b>요청 수</b>를 세야 한다.
     */
    private void record(
            Instant startedAt,
            UUID userId,
            boolean demo,
            String clientIp,
            String surface,
            String model,
            TokenUsage usage,
            AiCallOutcome outcome,
            String fallbackReason,
            String errorSummary) {
        int latencyMs = (int) Duration.between(startedAt, clock.instant()).toMillis();
        aiCallLogRecorder.record(AiCallLog.narrate(
                startedAt,
                userId,
                demo,
                clientIp,
                surface,
                model,
                usage,
                outcome,
                fallbackReason,
                latencyMs,
                errorSummary));
    }

    /**
     * 쿼터 층을 폴백 사유로 옮긴다 (이슈 #140).
     *
     * <p>두 어휘를 하나로 두지 않고 여기서 옮기는 이유는 의존 방향이다 — {@link AiCallQuota} 가
     * {@code FallbackReason} 을 직접 돌려주면 이 클래스와 서로를 참조한다. 코드 문자열이 어긋나지
     * 않는 것은 {@code AiCallVocabularyTest} 가 지킨다.
     */
    private static FallbackReason reasonOf(AiCallQuota.Layer layer) {
        return switch (layer) {
            case USER -> FallbackReason.QUOTA_USER;
            case IP -> FallbackReason.QUOTA_IP;
            case GLOBAL -> FallbackReason.QUOTA_GLOBAL;
        };
    }

    /**
     * 요청을 캐시 키 하나로 접는다 (이슈 #139).
     *
     * <p><b>해시로 줄이지 않고 재료를 그대로 잇는다.</b> {@code facts} 는 상한이 4KB 라 500개를
     * 담아도 키가 몇 MB 를 넘지 않고, 대신 <b>해시 충돌이 원리적으로 없다</b> — 충돌은 곧 남의
     * 서술이 내 화면에 나가는 것이므로, 아낄 메모리와 바꿀 위험이 아니다.
     *
     * <p>{@code canonicalFacts} 는 {@link ExplainRequestGuard} 가 <b>키를 정렬해</b> 직렬화한
     * 문자열이어야 한다. {@code Map} 순회 순서에 기대면 내용이 같은데 키가 달라져 캐시가 조용히
     * 빗나가고, 캐시가 없는 것과 같은 상태가 지표에는 "히트율 0%" 로만 보인다.
     */
    private static String cacheKey(
            String surface, String explainLevel, String explainDomain, String canonicalFacts) {
        return surface + KEY_SEPARATOR + explainLevel + KEY_SEPARATOR + explainDomain
                + KEY_SEPARATOR + canonicalFacts;
    }

    /**
     * 폴백에 이른 사유 (이슈 #122).
     *
     * <p>폴백 경로는 넷인데 그동안 <b>응답이 전부 같은 값으로 수렴</b>해 어느 경로였는지 알 수
     * 없었다. 배포된 환경에서 폴백 원인을 찾으려면 서버 로그를 뒤지는 수밖에 없었고, 그중 검증
     * 실패 경로는 로그조차 없었다. 이 값이 그 넷을 응답에서 가른다.
     */
    public enum FallbackReason {

        /** 어댑터 호출이 예외로 끝났다 — 타임아웃·429·5xx·응답 형식 위반. */
        PROVIDER_ERROR,

        /** 금지 표현이 검출됐다. 검출된 표현은 {@code blockedPhrases} 에 그대로 담긴다. */
        BLOCKED_PHRASES,

        /** 총예산({@link AiService#TOTAL_BUDGET})이 소진돼 재시도를 생략했다. */
        BUDGET_EXHAUSTED,

        /** 수치 대조 또는 급변 구간 고지 검사에 걸렸다. 어느 쪽인지는 두 측정값이 말한다. */
        VERIFICATION_FAILED,

        /**
         * 이 사용자가 24시간 상한을 다 썼다 (이슈 #140). 데모 세션은 더 낮은 상한을 쓴다.
         *
         * <p>쿼터 세 값은 앞의 넷과 성질이 다르다 — <b>프로바이더를 아예 부르지 않았다.</b>
         * 그래서 {@code numeric_match}·{@code regime_disclosed} 가 {@code null} 이고 토큰이 0 이다.
         */
        QUOTA_USER,

        /** 이 IP 에서 온 호출이 상한을 넘었다. 위조 가능한 헤더에 기대므로 과속방지턱이다. */
        QUOTA_IP,

        /** 서비스 전체가 24시간 상한을 넘었다 — 킬스위치가 발동해 전건이 폴백한다. */
        QUOTA_GLOBAL;

        /** API 응답에 싣는 snake_case 코드 — DB 컬럼·응답 필드와 같은 표기를 쓴다(§5 네이밍). */
        public String code() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /**
     * 서술 결과 (명세 §5.12 {@code explanation} + {@code verification} 의 원본).
     *
     * <p><b>검증값은 측정한 것만 담는다</b>(이슈 #122). 폴백 응답이 {@code numericMatch=true} 를
     * 상수로 실어 "LLM 출력이 검증을 통과했다" 로 읽히게 하던 것을 없앴다 — 통과한 출력은 애초에
     * 존재하지 않았다. 검증 단계에 도달하지 못한 경로에서는 {@code null} 이다.
     *
     * @param sentences       서술 문장 목록. {@code fallback} 이면 고정 템플릿
     * @param explainLevel    반영된 설명 선호
     * @param explainDomain   반영된 익숙한 설명 분야
     * @param fallback        검증 실패로 고정 템플릿을 냈는지
     * @param numericMatch    마지막으로 검증 단계까지 간 시도의 수치 대조 결과.
     *                        거기까지 가지 못했으면(호출 실패 등) {@code null}
     * @param regimeDisclosed 같은 시도의 급변 구간 고지 검사 결과. 위와 같은 규칙으로 {@code null}
     * @param blockedPhrases  검출된 금지 표현. 그 경로가 아니면 빈 목록
     * @param fallbackReason  폴백 사유. {@code fallback} 이면 항상 non-null, 성공이면 {@code null}
     */
    public record ExplainOutcome(
            List<String> sentences,
            String explainLevel,
            String explainDomain,
            boolean fallback,
            Boolean numericMatch,
            Boolean regimeDisclosed,
            List<String> blockedPhrases,
            FallbackReason fallbackReason) {
    }
}
