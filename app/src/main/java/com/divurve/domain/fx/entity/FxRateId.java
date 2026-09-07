package com.divurve.domain.fx.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.time.LocalDate;
import java.util.Objects;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * {@code fx_rates} 복합 PK — (통화쌍, 고시일, 환율 종류) (이슈 #111).
 *
 * <p>세 값이 함께여야 한 행을 가리킨다. 같은 날 같은 쌍이라도 매매기준율과 전신환 매도율은
 * 다른 값이므로 {@code rate_type} 이 키에 들어간다.
 */
@Embeddable
public class FxRateId implements Serializable {

    private static final long serialVersionUID = 1L;

    /** {@code char(6)} 컬럼 — JDBC 타입을 맞추지 않으면 {@code ddl-auto=validate} 가 막는다. */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "pair_code", nullable = false, length = 6)
    private String pairCode;

    @Column(name = "quote_date", nullable = false)
    private LocalDate quoteDate;

    /** {@link com.divurve.domain.fx.FxRateType} 의 소문자 코드. */
    @Column(name = "rate_type", nullable = false)
    private String rateType;

    /** JPA 전용 기본 생성자. */
    protected FxRateId() {
    }

    public String getPairCode() {
        return pairCode;
    }

    public LocalDate getQuoteDate() {
        return quoteDate;
    }

    public String getRateType() {
        return rateType;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof FxRateId that)) {
            return false;
        }
        return Objects.equals(pairCode, that.pairCode)
                && Objects.equals(quoteDate, that.quoteDate)
                && Objects.equals(rateType, that.rateType);
    }

    @Override
    public int hashCode() {
        return Objects.hash(pairCode, quoteDate, rateType);
    }
}
