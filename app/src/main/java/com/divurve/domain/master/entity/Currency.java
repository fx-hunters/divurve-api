package com.divurve.domain.master.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 통화 마스터 (이슈 #111, ERD v3.0 §4.A). 테이블 {@code currencies} 에 매핑된다.
 *
 * <p>이 엔티티가 생기기 전까지 통화 정보는 {@code CurrencyMaster} 하드코딩 상수였다. 그래서
 * "ECOS 가 GBP 를 고시하지 않는다" 는 사실을 표현할 자리가 없어, 지원하지 않는 통화가 지원 통화
 * 목록으로 나갔다(이슈 #95). {@link #isSupported()} 가 그 자리다.
 *
 * <p><b>여기에 계산은 없다.</b> 표시 규칙과 조달 가능 여부만 담는다 — 환율 계산은
 * {@code engine} 모듈이, 환율 조달은 {@code FxRateProvider} 가 한다.
 *
 * <p>{@code usd_side} 는 {@code text} + CHECK 컬럼이라 문자열로 받는다(V20 주석 참고). 이 값은
 * 삼각환산 방향을 나타내는 참조 데이터일 뿐 분기의 주체가 아니므로 자바 enum 으로 올리지 않는다.
 */
@Entity
@Table(name = "currencies")
public class Currency {

    /**
     * {@code char(3)} 컬럼이다. {@code @JdbcTypeCode(CHAR)} 가 없으면 Hibernate 가 이 필드를
     * {@code varchar} 로 보고 {@code ddl-auto=validate} 가 기동을 막는다.
     */
    @Id
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "currency_code", length = 3, nullable = false)
    private String currencyCode;

    @Column(name = "name_ko", nullable = false)
    private String nameKo;

    @Column(name = "symbol", nullable = false)
    private String symbol;

    @Column(name = "minor_units", nullable = false)
    private short minorUnits;

    @Column(name = "quote_unit", nullable = false)
    private short quoteUnit;

    @Column(name = "usd_side", nullable = false)
    private String usdSide;

    @Column(name = "is_home_currency", nullable = false)
    private boolean homeCurrency;

    @Column(name = "is_supported", nullable = false)
    private boolean supported;

    @Column(name = "support_note")
    private String supportNote;

    @Column(name = "color_token")
    private String colorToken;

    @Column(name = "sort_order", nullable = false)
    private short sortOrder;

    /** JPA 전용 기본 생성자. 이 테이블은 마이그레이션 시드로만 채워지므로 다른 생성자가 없다. */
    protected Currency() {
    }

    public String getCurrencyCode() {
        return currencyCode;
    }

    public String getNameKo() {
        return nameKo;
    }

    public String getSymbol() {
        return symbol;
    }

    /** 소수 자릿수 (JPY·KRW 0, 대부분 2). 표시 반올림의 근거다. */
    public short getMinorUnits() {
        return minorUnits;
    }

    /** 호가 단위. ECOS 는 JPY 를 원/100엔으로 고시하므로 JPY 만 100 이다. */
    public short getQuoteUnit() {
        return quoteUnit;
    }

    /** 삼각환산 방향 — {@code self}/{@code base}/{@code quote}/{@code none}. */
    public String getUsdSide() {
        return usdSide;
    }

    public boolean isHomeCurrency() {
        return homeCurrency;
    }

    /** 환율을 실제로 조달할 수 있는가. 표시 가능 여부가 아니다. */
    public boolean isSupported() {
        return supported;
    }

    /** 미지원 사유. {@link #isSupported()} 가 true 면 보통 {@code null} 이다. */
    public String getSupportNote() {
        return supportNote;
    }

    public String getColorToken() {
        return colorToken;
    }

    public short getSortOrder() {
        return sortOrder;
    }
}
