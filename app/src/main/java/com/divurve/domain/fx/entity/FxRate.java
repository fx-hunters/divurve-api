package com.divurve.domain.fx.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * 일별 환율 (이슈 #111, ERD v3.0 §0.E). 테이블 {@code fx_rates} 에 매핑된다.
 *
 * <p>{@code rate} 는 <b>1 외화당 원화</b>다. ECOS 가 100엔당으로 주는 JPY 는 적재 전에
 * 1단위로 접힌다 — 저장된 값의 의미가 통화마다 달라지면 이 표를 읽는 모든 코드가 통화별 분기를 갖는다.
 *
 * <p>쓰기는 {@code FxRateRepository.upsert} 네이티브 쿼리가 담당한다. 복합 PK 에 대해
 * {@code save()} 는 select-then-insert 라 같은 날짜를 두 경로가 동시에 적재하면 충돌한다.
 * 그래서 이 엔티티에는 상태를 바꾸는 메서드가 없다 — 읽기 전용 투영이다.
 */
@Entity
@Table(name = "fx_rates")
public class FxRate {

    @EmbeddedId
    private FxRateId id;

    @Column(name = "rate", nullable = false, precision = 14, scale = 6)
    private BigDecimal rate;

    /** 어디서 받은 값인가 — {@code ECOS} 등. 우리가 계산한 값이면 그렇게 밝힌다. */
    @Column(name = "data_source", nullable = false)
    private String dataSource;

    /** 우리가 가져온 시각. 고시된 날짜({@code quote_date})와 다르다. */
    @Column(name = "fetched_at", nullable = false)
    private Instant fetchedAt;

    /** JPA 전용 기본 생성자. */
    protected FxRate() {
    }

    public FxRateId getId() {
        return id;
    }

    public BigDecimal getRate() {
        return rate;
    }

    public String getDataSource() {
        return dataSource;
    }

    public Instant getFetchedAt() {
        return fetchedAt;
    }
}
