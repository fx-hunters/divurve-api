package com.divurve.domain.fx;

import com.divurve.domain.fx.entity.FxRate;
import com.divurve.domain.fx.entity.FxRateId;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/**
 * 일별 환율 리포지토리 (이슈 #111). Spring Data JPA 가 런타임 구현을 주입한다.
 */
public interface FxRateRepository extends JpaRepository<FxRate, FxRateId> {

    /**
     * 환율 한 건을 넣거나 이미 있으면 갱신한다.
     *
     * <p><b>{@code save()} 를 쓰지 않는 이유</b> — 복합 PK 엔티티에 대해 Spring Data 는 "id 로
     * 조회해 보고 없으면 insert" 로 동작한다. 스케줄러와 관리자 수동 갱신이 같은 날짜를 동시에
     * 적재하면 둘 다 "없음" 을 보고 insert 를 시도해 PK 충돌로 하나가 죽는다. {@code ON CONFLICT}
     * 는 그 경합을 DB 가 해결하게 한다.
     *
     * <p>ECOS 는 같은 날짜의 값을 나중에 정정하기도 하므로 갱신은 덮어쓰기다.
     *
     * @return 반영된 행 수 (신규·갱신 모두 1)
     */
    @Transactional
    @Modifying
    @Query(value = """
            insert into fx_rates (pair_code, quote_date, rate_type, rate, data_source, fetched_at)
            values (:pairCode, :quoteDate, :rateType, :rate, :dataSource, :fetchedAt)
            on conflict (pair_code, quote_date, rate_type)
            do update set rate = excluded.rate,
                          data_source = excluded.data_source,
                          fetched_at = excluded.fetched_at
            """, nativeQuery = true)
    int upsert(
            @Param("pairCode") String pairCode,
            @Param("quoteDate") LocalDate quoteDate,
            @Param("rateType") String rateType,
            @Param("rate") BigDecimal rate,
            @Param("dataSource") String dataSource,
            @Param("fetchedAt") Instant fetchedAt);

    /** 차트용 시계열 — 한 통화쌍·종류의 기간 구간을 오래된 순으로 반환한다. */
    List<FxRate> findByIdPairCodeAndIdRateTypeAndIdQuoteDateBetweenOrderByIdQuoteDateAsc(
            String pairCode, String rateType, LocalDate from, LocalDate to);

    /**
     * 구멍 판정용 — 구간 안에서 값이 있는 날짜만 뽑는다 (이슈 #116).
     *
     * <p>엔티티를 통째로 들고 오지 않는 이유는 5년치(약 1,300행) × 4쌍을 요청마다 훑기 때문이다.
     * 판정에 필요한 것은 날짜 집합뿐이다.
     */
    @Query("""
            select r.id.quoteDate from FxRate r
            where r.id.pairCode = :pairCode
              and r.id.rateType = :rateType
              and r.id.quoteDate between :from and :to
            """)
    List<LocalDate> findQuoteDates(
            @Param("pairCode") String pairCode,
            @Param("rateType") String rateType,
            @Param("from") LocalDate from,
            @Param("to") LocalDate to);

    /**
     * 가장 최근 관측 하나 (이슈 #116). 최신 환율 읽기 경로가 신선도를 판단하는 근거다.
     *
     * <p>{@code to} 를 받는 이유는 미래 날짜가 섞여 들어오는 것을 막기 위해서다 — ECOS 정정이
     * 앞선 날짜로 들어오는 일은 없지만, 기준일을 고정해야 같은 요청이 같은 값을 준다.
     */
    Optional<FxRate> findTopByIdPairCodeAndIdRateTypeAndIdQuoteDateLessThanEqualOrderByIdQuoteDateDesc(
            String pairCode, String rateType, LocalDate to);
}
