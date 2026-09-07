package com.divurve.domain.user;

import com.divurve.domain.user.entity.User;
import java.util.Optional;
import java.util.UUID;
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
     * @param keyword 이메일 또는 이름의 부분일치 검색어. {@code null} 이면 전체
     * @param demo    데모 계정만/일반 계정만. {@code null} 이면 전체
     */
    @Query("select u from User u "
            + "where (:keyword is null "
            + "       or lower(u.email) like lower(concat('%', :keyword, '%')) "
            + "       or lower(u.name) like lower(concat('%', :keyword, '%'))) "
            + "  and (:demo is null or u.isDemo = :demo)")
    Page<User> searchForAdmin(
            @Param("keyword") String keyword, @Param("demo") Boolean demo, Pageable pageable);
}
