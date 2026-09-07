package com.divurve.domain.master.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 통화쌍 마스터 (이슈 #111, ERD v3.0 §4.A). 테이블 {@code currency_pairs} 에 매핑된다.
 *
 * <p>{@code fx_rates.pair_code} 가 이 테이블을 참조하므로, 오타난 통화쌍은 DB 가 막는다.
 *
 * <p>{@link #isStored()} 는 "이 쌍의 환율을 {@code fx_rates} 에 적재하는가" 다. false 인 쌍은
 * {@link #getDeriveViaPairCode()} 를 거쳐 유도해야 하며, 그 유도 계산은 이 엔티티가 하지 않는다.
 * 현재 시드는 ECOS 가 원화 직접 고시로 주는 4쌍이 전부 저장 쌍이다(V21 주석 참고).
 */
@Entity
@Table(name = "currency_pairs")
public class CurrencyPair {

    /** {@code char(6)} 컬럼 — {@code Currency#currencyCode} 와 같은 이유로 JDBC 타입을 맞춘다. */
    @Id
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "pair_code", length = 6, nullable = false)
    private String pairCode;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "base_currency_code", nullable = false, length = 3)
    private String baseCurrencyCode;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "quote_currency_code", nullable = false, length = 3)
    private String quoteCurrencyCode;

    @Column(name = "is_stored", nullable = false)
    private boolean stored;

    /**
     * 유도 경로. FK 지만 {@code @ManyToOne} 이 아니라 스칼라로 매핑한다 — 자기참조 연관은
     * {@code open-in-view=false} 에서 프록시 초기화 문제만 만들고, 여기서 필요한 것은 코드값 하나다.
     */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "derive_via_pair_code", length = 6)
    private String deriveViaPairCode;

    /** JPA 전용 기본 생성자. 이 테이블은 마이그레이션 시드로만 채워진다. */
    protected CurrencyPair() {
    }

    public String getPairCode() {
        return pairCode;
    }

    public String getBaseCurrencyCode() {
        return baseCurrencyCode;
    }

    public String getQuoteCurrencyCode() {
        return quoteCurrencyCode;
    }

    /** 환율을 직접 적재하는 쌍인가. false 면 {@link #getDeriveViaPairCode()} 로 유도해야 한다. */
    public boolean isStored() {
        return stored;
    }

    public String getDeriveViaPairCode() {
        return deriveViaPairCode;
    }
}
