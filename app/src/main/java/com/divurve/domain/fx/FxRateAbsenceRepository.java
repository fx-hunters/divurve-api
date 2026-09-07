package com.divurve.domain.fx;

import com.divurve.domain.fx.entity.FxRateAbsence;
import com.divurve.domain.fx.entity.FxRateAbsenceId;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/**
 * 고시 부재 확정 리포지토리 (이슈 #116). Spring Data JPA 가 런타임 구현을 주입한다.
 */
public interface FxRateAbsenceRepository extends JpaRepository<FxRateAbsence, FxRateAbsenceId> {

    /**
     * 부재 확정을 넣거나 이미 있으면 확인 시각만 갱신한다.
     *
     * <p>{@code save()} 가 아니라 {@code ON CONFLICT} 인 이유는 {@link FxRateRepository#upsert}
     * 와 같다 — 복합 PK 라 select-then-insert 가 되고, 백필과 스케줄러가 같은 날짜를 동시에
     * 확정하면 PK 충돌로 하나가 죽는다.
     *
     * @return 반영된 행 수 (신규·갱신 모두 1)
     */
    @Transactional
    @Modifying
    @Query(value = """
            insert into fx_rate_absences (pair_code, quote_date, rate_type, confirmed_at)
            values (:pairCode, :quoteDate, :rateType, :confirmedAt)
            on conflict (pair_code, quote_date, rate_type)
            do update set confirmed_at = excluded.confirmed_at
            """, nativeQuery = true)
    int confirm(
            @Param("pairCode") String pairCode,
            @Param("quoteDate") LocalDate quoteDate,
            @Param("rateType") String rateType,
            @Param("confirmedAt") Instant confirmedAt);

    /**
     * 부재 확정을 지운다. ECOS 가 뒤늦게 그 날짜를 채워 고시하면 {@code fx_rates} 에 행이 생기는데,
     * 두 표에 같은 키가 동시에 있으면 어느 쪽이 사실인지 알 수 없다.
     *
     * @return 지워진 행 수 (없었으면 0)
     */
    @Transactional
    @Modifying
    @Query(value = """
            delete from fx_rate_absences
            where pair_code = :pairCode and quote_date = :quoteDate and rate_type = :rateType
            """, nativeQuery = true)
    int release(
            @Param("pairCode") String pairCode,
            @Param("quoteDate") LocalDate quoteDate,
            @Param("rateType") String rateType);

    /** 구멍 판정용 — 구간 안에서 부재가 확정된 날짜만 뽑는다. 엔티티를 통째로 들고 오지 않는다. */
    @Query("""
            select a.id.quoteDate from FxRateAbsence a
            where a.id.pairCode = :pairCode
              and a.id.rateType = :rateType
              and a.id.quoteDate between :from and :to
            """)
    List<LocalDate> findConfirmedDates(
            @Param("pairCode") String pairCode,
            @Param("rateType") String rateType,
            @Param("from") LocalDate from,
            @Param("to") LocalDate to);
}
