package com.divurve.domain.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.divurve.domain.port.AiProvider;
import com.divurve.domain.port.AiProvider.ExplainContext;
import com.divurve.domain.port.AiProvider.ExplainResult;
import com.divurve.domain.settings.SettingsView;
import com.divurve.domain.settings.UserSettingsService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * {@link AiService} 유스케이스 테스트 (API 명세 v2 §5.12).
 * 성공 시 그대로 반환, 검증 실패 시 폴백(H1 대응 — 400 이 아니라 200 + fallback:true), 사용자 설정에서
 * explain_level·explain_domain 을 읽는지(M2 대응)를 검증한다.
 *
 * <p>이슈 #73 에서 더해진 실 LLM 대응 — API 예외 격리, 금지 표현 즉시 폴백(재시도 없음),
 * 총예산 소진 시 재시도 생략, 급변 구간 안내 누락 검사도 함께 본다.
 *
 * <p>이슈 #122 — <b>폴백 경로 넷이 결과에서 서로 구분되는지</b>를 함께 고정한다. 넷이 같은 값으로
 * 수렴하면 배포 환경에서 왜 폴백했는지 알 방법이 없어진다.
 */
@ExtendWith(MockitoExtension.class)
class AiServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-07T00:00:00Z");

    @Mock
    private AiProvider aiProvider;

    @Mock
    private AiResponseValidator validator;

    @Mock
    private NarrativeFilter narrativeFilter;

    @Mock
    private UserSettingsService userSettingsService;

    private final RegimeDisclosureCheck regimeDisclosureCheck = new RegimeDisclosureCheck();
    private final UUID userId = UUID.randomUUID();
    private final Map<String, Object> facts = Map.of("amount", 100000.0);

    private AiService service;

    @BeforeEach
    void setUp() {
        service = newService(Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private AiService newService(Clock clock) {
        return new AiService(aiProvider, validator, narrativeFilter, userSettingsService,
                regimeDisclosureCheck, clock);
    }

    private void stubSettings(String level, String domain) {
        when(userSettingsService.getSettings(userId)).thenReturn(new SettingsView(
                null, 0.0, level, domain, 0.0, 0.0, true, true, true, false, true));
    }

    @Test
    void explain_수치와_표현이_모두_통과하면_그대로_반환한다() {
        stubSettings("standard", "finance");
        List<String> sentences = List.of("자산은 100000입니다.");
        when(aiProvider.explain(any(ExplainContext.class))).thenReturn(new ExplainResult(sentences));
        when(validator.verify(sentences, facts)).thenReturn(true);
        when(narrativeFilter.detect("자산은 100000입니다.")).thenReturn(List.of());

        AiService.ExplainOutcome outcome = service.explain(userId, "profile_fit", facts);

        assertThat(outcome.sentences()).isEqualTo(sentences);
        assertThat(outcome.fallback()).isFalse();
        assertThat(outcome.numericMatch()).isTrue();
        assertThat(outcome.regimeDisclosed()).isTrue();
        assertThat(outcome.blockedPhrases()).isEmpty();
        assertThat(outcome.fallbackReason()).isNull();
        assertThat(outcome.explainLevel()).isEqualTo("standard");
        assertThat(outcome.explainDomain()).isEqualTo("finance");
    }

    @Test
    void explain_수치_불일치가_재시도_후에도_계속되면_폴백을_반환한다() {
        stubSettings("simple", "plain");
        List<String> bad = List.of("자산은 999999입니다.");
        when(aiProvider.explain(any(ExplainContext.class))).thenReturn(new ExplainResult(bad));
        when(validator.verify(bad, facts)).thenReturn(false);
        when(narrativeFilter.detect("자산은 999999입니다.")).thenReturn(List.of());

        AiService.ExplainOutcome outcome = service.explain(userId, "profile_fit", facts);

        assertThat(outcome.fallback()).isTrue();
        assertThat(outcome.sentences()).isEqualTo(AiService.FALLBACK_SENTENCES);
        // 이슈 #122 — 측정한 값을 그대로 보고한다. 예전에는 상수 true 가 실려 "검증을 통과했다" 로
        // 읽혔는데, 통과한 출력은 애초에 존재하지 않았다.
        assertThat(outcome.numericMatch()).isFalse();
        assertThat(outcome.regimeDisclosed()).isTrue();
        assertThat(outcome.blockedPhrases()).isEmpty();
        assertThat(outcome.fallbackReason())
                .isEqualTo(AiService.FallbackReason.VERIFICATION_FAILED);
        verify(aiProvider, times(AiService.MAX_ATTEMPTS)).explain(any(ExplainContext.class));
    }

    @Test
    void explain_금지_표현이_발견되면_재시도하지_않고_폴백한다() {
        stubSettings("simple", "plain");
        List<String> risky = List.of("반드시 매수하세요.");
        when(aiProvider.explain(any(ExplainContext.class))).thenReturn(new ExplainResult(risky));
        when(narrativeFilter.detect("반드시 매수하세요.")).thenReturn(List.of("반드시", "매수하세요"));

        AiService.ExplainOutcome outcome = service.explain(userId, "profile_fit", facts);

        assertThat(outcome.fallback()).isTrue();
        assertThat(outcome.sentences()).isEqualTo(AiService.FALLBACK_SENTENCES);
        // 이슈 #122 — 차단한 표현을 응답이 숨기지 않는다. 빈 목록으로 나가면 무엇에 걸렸는지 사라진다.
        assertThat(outcome.blockedPhrases()).containsExactly("반드시", "매수하세요");
        assertThat(outcome.fallbackReason()).isEqualTo(AiService.FallbackReason.BLOCKED_PHRASES);
        // 검증 단계까지 가지 않았으므로 측정값이 없다 — 채워 넣지 않는다.
        assertThat(outcome.numericMatch()).isNull();
        assertThat(outcome.regimeDisclosed()).isNull();
        // §5 4단계는 "차단"이지 "재생성"이 아니다 — 두 번 부르면 요금과 지연만 2배가 된다.
        verify(aiProvider, times(1)).explain(any(ExplainContext.class));
        verify(validator, never()).verify(anyList(), anyMap());
    }

    @Test
    void explain_provider가_예외를_던지면_재시도하지_않고_폴백한다() {
        stubSettings("simple", "plain");
        when(aiProvider.explain(any(ExplainContext.class)))
                .thenThrow(new IllegalStateException("read timed out"));

        AiService.ExplainOutcome outcome = service.explain(userId, "forecast_summary", facts);

        // FR-AI-06 — AI 실패가 500 으로 나가지 않는다.
        assertThat(outcome.fallback()).isTrue();
        assertThat(outcome.sentences()).isEqualTo(AiService.FALLBACK_SENTENCES);
        assertThat(outcome.fallbackReason()).isEqualTo(AiService.FallbackReason.PROVIDER_ERROR);
        assertThat(outcome.numericMatch()).isNull();
        assertThat(outcome.regimeDisclosed()).isNull();
        assertThat(outcome.blockedPhrases()).isEmpty();
        verify(aiProvider, times(1)).explain(any(ExplainContext.class));
        verify(narrativeFilter, never()).detect(anyString());
    }

    @Test
    void explain_총예산이_소진되면_두_번째_호출을_생략한다() {
        stubSettings("simple", "plain");
        List<String> bad = List.of("자산은 999999입니다.");
        when(aiProvider.explain(any(ExplainContext.class))).thenReturn(new ExplainResult(bad));
        when(validator.verify(bad, facts)).thenReturn(false);
        when(narrativeFilter.detect("자산은 999999입니다.")).thenReturn(List.of());

        // 첫 호출이 예산을 다 쓴 상황 — 시계가 예산 너머로 가 있다.
        AiService budgetSpent = newService(new SteppingClock(NOW, AiService.TOTAL_BUDGET));

        AiService.ExplainOutcome outcome = budgetSpent.explain(userId, "forecast_summary", facts);

        assertThat(outcome.fallback()).isTrue();
        // 검증 실패로 재시도하려던 참에 예산이 끊긴 것이므로, 끝낸 사유는 예산 소진이다.
        assertThat(outcome.fallbackReason()).isEqualTo(AiService.FallbackReason.BUDGET_EXHAUSTED);
        // 첫 시도는 검증까지 갔으므로 그때의 측정값은 남아 있다.
        assertThat(outcome.numericMatch()).isFalse();
        verify(aiProvider, times(1)).explain(any(ExplainContext.class));
    }

    @Test
    void explain_급변_구간에_불확실성_안내가_없으면_폴백한다() {
        stubSettings("simple", "plain");
        Map<String, Object> stressed = Map.of("amount", 100000.0, "regime", "stress");
        List<String> silent = List.of("자산은 100000입니다.");
        when(aiProvider.explain(any(ExplainContext.class))).thenReturn(new ExplainResult(silent));
        when(validator.verify(silent, stressed)).thenReturn(true);
        when(narrativeFilter.detect("자산은 100000입니다.")).thenReturn(List.of());

        AiService.ExplainOutcome outcome = service.explain(userId, "forecast_summary", stressed);

        // §5.1 — 급변 구간에서 안내가 빠지는 것은 하필 가장 필요한 순간에 규약이 깨지는 것이다.
        assertThat(outcome.fallback()).isTrue();
        // 이슈 #122 — 두 측정값이 "어느 검증에서 걸렸는가" 를 그대로 말한다.
        assertThat(outcome.numericMatch()).isTrue();
        assertThat(outcome.regimeDisclosed()).isFalse();
        assertThat(outcome.fallbackReason())
                .isEqualTo(AiService.FallbackReason.VERIFICATION_FAILED);
        verify(aiProvider, times(AiService.MAX_ATTEMPTS)).explain(any(ExplainContext.class));
    }

    @Test
    void explain_급변_구간에_안내가_있으면_그대로_반환한다() {
        stubSettings("simple", "plain");
        Map<String, Object> stressed = Map.of("amount", 100000.0, "regime", "elevated");
        List<String> disclosed = List.of(
                "자산은 100000입니다.",
                "최근 변동성이 커진 구간이라 " + RegimeDisclosureCheck.REQUIRED_DISCLOSURE + ".");
        when(aiProvider.explain(any(ExplainContext.class))).thenReturn(new ExplainResult(disclosed));
        when(validator.verify(disclosed, stressed)).thenReturn(true);
        when(narrativeFilter.detect(String.join(" ", disclosed))).thenReturn(List.of());

        AiService.ExplainOutcome outcome = service.explain(userId, "forecast_summary", stressed);

        assertThat(outcome.fallback()).isFalse();
        assertThat(outcome.sentences()).isEqualTo(disclosed);
    }

    @Test
    void explain_사용자_설정의_explainLevel_explainDomain을_provider에게_전달한다() {
        stubSettings("detailed", "dev");
        List<String> sentences = List.of("변동성 지표는 5년 백분위 72%에 해당합니다.");
        when(aiProvider.explain(any(ExplainContext.class))).thenReturn(new ExplainResult(sentences));
        when(validator.verify(sentences, facts)).thenReturn(true);
        when(narrativeFilter.detect(sentences.get(0))).thenReturn(List.of());

        service.explain(userId, "forecast_summary", facts);

        verify(aiProvider).explain(new ExplainContext("forecast_summary", facts, "detailed", "dev"));
    }

    @Test
    void explain_userId가_null이면_NullPointerException을_던진다() {
        assertThatThrownBy(() -> service.explain(null, "profile_fit", facts))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void explain_surface가_null이면_NullPointerException을_던진다() {
        assertThatThrownBy(() -> service.explain(userId, null, facts))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void explain_facts가_null이면_NullPointerException을_던진다() {
        assertThatThrownBy(() -> service.explain(userId, "profile_fit", null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void 생성자는_협력자가_null_이면_실패한다() {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        assertThatThrownBy(() -> new AiService(null, validator, narrativeFilter, userSettingsService,
                regimeDisclosureCheck, clock)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new AiService(aiProvider, null, narrativeFilter, userSettingsService,
                regimeDisclosureCheck, clock)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new AiService(aiProvider, validator, null, userSettingsService,
                regimeDisclosureCheck, clock)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new AiService(aiProvider, validator, narrativeFilter, null,
                regimeDisclosureCheck, clock)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new AiService(aiProvider, validator, narrativeFilter, userSettingsService,
                null, clock)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new AiService(aiProvider, validator, narrativeFilter, userSettingsService,
                regimeDisclosureCheck, null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void FallbackReason_code는_응답에_싣는_snake_case를_준다() {
        // 네 경로가 응답에서 서로 다른 값으로 구분되는 것이 이슈 #122 의 요구다.
        assertThat(AiService.FallbackReason.PROVIDER_ERROR.code()).isEqualTo("provider_error");
        assertThat(AiService.FallbackReason.BLOCKED_PHRASES.code()).isEqualTo("blocked_phrases");
        assertThat(AiService.FallbackReason.BUDGET_EXHAUSTED.code()).isEqualTo("budget_exhausted");
        assertThat(AiService.FallbackReason.VERIFICATION_FAILED.code())
                .isEqualTo("verification_failed");
    }

    /** 읽을 때마다 시간이 흐르는 시계 — 첫 호출이 예산을 다 쓴 상황을 재현한다. */
    private static final class SteppingClock extends Clock {

        private final Instant start;
        private final Duration step;
        private int reads;

        private SteppingClock(Instant start, Duration step) {
            this.start = start;
            this.step = step;
        }

        @Override
        public Instant instant() {
            return start.plus(step.multipliedBy(reads++));
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }
}
