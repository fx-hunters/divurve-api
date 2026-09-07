package com.divurve.domain.user;

import static org.assertj.core.api.Assertions.assertThat;

import com.divurve.domain.RepositoryTestBase;
import com.divurve.domain.user.entity.User;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/**
 * {@link UserRepository#searchForAdmin} 을 <b>실제 PostgreSQL</b> 에 실행한다 (이슈 #118).
 *
 * <p><b>왜 이 테스트가 필요한가</b> — {@code AdminUserQueryServiceTest} 는 리포지토리를 Mock 으로
 * 두므로 JPQL 이 SQL 로 번역되어 실행되는 단계를 통째로 건너뛴다. 그래서
 * {@code GET /api/v1/admin/users} 가 배포에서 500 을 내는 동안에도 단위 테스트는 전부 green 이었다.
 * 원인은 검색어를 넘기지 않았을 때 {@code :keyword} 가 타입 없는 파라미터로 나가 PostgreSQL 이
 * {@code bytea} 로 결정하고 {@code lower(bytea)} 를 찾다 실패한 것이다 — <b>DB 없이는 구조적으로
 * 재현되지 않는 실패</b>다.
 *
 * <p>그러므로 여기서는 네 가지 조합(검색어 유/무 × 데모 필터 유/무)을 모두 실행한다. 결과 건수뿐
 * 아니라 <b>쿼리가 실행된다는 사실 자체</b>가 이 테스트가 지키는 것이다.
 */
@DisplayName("UserRepository.searchForAdmin — 실 PostgreSQL")
class AdminUserSearchRepositoryTest extends RepositoryTestBase {

    private static final Pageable FIRST_PAGE =
            PageRequest.of(0, 50, Sort.by(Sort.Direction.DESC, "createdAt"));

    @Autowired
    private UserRepository userRepository;

    private UUID normalUserId;
    private UUID demoUserId;

    @BeforeEach
    void setUp() {
        userRepository.deleteAll();
        normalUserId = userRepository.save(User.create("alice@example.com", "앨리스", "hash")).getId();
        demoUserId = userRepository.save(User.createDemo("demo-1@divurve.local", "둘러보기")).getId();
    }

    @Test
    @DisplayName("검색어·데모 필터가 모두 없으면 전체를 반환한다 — 관리자 화면의 첫 호출이 이 경로다")
    void 조건이_없으면_전체를_반환한다() {
        List<UUID> found = idsOf(null, null);

        assertThat(found).containsExactlyInAnyOrder(normalUserId, demoUserId);
    }

    @Test
    @DisplayName("검색어만 있으면 이메일·이름 부분일치로 거른다")
    void 검색어로_거른다() {
        assertThat(idsOf("alice", null)).containsExactly(normalUserId);
        assertThat(idsOf("앨리", null)).containsExactly(normalUserId);
        assertThat(idsOf("없는사용자", null)).isEmpty();
    }

    @Test
    @DisplayName("검색어는 대소문자를 가리지 않는다")
    void 검색어는_대소문자를_가리지_않는다() {
        assertThat(idsOf("ALICE", null)).containsExactly(normalUserId);
    }

    @Test
    @DisplayName("데모 필터만 있으면 계정 성격으로 거른다")
    void 데모_필터로_거른다() {
        assertThat(idsOf(null, true)).containsExactly(demoUserId);
        assertThat(idsOf(null, false)).containsExactly(normalUserId);
    }

    @Test
    @DisplayName("검색어와 데모 필터는 AND 로 결합된다")
    void 두_조건은_함께_적용된다() {
        assertThat(idsOf("alice", false)).containsExactly(normalUserId);
        assertThat(idsOf("alice", true)).isEmpty();
    }

    private List<UUID> idsOf(String keyword, Boolean demo) {
        return userRepository.searchForAdmin(keyword, demo, FIRST_PAGE).getContent().stream()
                .map(User::getId)
                .toList();
    }
}
