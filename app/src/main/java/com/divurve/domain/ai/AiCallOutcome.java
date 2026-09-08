package com.divurve.domain.ai;

import java.util.Locale;

/**
 * AI 호출이 어떻게 끝났는지 (이슈 #143).
 *
 * <p><b>실 호출이 없는 경로도 값을 갖는다.</b> 캐시 히트·폴백·쿼터 차단은 토큰이 0 이지만 행을
 * 남긴다 — 남기지 않으면 관리자 화면에서 호출량이 줄어든 것이 "캐시가 잘 듣는다" 인지 "장애로
 * 폴백 중" 인지 구분되지 않고, 반대로 캐시 히트를 실 호출로 집계하면 비용이 부풀려 보인다.
 */
public enum AiCallOutcome {

    /** 실제로 호출했고 검증까지 통과했다. 토큰이 소모된 유일한 경우다. */
    SUCCESS,

    /** 고정 템플릿으로 폴백했다. 사유는 {@code fallback_reason} 이 가른다. */
    FALLBACK,

    /** 캐시에서 응답했다 — provider 를 부르지 않았다(이슈 #139). */
    CACHE_HIT,

    /** 쿼터에 걸려 호출 자체를 하지 않았다(이슈 #140). */
    QUOTA_BLOCKED,

    /**
     * 호출이 실패했고 대체할 산출물이 없다.
     *
     * <p>{@link #FALLBACK} 과 다른 사실이다 — narrate 는 고정 템플릿이 있어 실패해도 사용자에게
     * 문장이 나가지만(FR-AI-06), extract 는 폴백할 것이 없어 그 원문을 건너뛴다. 두 경우를 같은
     * 값으로 적으면 "템플릿으로 대체됐다" 와 "아무것도 얻지 못했다" 가 섞인다.
     */
    ERROR;

    /** DB 컬럼·API 응답에 쓰는 표기. */
    public String code() {
        return name().toLowerCase(Locale.ROOT);
    }
}
