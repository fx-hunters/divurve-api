package com.divurve.domain.user;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.divurve.common.exception.ForbiddenException;
import com.divurve.domain.user.entity.User;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * {@link AdminAccessService} — 관리자 인가 판정.
 *
 * <p>세 갈래를 전부 고정한다: 관리자·일반 사용자·존재하지 않는 계정.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AdminAccessService")
class AdminAccessServiceTest {

    private static final UUID USER_ID = UUID.randomUUID();

    @Mock
    private UserRepository userRepository;

    private AdminAccessService service() {
        return new AdminAccessService(userRepository);
    }

    private static User admin() {
        User user = User.create("admin@divurve.local", "관리자", "hash");
        user.promoteToAdmin();
        return user;
    }

    @Test
    @DisplayName("관리자면 통과한다")
    void admin_Passes() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(admin()));

        assertThatCode(() -> service().requireAdmin(USER_ID)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("일반 사용자는 403 이다 — 토큰을 갱신해도 해결되지 않으므로 401 이 아니다")
    void normalUser_Forbidden() {
        when(userRepository.findById(USER_ID))
                .thenReturn(Optional.of(User.create("u@example.com", "사용자", "hash")));

        assertThatThrownBy(() -> service().requireAdmin(USER_ID))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("관리자 권한");
    }

    @Test
    @DisplayName("계정이 없어도 같은 403 이다 — 계정 존재 여부를 응답으로 흘리지 않는다")
    void missingUser_Forbidden() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().requireAdmin(USER_ID))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    @DisplayName("null 인자와 의존은 거부한다")
    void nullArguments_Throw() {
        assertThatThrownBy(() -> new AdminAccessService(null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> service().requireAdmin(null))
                .isInstanceOf(NullPointerException.class);
    }
}
