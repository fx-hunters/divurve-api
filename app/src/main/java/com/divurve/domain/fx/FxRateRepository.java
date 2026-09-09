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

    /**
     * 통화쌍별 적재 신선도 (이슈 #128). 관리자 화면의 "마지막 갱신" 자리를 채운다.
     *
     * <p><b>{@code rate_type} 을 나누지 않는다.</b> 적재는 쌍 단위로 한 번 돌고 종류별로 갈리지
     * 않는다 — 지금 들어오는 값이 {@code mid} 하나뿐이라 나눠 봐야 같은 수가 다섯 줄로 늘 뿐이다.
     * 종류별 적재 시각이 갈리는 날이 오면 그때 키를 늘린다.
     *
     * <p><b>두 최댓값은 서로 다른 질문에 답한다.</b> {@code lastFetchedAt} 은 "적재가 언제
     * 돌았는가", {@code lastQuoteDate} 는 "어느 날짜까지 채워져 있는가" 다. 오늘 적재가 돌아도
     * ECOS 가 어제까지만 고시했다면 뒤엣값은 어제다 — 같은 행에서 온 값이 아니어도 된다.
     *
     * <p>정렬을 쿼리에 두는 이유는 화면의 줄 순서가 요청마다 바뀌지 않게 하려는 것뿐이다.
     */
    @Query("""
            select r.id.pairCode as pairCode,
                   max(r.fetchedAt) as lastFetchedAt,
                   max(r.id.quoteDate) as lastQuoteDate
            from FxRate r
            group by r.id.pairCode
            order by r.id.pairCode
            """)
    List<PairFreshness> freshnessByPair();

    /**
     * 통화쌍 하나의 적재 신선도.
     *
     * <p>행이 하나도 없는 쌍은 여기에 나오지 않는다 — {@code group by} 는 없는 그룹을 만들지
     * 않는다. "한 번도 받지 못한 쌍" 은 통화쌍 목록(이슈 #111)과 대조해야 보이며, 이 조회의
     * 질문이 아니다.
     */
    interface PairFreshness {
        String getPairCode();

        Instant getLastFetchedAt();

        LocalDate getLastQuoteDate();
    }
}
