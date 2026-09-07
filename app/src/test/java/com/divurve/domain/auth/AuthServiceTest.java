package com.divurve.domain.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.divurve.common.exception.DuplicateResourceException;
import com.divurve.common.exception.UnauthorizedException;
import com.divurve.domain.auth.AuthService.AuthResult;
import com.divurve.domain.port.AuthPrincipal;
import com.divurve.domain.port.AuthTokens;
import com.divurve.domain.port.TokenProvider;
import com.divurve.domain.user.UserRepository;
import com.divurve.domain.user.entity.User;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import java.time.ZoneOffset;
import java.time.Clock;

/**
 * {@link AuthService} 단위 테스트 — 가입·로그인·토큰 갱신.
 *
 * <p>로그인·갱신 결과에는 {@code onboarded}(초기 설정 완료 여부)가 함께 실린다 —
 * 클라이언트는 이 값 하나로 초기 설정으로 보낼지 정한다(FR-IS-01·FR-IS-07).
 */
class AuthServiceTest {

    /** 접속 기록(이슈 #111)이 남는지 확인하기 위한 고정 IP·시각. */
    private static final String CLIENT_IP = "203.0.113.9";

    private static final Clock TEST_CLOCK =
            Clock.fixed(Instant.parse("2026-09-07T12:00:00Z"), ZoneOffset.UTC);

