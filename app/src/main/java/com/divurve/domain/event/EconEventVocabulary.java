package com.divurve.domain.event;

import java.util.Locale;
import java.util.Map;

/**
 * {@code econ_events} 저장 어휘를 API 응답 어휘로 옮기는 변환표 (이슈 #162).
 *
 * <p><b>수치를 만들지 않는다.</b> 저장된 값 하나를 표시용 어휘 하나로 바꾸는 전단사 대응일 뿐이라
 * engine 이 아니라 domain 에 둔다(CLAUDE.md §1) — 계산이 아니므로 {@code EngineComponent} 도 아니다.
 *
 * <p>상태가 없어 인스턴스를 만들 이유가 없다. {@link EconEventValidator} 와 달리 빈으로 등록하지
 * 않는 이유가 이것이다 — 검증기는 인스턴스 메서드를 주입받아 쓰지만 이 변환표는 정적 대응이다.
 */
public final class EconEventVocabulary {

    /**
     * {@code region} → {@code currency_code} (이슈 #162 확정).
     *
     * <p>키는 {@link EconEventValidator} 가 허용하는 지역 전체와 일치해야 한다 — 저장을 통과한
     * 지역이 여기 없으면 조회에서 값을 잃는다. {@code GLOBAL} 은 아래 별도 처리한다.
     */
    private static final Map<String, String> REGION_TO_CURRENCY = Map.of(
            "US", "USD",
            "EU", "EUR",
            "JP", "JPY",
            "KR", "KRW",
            "GB", "GBP",
            "CN", "CNY");

    // GLOBAL 은 표에 넣지 않는다 — 특정 통화에 귀속되지 않으므로 조회가 그대로 null 을 돌려준다.
    // 없는 통화를 지어내지 않는다(FR-CM-10). 보유 통화 필터가 붙으면(후속 이슈) null 은 "항상 포함" 이다.

    /** {@code impact}(1~3) → {@code importance}. 3 이 높음이다({@code ClaudeExtractPrompt} 규약). */
    private static final Map<Short, String> IMPACT_TO_IMPORTANCE = Map.of(
            (short) 3, "High",
            (short) 2, "Medium",
            (short) 1, "Low");

    private EconEventVocabulary() {
    }

    /**
     * 지역을 영향 통화로 옮긴다.
     *
     * @param region {@code econ_events.region}
     * @return 통화코드. {@code GLOBAL} 이거나 표에 없는 지역이면 {@code null}
     */
    public static String toCurrencyCode(String region) {
        if (region == null) {
            return null;
        }
        return REGION_TO_CURRENCY.get(region.trim().toUpperCase(Locale.ROOT));
    }

    /**
     * 영향도를 표시 어휘로 옮긴다.
     *
     * @param impact {@code econ_events.impact} (1~3)
     * @return {@code High}/{@code Medium}/{@code Low}. 범위 밖이면 {@code null}
     */
    public static String toImportance(short impact) {
        return IMPACT_TO_IMPORTANCE.get(impact);
    }
}
