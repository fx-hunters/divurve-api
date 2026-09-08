package com.divurve.domain.ai;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 서술을 허용하는 화면과 그 화면의 규약 (이슈 #135, 이슈 #153).
 *
 * <p><b>왜 enum 인가.</b> 이전에는 같은 사실이 세 곳에 흩어져 있었다 — 허용 목록은
 * {@code ExplainRequestGuard.ALLOWED_SURFACES} 의 {@code Set.of(...)}, 문장 수는
 * {@code ClaudeExplainPrompt.FORECAST_SENTENCE_COUNT} 상수 하나, 화면 이름은
 * {@code AiService.SURFACE_*} 문자열이었다. 그래서 <b>화면을 하나 늘리려면 세 곳을 함께 고쳐야
 * 했고, 실제로 이슈 #153 은 그중 한 곳만 고쳐진 결과다</b> — 프론트가 이미 배포된 화면에서
 * {@code xray_exposure} 를 보내는데 허용 목록에만 없어서, 내부 계약 메시지가 사용자 화면에
 * 그대로 노출됐다. 여기 값을 하나 추가하면 허용·문장 수·프롬프트가 함께 따라온다.
 *
 * <p><b>문장 수를 화면마다 고정하는 이유.</b> 사후 검증이 "몇 문장인가"를 물을 수 있어야 한다.
 * 범위({@code 2~4문장})로 두면 프롬프트 지시가 느슨해지고, 모델이 범위 안에서 흔들리는 것을
 * 규약 위반으로 잡아낼 수 없다. {@code forecast_summary} 의 4문장만 요구사항이 정한 값이고
 * (FR-FC-07·FR-AI-04, {@code docs/05-ai-usage-v2.md} §3.2), 나머지는 카드 한 장에 들어가는
 * 분량으로 3문장을 택했다 — 이 값들은 요구사항이 아니라 이 파일이 정하는 규약이므로, 바꾸려면
 * 여기만 고치면 된다.
 *
 * <p><b>{@code focus} 는 프롬프트에 실린다.</b> 같은 {@code facts} 라도 어느 화면의 것인지에 따라
 * 무엇을 앞세워 말할지가 다르다. 이것을 주지 않으면 모델은 화면 맥락 없이 키-값을 나열하게 되고,
 * 그것이 정확히 이슈 #135 에서 사용자가 본 문장이다 — "interval 80 lo는(은) 1313.2211410234067입니다."
 */
public enum ExplainSurface {

    /** 환율 전망 — 4문장 고정 (FR-FC-07·FR-AI-04, 문서 §3.2). */
    FORECAST_SUMMARY(
            "forecast_summary",
            4,
            "환율 전망 화면. 현재 환율과 참고 구간, 변동성, 보유 자산에 미치는 영향을 설명한다."),

    /** 홈 시장 요약 카드. */
    HOME_MARKET_SUMMARY(
            "home_market_summary",
            3,
            "홈 화면의 시장 요약 카드. 지금 환율이 어떤 상태인지 한눈에 읽히게 설명한다."),

    /** 홈 — 내 외화 현황(통화별 노출·민감도). */
    HOME_FX_STATUS(
            "home_fx_status",
            3,
            "홈 화면의 내 외화 현황 카드. 어떤 통화를 얼마나 들고 있고 환율 변동에 얼마나 "
                    + "민감한지 설명한다."),

    /** 홈 — 내 목표. */
    HOME_GOALS(
            "home_goals",
            3,
            "홈 화면의 내 목표 카드. 목표까지 얼마나 왔고 무엇이 남았는지 설명한다."),

    /** 홈 — 경제 일정. */
    HOME_CALENDAR(
            "home_calendar",
            3,
            "홈 화면의 경제 일정 카드. 다가오는 일정이 무엇이고 왜 눈여겨볼 만한지 설명한다."),

    /** X-Ray — 통화 노출 분해. */
    XRAY_EXPOSURE(
            "xray_exposure",
            3,
            "X-Ray 화면의 통화 노출 분해. 자산이 어느 통화에 얼마나 쏠려 있는지 설명한다."),

    /** X-Ray — 목표 적합도(Fit). */
    XRAY_FITNESS(
            "xray_fitness",
            3,
            "X-Ray 화면의 목표 적합도. 지금 보유 구성이 목표와 얼마나 맞는지 설명한다.");

    private final String code;
    private final int sentenceCount;
    private final String focus;

    ExplainSurface(String code, int sentenceCount, String focus) {
        this.code = code;
        this.sentenceCount = sentenceCount;
        this.focus = focus;
    }

    /** API 가 주고받는 {@code surface} 값. */
    public String code() {
        return code;
    }

    /** 이 화면이 내야 하는 문장 수. 더도 덜도 규약 위반이다. */
    public int sentenceCount() {
        return sentenceCount;
    }

    /** 프롬프트에 싣는 화면 맥락 — 무엇을 앞세워 말할 화면인지. */
    public String focus() {
        return focus;
    }

    /**
     * {@code surface} 값에 대응하는 화면.
     *
     * @param code API 로 들어온 {@code surface}. {@code null} 이면 빈 값
     * @return 해당 화면, 없으면 {@code Optional.empty()}
     */
    public static Optional<ExplainSurface> of(String code) {
        return Arrays.stream(values()).filter(s -> s.code.equals(code)).findFirst();
    }

    /**
     * 허용하는 {@code surface} 값 전체. 거절 메시지에 그대로 실리므로 <b>선언 순서</b>를 지킨다 —
     * 알파벳순으로 늘어놓으면 홈·X-Ray 화면이 뒤섞여 무엇이 어느 화면인지 읽히지 않는다.
     */
    public static Set<String> codes() {
        return Arrays.stream(values())
                .map(ExplainSurface::code)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }
}