    private AuthService authService;
    private UserRepository userRepository;
    private TokenProvider tokenProvider;
    private SampleDataSeeder sampleDataSeeder;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        tokenProvider = mock(TokenProvider.class);
        sampleDataSeeder = mock(SampleDataSeeder.class);
        authService = new AuthService(userRepository, tokenProvider, sampleDataSeeder, true, TEST_CLOCK);
    }

    @Test
    void signup_success() {
        String email = "user@example.com";
        String password = "password123";
        String name = "User Name";
        UUID userId = UUID.randomUUID();

        when(userRepository.findByEmail(email)).thenReturn(Optional.empty());
        // save() 가 돌려주는 것은 JPA 가 id 를 채운 엔티티다. 단위테스트에서는 리플렉션으로 주입한다.
        User savedUser = User.create(email, name, "hashed");
        assignId(savedUser, userId);
        when(userRepository.save(any(User.class))).thenReturn(savedUser);

        AuthTokens expectedTokens = new AuthTokens("access", "refresh", 1800);
        when(tokenProvider.issue(userId, false)).thenReturn(expectedTokens);

        AuthTokens result = authService.signup(email, password, name, CLIENT_IP);

        assertThat(result.accessToken()).isEqualTo("access");
        assertThat(result.refreshToken()).isEqualTo("refresh");
        verify(userRepository).findByEmail(email);
        verify(userRepository).save(any(User.class));
        verify(tokenProvider).issue(userId, false);
    }

    @Test
    void signup_은_실연동이_없는_동안_샘플_자산을_임시로_시드한다() {
        // 원래 온보딩 2단계는 금융기관에서 실제 자산을 불러와야 한다(이슈 #108). MVP 범위에 실연동이
        // 없어 빈 계정으로 두면 온보딩 2단계부터 홈·X-ray·플랜까지 전부 빈 화면이 된다.
        User savedUser = givenSavedUser();

        authService.signup("user@example.com", "password123", "User Name", CLIENT_IP);

        verify(sampleDataSeeder).seed(savedUser);
    }

    @Test
    void 플래그를_끄면_가입_계정에_샘플을_넣지_않는다() {
        // 실연동이 도착하면 플래그를 끄는 것으로 먼저 분리하고, 그 다음 호출을 걷어낸다.
        AuthService withoutSeed = new AuthService(userRepository, tokenProvider, sampleDataSeeder, false, TEST_CLOCK);
        givenSavedUser();

        withoutSeed.signup("user@example.com", "password123", "User Name", CLIENT_IP);

        verifyNoInteractions(sampleDataSeeder);
    }

    /** 가입이 성공하는 최소 조건 — 중복 없음 + id 가 채워진 저장 결과 + 토큰 발급. */
    private User givenSavedUser() {
        UUID userId = UUID.randomUUID();
        User savedUser = User.create("user@example.com", "User Name", "hashed");
        assignId(savedUser, userId);

        when(userRepository.findByEmail("user@example.com")).thenReturn(Optional.empty());
        when(userRepository.save(any(User.class))).thenReturn(savedUser);
        when(tokenProvider.issue(userId, false)).thenReturn(new AuthTokens("access", "refresh", 1800));

        return savedUser;
    }

    @Test
    void signup_emailAlreadyExists() {
        String email = "user@example.com";
        String password = "password123";
        String name = "User Name";

        User existingUser = User.create(email, name, "hashed");
        when(userRepository.findByEmail(email)).thenReturn(Optional.of(existingUser));

        assertThatThrownBy(() -> authService.signup(email, password, name, CLIENT_IP))
                .isInstanceOf(DuplicateResourceException.class)
                .hasMessage("이미 사용 중인 이메일입니다.");
    }

    /** 명세 §1.3 — 중복 이메일은 409 DUPLICATE_RESOURCE, field 는 email 이어야 한다. */
    @Test
    void signup_emailAlreadyExists_은_409_DUPLICATE_RESOURCE_이고_field_는_email_이다() {
        String email = "user@example.com";
        User existingUser = User.create(email, "User Name", "hashed");
        when(userRepository.findByEmail(email)).thenReturn(Optional.of(existingUser));

        DuplicateResourceException thrown = catchThrowableOfType(
                () -> authService.signup(email, "password123", "User Name", CLIENT_IP),
                DuplicateResourceException.class);

        assertThat(thrown.getStatus()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(thrown.getCode()).isEqualTo("DUPLICATE_RESOURCE");
        assertThat(thrown.getField()).isEqualTo("email");
    }

    @Test
    void signup_emailNull() {
        assertThatThrownBy(() -> authService.signup(null, "password", "name", CLIENT_IP))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("email must not be null");
    }

    @Test
    void signup_passwordNull() {
        assertThatThrownBy(() -> authService.signup("email@example.com", null, "name", CLIENT_IP))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("password must not be null");
    }

    @Test
    void signup_nameNull() {
        assertThatThrownBy(() -> authService.signup("email@example.com", "password", null, CLIENT_IP))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("name must not be null");
    }

    @Test
    void login_success() {
        String email = "user@example.com";
        String password = "password123";
        String passwordHash = new BCryptPasswordEncoder().encode(password);

        User user = User.create(email, "User Name", passwordHash);
        when(userRepository.findByEmail(email)).thenReturn(Optional.of(user));
        when(tokenProvider.issue(user.getId(), false)).thenReturn(new AuthTokens("access", "refresh", 1800));

        AuthResult result = authService.login(email, password, CLIENT_IP);

        assertThat(result.tokens().accessToken()).isEqualTo("access");
        assertThat(result.tokens().refreshToken()).isEqualTo("refresh");
        // 초기 설정을 마치지 않은 계정 — 클라이언트는 초기 설정으로 보낸다.
        assertThat(result.onboarded()).isFalse();
        verify(userRepository).findByEmail(email);
        verify(tokenProvider).issue(user.getId(), false);
    }

    @Test
    void login_초기설정을_마친_사용자는_onboarded_true() {
        String email = "done@example.com";
        String password = "password123";
        String passwordHash = new BCryptPasswordEncoder().encode(password);

        User user = User.create(email, "User Name", passwordHash);
        user.completeOnboarding(Instant.parse("2026-09-01T15:30:00Z"));
        when(userRepository.findByEmail(email)).thenReturn(Optional.of(user));
        when(tokenProvider.issue(user.getId(), false)).thenReturn(new AuthTokens("access", "refresh", 1800));

        assertThat(authService.login(email, password, CLIENT_IP).onboarded()).isTrue();
    }

    @Test
    void login_userNotFound() {
        String email = "user@example.com";
        String password = "password123";

        when(userRepository.findByEmail(email)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.login(email, password, CLIENT_IP))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessage("이메일 또는 비밀번호가 올바르지 않습니다.");
    }

    @Test
    void login_invalidPassword() {
        String email = "user@example.com";
        String password = "password123";
        String wrongPassword = "wrongpassword";
        String passwordHash = new BCryptPasswordEncoder().encode(password);

        User user = User.create(email, "User Name", passwordHash);
        when(userRepository.findByEmail(email)).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> authService.login(email, wrongPassword, CLIENT_IP))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessage("이메일 또는 비밀번호가 올바르지 않습니다.");
    }

    /**
     * 사용자 열거(user enumeration) 방지 회귀 테스트 (이슈 #61) — "없는 이메일"과 "비밀번호 불일치"는
     * 상태코드·에러코드·메시지가 완전히 같아야 공격자가 가입 여부를 추론할 수 없다.
     */
    @Test
    void login_없는_이메일과_틀린_비밀번호는_구분되지_않는_401_이다() {
        String knownEmail = "known@example.com";
        String unknownEmail = "unknown@example.com";
        String correctPassword = "password123";
        String passwordHash = new BCryptPasswordEncoder().encode(correctPassword);

        User user = User.create(knownEmail, "User Name", passwordHash);
        when(userRepository.findByEmail(knownEmail)).thenReturn(Optional.of(user));
        when(userRepository.findByEmail(unknownEmail)).thenReturn(Optional.empty());

        UnauthorizedException wrongPasswordException = catchThrowableOfType(
                () -> authService.login(knownEmail, "wrongpassword", CLIENT_IP), UnauthorizedException.class);
        UnauthorizedException unknownEmailException = catchThrowableOfType(
                () -> authService.login(unknownEmail, correctPassword, CLIENT_IP), UnauthorizedException.class);

        assertThat(wrongPasswordException.getStatus()).isEqualTo(unknownEmailException.getStatus());
        assertThat(wrongPasswordException.getCode()).isEqualTo(unknownEmailException.getCode());
        assertThat(wrongPasswordException.getMessage()).isEqualTo(unknownEmailException.getMessage());
        assertThat(wrongPasswordException.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(wrongPasswordException.getCode()).isEqualTo("UNAUTHORIZED");
    }

    /** 이메일이 없을 때도 더미 해시로 bcrypt 비교를 수행해 findByEmail 호출은 항상 일어나야 한다. */
    @Test
    void login_없는_이메일도_findByEmail_은_호출된다() {
        String unknownEmail = "unknown@example.com";
        when(userRepository.findByEmail(unknownEmail)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.login(unknownEmail, "any-password", CLIENT_IP))
                .isInstanceOf(UnauthorizedException.class);

        verify(userRepository).findByEmail(unknownEmail);
    }

    @Test
    void login_emailNull() {
        assertThatThrownBy(() -> authService.login(null, "password", CLIENT_IP))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("email must not be null");
    }

    @Test
    void login_passwordNull() {
        assertThatThrownBy(() -> authService.login("email@example.com", null, CLIENT_IP))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("password must not be null");
    }

    @Test
    void refreshAccessToken_success() {
        String refreshToken = "refresh_token";
        UUID userId = UUID.randomUUID();

        when(tokenProvider.verifyRefreshToken(refreshToken))
                .thenReturn(Optional.of(new AuthPrincipal(userId, false)));
        when(tokenProvider.issue(userId, false)).thenReturn(new AuthTokens("new_access", "refresh", 1800));

        User user = User.create("user@example.com", "User Name", "hashed");
        user.completeOnboarding(Instant.parse("2026-09-01T15:30:00Z"));
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        AuthResult result = authService.refreshAccessToken(refreshToken, CLIENT_IP);

        assertThat(result.tokens().accessToken()).isEqualTo("new_access");
        assertThat(result.tokens().refreshToken()).isEqualTo(refreshToken);
        assertThat(result.tokens().accessTokenTtlSeconds()).isEqualTo(1800);
        assertThat(result.onboarded()).isTrue();
        verify(tokenProvider).verifyRefreshToken(refreshToken);
        verify(tokenProvider).issue(userId, false);
    }

    /** 토큰은 유효한데 사용자 행이 사라진 경우 — 갱신은 되지만 초기 설정 미완료로 본다. */
    @Test
    void refreshAccessToken_사용자를_찾지_못하면_onboarded_false() {
        String refreshToken = "refresh_token";
        UUID userId = UUID.randomUUID();

        when(tokenProvider.verifyRefreshToken(refreshToken))
                .thenReturn(Optional.of(new AuthPrincipal(userId, false)));
        when(tokenProvider.issue(userId, false)).thenReturn(new AuthTokens("new_access", "refresh", 1800));
        when(userRepository.findById(userId)).thenReturn(Optional.empty());

        assertThat(authService.refreshAccessToken(refreshToken, CLIENT_IP).onboarded()).isFalse();
    }

    @Test
    void refreshAccessToken_invalidToken() {
        String refreshToken = "invalid_token";

        when(tokenProvider.verifyRefreshToken(refreshToken)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.refreshAccessToken(refreshToken, CLIENT_IP))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessage("유효하지 않거나 만료된 리프레시 토큰입니다.");
    }

    /** 명세 §1.3 — 위조된 리프레시 토큰은 401 UNAUTHORIZED 여야 한다. */
    @Test
    void refreshAccessToken_invalidToken_은_401_UNAUTHORIZED_이다() {
        String refreshToken = "invalid_token";
        when(tokenProvider.verifyRefreshToken(refreshToken)).thenReturn(Optional.empty());

        UnauthorizedException thrown = catchThrowableOfType(
                () -> authService.refreshAccessToken(refreshToken, CLIENT_IP),
                UnauthorizedException.class);

        assertThat(thrown.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(thrown.getCode()).isEqualTo("UNAUTHORIZED");
    }

    @Test
    void refreshAccessToken_tokenNull() {
        assertThatThrownBy(() -> authService.refreshAccessToken(null, CLIENT_IP))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("refreshToken must not be null");
    }

    @Test
    void passwordHashingIsNotPlaintext() {
        String password = "password123";
        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
        String passwordHash = encoder.encode(password);

        assertThat(passwordHash).isNotEqualTo(password);
        assertThat(passwordHash).startsWith("$2");
        assertThat(encoder.matches(password, passwordHash)).isTrue();
    }

    /** 단위테스트에서 JPA 가 채우는 UUID 를 리플렉션으로 주입한다. */
    private static void assignId(User user, UUID id) {
        try {
            var field = User.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(user, id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
