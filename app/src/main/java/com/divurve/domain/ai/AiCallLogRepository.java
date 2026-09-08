package com.divurve.domain.ai;

import com.divurve.domain.ai.entity.AiCallLog;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * AI 호출 로그 접근 리포지토리 (이슈 #143). Spring Data JPA 가 런타임 구현을 주입한다.
 */
public interface AiCallLogRepository extends JpaRepository<AiCallLog, UUID> {

    /**
     * 관리자 목록 조회. {@code null} 파라미터는 "조건 없음" 이다 — 조합마다 메서드를 만들면 필터가
     * 하나 늘 때 개수가 배가 된다({@code UserRepository#searchForAdmin} 과 같은 방식).
     *
     * <p><b>{@code cast(...)} 이 필요한 이유 (이슈 #118 과 동일한 함정).</b> 캐스트가 없으면
     * Hibernate 가 파라미터를 타입 없는 바인드로 내보내고 PostgreSQL 이 타입을 정하지 못해,
     * 조건을 <b>넘기지 않았을 때</b> 파싱 단계에서 터진다. 필터 없이 전체를 보는 가장 흔한 호출이
     * 정확히 그 경우다. PostgreSQL 은 {@code :param is null} 이 참이어서 뒷부분을 실행하지 않더라도
     * <b>파싱 시점에 식 전체의 타입을 정하기 때문</b>이다.
     *
     * <p><b>시각 두 개는 {@code null} 을 아예 받지 않는다</b> — 여기가 #118 보다 한 겹 더 깊다.
     * 문자열은 앞의 {@code ? is null} 이 파라미터를 {@code bytea} 로 굳혀도 뒤의
     * {@code cast(? as text)} 가 통한다(bytea → text 캐스트가 존재한다). {@code timestamp} 로는
     * 그 캐스트가 없어 {@code cannot cast type bytea to timestamp} 로 죽고, 등장하는 자리를 모두
     * 캐스트해도 마찬가지다 — Hibernate 가 null {@code Instant} 를 그 타입으로 바인딩하기 때문에
     * SQL 쪽에서 고칠 수 있는 문제가 아니다.
     *
     * <p>그래서 <b>경계값을 호출자가 채운다</b>. {@link AiCallLogQueryService} 가 열린 구간을
     * {@code Instant.EPOCH}·먼 미래로 바꿔 넘기고, 여기서는 {@code between} 하나로 끝낸다 —
     * {@code null} 이 없으면 타입을 정하지 못할 파라미터도 없다.
     *
     * <p>{@code :demo} 는 boolean 컬럼과 비교되어 타입이 추론되므로 캐스트하지 않는다.
     *
     * @param from    조회 시작 시각 (포함). 열린 구간은 호출자가 하한 상수로 바꿔 넘긴다
     * @param to      조회 종료 시각 (포함). 열린 구간은 호출자가 상한 상수로 바꿔 넘긴다
     *
     * @param purpose {@code narrate}/{@code extract}. {@code null} 이면 전체
     * @param surface 서술 대상 화면. {@code null} 이면 전체
     * @param outcome 호출 결과. {@code null} 이면 전체
     * @param demo    데모 트래픽만/일반만. {@code null} 이면 전체
     */
    @Query("select l from AiCallLog l "
            + "where l.requestedAt between :from and :to "
            + "  and (cast(:purpose as string) is null "
            + "       or l.purpose = cast(:purpose as string)) "
            + "  and (cast(:surface as string) is null "
            + "       or l.surface = cast(:surface as string)) "
            + "  and (cast(:outcome as string) is null "
            + "       or l.outcome = cast(:outcome as string)) "
            + "  and (:demo is null or l.isDemo = :demo)")
    Page<AiCallLog> searchForAdmin(
            @Param("from") Instant from,
            @Param("to") Instant to,
            @Param("purpose") String purpose,
            @Param("surface") String surface,
            @Param("outcome") String outcome,
            @Param("demo") Boolean demo,
            Pageable pageable);

    /**
     * 일자·용도·모델별 집계. 관리자 화면에서 실제로 보는 것은 행 목록이 아니라 이 추이다.
     *
     * <p>날짜는 <b>UTC 기준</b>으로 자른다. 표시 시간대는 화면의 선택이며, 여기서 로컬 시간대를
     * 가정하면 서버·DB·브라우저가 각자 다른 하루 경계를 쓰게 된다.
     *
     * @return {@code [일자(LocalDate), purpose, model, 호출 수, 입력 토큰 합, 출력 토큰 합]}
     */
    @Query(value = """
            select cast(l.requested_at at time zone 'UTC' as date) as day,
                   l.purpose,
                   l.model,
                   count(*)                    as calls,
                   coalesce(sum(l.input_tokens), 0)  as input_tokens,
                   coalesce(sum(l.output_tokens), 0) as output_tokens
              from ai_call_logs l
             where (cast(:from as timestamptz) is null or l.requested_at >= cast(:from as timestamptz))
               and (cast(:to   as timestamptz) is null or l.requested_at <= cast(:to as timestamptz))
             group by day, l.purpose, l.model
             order by day desc, l.purpose, l.model
            """, nativeQuery = true)
    List<Object[]> summarize(@Param("from") Instant from, @Param("to") Instant to);
}
