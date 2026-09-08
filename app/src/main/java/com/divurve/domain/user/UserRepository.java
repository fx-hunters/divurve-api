package com.divurve.domain.user;

import com.divurve.domain.user.entity.User;
import java.util.Optional;
import java.util.UUID;
import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 사용자 접근 리포지토리. Spring Data JPA 가 런타임 구현을 주입한다.
 * 자체 로직이 없는 인터페이스이므로 별도 @PersistenceAdapter 구현체는 두지 않는다.
 */
public interface UserRepository extends JpaRepository<User, UUID> {

    /** 이메일로 사용자를 조회한다. */
    Optional<User> findByEmail(String email);

    /**
     * 관리자 목록 조회 (이슈 #111). 이메일·이름 부분일치와 데모 여부로 거른다.
     *
     * <p>두 조건을 파생 쿼리 메서드가 아니라 JPQL 한 개로 처리하는 이유 — 조건이 각각 있고 없고를
     * 조합하면 메서드가 4개가 되고, 조건이 하나 늘 때마다 배가 된다. {@code null} 은 "조건 없음" 이다.
     *
     * <p><b>{@code cast(... as string)} 이 왜 필요한가 (이슈 #118).</b> 캐스트가 없으면 Hibernate 가
     * {@code :keyword} 를 타입 없는 바인드 파라미터로 내보내고, PostgreSQL 은 타입을 못 정한 파라미터를
     * {@code bytea} 로 결정한다. 그러면 {@code lower(bytea)} 라는 없는 함수를 부르게 되어
     * {@code SQLGrammarException} 이 나고, 전역 핸들러가 이를 500 {@code INTERNAL_ERROR} 로 내보낸다.
     * <b>키워드를 넘기면 통과하고 넘기지 않으면 터진다</b> — PostgreSQL 은 {@code :keyword is null} 이
     * 참이어서 뒷부분을 실행하지 않더라도 <b>파싱 시점에 식 전체의 타입을 정하기 때문</b>이다.
     * 그래서 "필터 없이 전체 목록" 이라는 가장 흔한 호출이 정확히 깨졌다.
     *
     * <p>{@code :demo} 는 {@code u.isDemo = :demo} 로 boolean 컬럼과 비교되어 타입이 추론되므로
     * 캐스트가 필요 없다. 그래도 대칭을 위해 붙이지 않는다 — 필요 없는 캐스트는 왜 있는지 설명할 수 없다.
     *
     * @param keyword 이메일 또는 이름의 부분일치 검색어. {@code null} 이면 전체
     * @param demo    데모 계정만/일반 계정만. {@code null} 이면 전체
     */
    @Query("select u from User u "
            + "where (:keyword is null "
            + "       or lower(u.email) like lower(concat('%', cast(:keyword as string), '%')) "
            + "       or lower(u.name) like lower(concat('%', cast(:keyword as string), '%'))) "
            + "  and (:demo is null or u.isDemo = :demo)")
    Page<User> searchForAdmin(
            @Param("keyword") String keyword, @Param("demo") Boolean demo, Pageable pageable);

    /**
     * 마지막 접속이 {@code threshold} 보다 오래된 데모 계정의 id 를 조회한다 (이슈 #138).
     *
     * <p><b>{@code coalesce} 가 필요한 이유</b> — {@code last_login_at} 은 nullable 이라
     * {@code lastLoginAt < :threshold} 로 쓰면 그 값이 비어 있는 행이 영구히 남는다. 데모는 발급
     * 시점에 접속을 기록하므로 실무상 비어 있지 않지만, 판정이 컬럼 하나의 존재에 의존하면
     * 기록 경로가 하나 바뀔 때 정리가 조용히 멈춘다.
     *
     * <p><b>{@code isDemo} 만 본다</b> — {@code sampleDataSeeded} 가 아니다. 실연동 도착 전까지
     * 일반 가입 계정도 같은 샘플을 받으므로(이슈 #108) 그것을 기준으로 삼으면 실제 회원이 지워진다.
     *
     * <p>엔티티가 아니라 id 만 돌려주는 이유 — 호출자는 지우기만 하고 필드를 읽지 않는다.
     *
     * @param threshold 이 시각보다 마지막 접속이 오래된 계정이 대상이다
     * @param pageable  1회 실행 상한. 상한 없는 벌크 삭제를 피한다
     */
    @Query("select u.id from User u "
            + "where u.isDemo = true "
            + "  and coalesce(u.lastLoginAt, u.createdAt) < :threshold")
    List<UUID> findExpiredDemoUserIds(
            @Param("threshold") Instant threshold, Pageable pageable);
}
