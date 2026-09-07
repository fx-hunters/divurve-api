package com.divurve.domain.fx.entity;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * 고시 부재 확정 (이슈 #116, 마이그레이션 V23). 테이블 {@code fx_rate_absences} 에 매핑된다.
 *
 * <p>"우리가 ECOS 에 물었고, 그 날짜에 고시가 없었다" 는 관측이다. 값을 만들어 채우는 것이
 * 아니므로 NFR-DT-01 에 어긋나지 않는다 — 오히려 이 행이 없으면 공휴일과 배치 실패를 구분할
 * 근거가 없어 {@code fx_rates} 가 이득이 아니라 부채가 된다.
 *
 * <p>{@link FxRate} 와 마찬가지로 쓰기는 리포지토리의 {@code ON CONFLICT} 네이티브 쿼리가
 * 담당하므로 이 엔티티에는 상태를 바꾸는 메서드가 없다 — 읽기 전용 투영이다.
 */
@Entity
@Table(name = "fx_rate_absences")
public class FxRateAbsence {

    @EmbeddedId
    private FxRateAbsenceId id;

    /** 부재를 확인한 시각. 고시가 없던 날({@code quote_date})과 다르다. */
    @Column(name = "confirmed_at", nullable = false)
    private Instant confirmedAt;

    /** JPA 전용 기본 생성자. */
    protected FxRateAbsence() {
    }

    public FxRateAbsenceId getId() {
        return id;
    }

    public Instant getConfirmedAt() {
        return confirmedAt;
    }
}
