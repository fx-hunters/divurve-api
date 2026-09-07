package com.divurve.domain.fx.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.time.LocalDate;
import java.util.Objects;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * {@code fx_rate_absences} 복합 PK — (통화쌍, 고시일, 환율 종류) (이슈 #116).
 *
 * <p>{@link FxRateId} 와 같은 모양이다. 두 표를 같은 키로 대조해야 구멍이 뺄셈으로 떨어진다.
 */
@Embeddable
public class FxRateAbsenceId implements Serializable {

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
    protected FxRateAbsenceId() {
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
        if (!(other instanceof FxRateAbsenceId that)) {
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
