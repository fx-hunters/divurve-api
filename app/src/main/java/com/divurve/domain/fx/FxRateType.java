package com.divurve.domain.fx;

import com.divurve.common.exception.InvalidRequestException;
import java.util.Arrays;

/**
 * 환율 종류 (이슈 #111, ERD v3.0 §0.E {@code rate_type}).
 *
 * <p><b>현재 적재되는 것은 {@link #MID} 하나뿐이다.</b> ECOS 731Y001 은 매매기준율만 고시한다.
 * 나머지 4종을 {@code mid × (1 + 스프레드)} 로 계산해 저장하지 않는다 — 그것은 가정값을 출처 있는
 * 관측인 것처럼 저장하는 행위다(NFR-DT-01 · FR-CM-10). 스프레드는 지금처럼 계산 시점에
 * {@code ExchangeCostCalculator} 가 적용하고 그 결과는 {@code plans} 스냅샷에만 남는다.
 *
 * <p>그래도 5종을 전부 선언해 두는 이유는, 은행 고시 출처가 생기면 마이그레이션 없이 행만
 * 늘리면 되게 하기 위해서다 (DB CHECK 도 5종을 전부 열어 뒀다).
 *
 * <p>DB 에는 {@code text} 컬럼에 <b>소문자 코드</b>로 저장된다. enum 이름과 다르므로
 * {@link #code()}/{@link #fromCode(String)} 로만 오간다.
 */
public enum FxRateType {

    /** 매매기준율. ECOS 가 고시하는 유일한 값이고, 통계·전망 계산이 쓰는 값이다. */
    MID("mid"),

    /** 전신환 살 때. */
    TT_BUY("tt_buy"),

    /** 전신환 팔 때 — 원화로 외화를 살 때의 비용 근거. */
    TT_SELL("tt_sell"),

    /** 현찰 살 때. */
    CASH_BUY("cash_buy"),

    /** 현찰 팔 때. */
    CASH_SELL("cash_sell");

    private final String code;

    FxRateType(String code) {
        this.code = code;
    }

    /** DB·API 에 쓰이는 소문자 코드. */
    public String code() {
        return code;
    }

    /**
     * 코드 문자열을 종류로 바꾼다.
     *
     * @param code 소문자 코드 ({@code mid} 등)
     * @throws InvalidRequestException 알 수 없는 코드인 경우 (400)
     */
    public static FxRateType fromCode(String code) {
        return Arrays.stream(values())
                .filter(type -> type.code.equals(code))
                .findFirst()
                .orElseThrow(() -> new InvalidRequestException(
                        "지원하지 않는 환율 종류입니다: " + code, "rate_type"));
    }
}
