package com.divurve.api.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.divurve.api.dto.auth.LoginRequest;
import com.divurve.api.dto.auth.RefreshRequest;
import com.divurve.api.dto.auth.SignupRequest;
import com.divurve.api.dto.auth.TokenResponse;
import com.divurve.common.response.ApiResponse;
import com.divurve.domain.auth.AuthDemoService;
import com.divurve.domain.auth.AuthService;
import com.divurve.domain.auth.AuthService.AuthResult;
import com.divurve.domain.port.AuthTokens;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * {@link AuthController} 매핑 검증 — signup/login/refresh/demo.
 * AuthTokens → TokenResponse(data/meta 래핑, {@code is_demo}·{@code onboarded} 설정).
 *
 * <p>{@code onboarded} 는 클라이언트가 초기 설정으로 보낼지 정하는 유일한 근거다(FR-IS-01·FR-IS-07) —
 * 가입 직후 false, 샘플 계정 true, 로그인·갱신은 도메인이 준 값 그대로임을 고정한다.
 */
@ExtendWith(MockitoExtension.class)
class AuthControllerTest {

    @Mock
    private AuthService authService;

    @Mock
    private AuthDemoService authDemoService;

    /** 접속 IP 는 @ClientIp 리졸버가 채워 넣는 값이다 (이슈 #111). 컨트롤러는 그대로 넘기기만 한다. */
    private static final String CLIENT_IP = "203.0.113.42";

    private AuthController controller() {
        return new AuthController(authService, authDemoService);
    }

    @Test
    void 가입_직후에는_onboarded_가_false_다() {
        SignupRequest request = new SignupRequest("user@example.com", "password123", "User Name");
        when(authService.signup("user@example.com", "password123", "User Name", CLIENT_IP))
                .thenReturn(new AuthTokens("access-token", "refresh-token", 1800L));

        ApiResponse<TokenResponse> response = controller().signup(CLIENT_IP, request);

        assertThat(response.meta()).isNotNull();
        TokenResponse body = response.data();
        assertThat(body.accessToken()).isEqualTo("access-token");
        assertThat(body.refreshToken()).isEqualTo("refresh-token");
        assertThat(body.expiresIn()).isEqualTo(1800L);
        assertThat(body.isDemo()).isFalse();
        assertThat(body.onboarded()).isFalse();
    }

    @Test
    void 로그인_응답은_초기_설정_완료_여부를_그대로_싣는다() {
        when(authService.login("user@example.com", "password123", CLIENT_IP))
                .thenReturn(new AuthResult(new AuthTokens("access-token", "refresh-token", 1800L), true));

        TokenResponse body = controller().login(CLIENT_IP, new LoginRequest("user@example.com", "password123")).data();

        assertThat(body.accessToken()).isEqualTo("access-token");
        assertThat(body.isDemo()).isFalse();
        assertThat(body.onboarded()).isTrue();
    }

    @Test
    void 초기_설정을_마치지_않은_사용자는_onboarded_false_로_로그인한다() {
        when(authService.login("new@example.com", "password123", CLIENT_IP))
                .thenReturn(new AuthResult(new AuthTokens("access-token", "refresh-token", 1800L), false));

        TokenResponse body = controller().login(CLIENT_IP, new LoginRequest("new@example.com", "password123")).data();

        assertThat(body.onboarded()).isFalse();
    }

    @Test
    void 토큰_갱신도_초기_설정_완료_여부를_함께_돌려준다() {
        when(authService.refreshAccessToken("refresh-token", CLIENT_IP))
                .thenReturn(new AuthResult(new AuthTokens("new-access", "refresh-token", 1800L), true));

        TokenResponse body = controller().refresh(CLIENT_IP, new RefreshRequest("refresh-token")).data();

        assertThat(body.accessToken()).isEqualTo("new-access");
        assertThat(body.refreshToken()).isEqualTo("refresh-token");
        assertThat(body.onboarded()).isTrue();
    }

    @Test
    void 샘플_계정은_샘플_데이터가_이미_있으므로_onboarded_true_다() {
        when(authDemoService.createDemoSession(CLIENT_IP))
                .thenReturn(new AuthTokens("demo-access", "demo-refresh", 1800L));

        TokenResponse body = controller().demo(CLIENT_IP).data();

        assertThat(body.isDemo()).isTrue();
        assertThat(body.onboarded()).isTrue();
    }
}
