package com.divurve.domain.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.divurve.domain.user.entity.User;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * {@link AdminBootstrapService} — 기동 시 관리자 계정 준비.
 *
 * <p>멱등성과 "비밀번호를 덮어쓰지 않는다" 가 핵심이다. 기동마다 덮어쓰면 운영자가 바꾼
 * 비밀번호가 재배포 때 조용히 되돌아간다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AdminBootstrapService")
class AdminBootstrapServiceTest {

    private static final String EMAIL = "admin@divurve.local";
    private static final String PASSWORD = "bootstrap-password";

    @Mock
    private UserRepository userRepository;

    private AdminBootstrapService service(String email, String password) {
        return new AdminBootstrapService(userRepository, email, password);
    }

    @Test
    @DisplayName("이메일 설정이 없으면 아무것도 하지 않는다")
    void noEmail_Skips() {
        assertThat(service("", PASSWORD).bootstrap())
                .isEqualTo(AdminBootstrapService.BootstrapOutcome.SKIPPED_NO_EMAIL);
        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("이메일이 null 이어도 건너뛴다")
    void nullEmail_Skips() {
        assertThat(service(null, PASSWORD).bootstrap())
                .isEqualTo(AdminBootstrapService.BootstrapOutcome.SKIPPED_NO_EMAIL);
    }

    @Test
    @DisplayName("계정이 없고 비밀번호도 없으면 만들지 않는다 — 로그인할 수 없는 계정은 쓸모가 없다")
    void noPassword_Skips() {
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.empty());

        assertThat(service(EMAIL, "  ").bootstrap())
                .isEqualTo(AdminBootstrapService.BootstrapOutcome.SKIPPED_NO_PASSWORD);
        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("계정이 없으면 ADMIN 으로 만든다 — 이름은 이메일 로컬파트다")
    void missingAccount_Creates() {
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.empty());

        assertThat(service(EMAIL, PASSWORD).bootstrap())
                .isEqualTo(AdminBootstrapService.BootstrapOutcome.CREATED);

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(saved.capture());
        assertThat(saved.getValue().isAdmin()).isTrue();
        assertThat(saved.getValue().getEmail()).isEqualTo(EMAIL);
        assertThat(saved.getValue().getName()).isEqualTo("admin");
        // 평문을 그대로 저장하지 않는다.
        assertThat(saved.getValue().getPasswordHash()).isNotEqualTo(PASSWORD);
    }

    @Test
    @DisplayName("@ 가 없는 이메일이면 전체를 이름으로 쓴다")
    void emailWithoutAt_UsesWholeString() {
        when(userRepository.findByEmail("root")).thenReturn(Optional.empty());

        service("root", PASSWORD).bootstrap();

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(saved.capture());
        assertThat(saved.getValue().getName()).isEqualTo("root");
    }

    @Test
    @DisplayName("기존 계정은 role 만 올리고 비밀번호를 덮어쓰지 않는다")
    void existingAccount_PromotesOnly() {
        User existing = User.create(EMAIL, "기존 이름", "existing-hash");
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(existing));

        assertThat(service(EMAIL, PASSWORD).bootstrap())
                .isEqualTo(AdminBootstrapService.BootstrapOutcome.PROMOTED);
        assertThat(existing.isAdmin()).isTrue();
        assertThat(existing.getPasswordHash()).isEqualTo("existing-hash");
        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("이미 관리자면 아무것도 하지 않는다 — 기동마다 반복 호출되므로 멱등해야 한다")
    void alreadyAdmin_NoOp() {
        User existing = User.create(EMAIL, "관리자", "hash");
        existing.promoteToAdmin();
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(existing));

        assertThat(service(EMAIL, PASSWORD).bootstrap())
                .isEqualTo(AdminBootstrapService.BootstrapOutcome.ALREADY_ADMIN);
        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("null 의존은 거부한다")
    void nullRepository_Throws() {
        org.assertj.core.api.Assertions
                .assertThatThrownBy(() -> new AdminBootstrapService(null, EMAIL, PASSWORD))
                .isInstanceOf(NullPointerException.class);
    }
}
