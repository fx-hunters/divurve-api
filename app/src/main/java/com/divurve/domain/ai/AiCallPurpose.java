package com.divurve.domain.ai;

import java.util.Locale;

/**
 * AI 호출의 용도 (이슈 #143). narrate 와 extract 는 <b>비용 성격이 다른 두 경로</b>라 집계에서
 * 갈라 본다 — narrate 는 사용자 요청마다 동기로 일어나고, extract 는 배치에서 돈다.
 */
public enum AiCallPurpose {

    /** 엔진 결과를 문장으로 옮긴다 ({@code POST /api/v1/ai/explain}). */
    NARRATE,

    /** 비정형 원문에서 경제 이벤트를 구조화한다 (배치·관리자 미리보기). */
    EXTRACT;

    /** DB 컬럼·API 응답에 쓰는 표기. 둘이 같은 값을 쓴다 — 관리자가 SQL 과 화면을 대조한다. */
    public String code() {
        return name().toLowerCase(Locale.ROOT);
    }
}
