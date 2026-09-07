package com.divurve.api.controller;

import com.divurve.api.dto.auth.LoginRequest;
import com.divurve.api.dto.auth.RefreshRequest;
import com.divurve.api.dto.auth.SignupRequest;
import com.divurve.api.dto.auth.TokenResponse;
import com.divurve.common.architecture.WebAdapter;
import com.divurve.common.response.ApiResponse;
import com.divurve.domain.auth.AuthDemoService;
import com.divurve.domain.auth.AuthService;
import com.divurve.domain.auth.AuthService.AuthResult;
import com.divurve.domain.port.AuthTokens;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 인증 엔드포인트 (API 명세 v2 §3 인증·초기 설정 표, 이슈 #22).
 * signup/login/refresh 본구현 + 샘플 계정 발급. 인증 방식은 JWT(액세스 30분·리프레시 14일), Spring Security 미사용.
 *
 * <p>응답에는 {@code onboarded} 가 함께 실린다 — 클라이언트는 이 값 하나로 초기 설정 이동 여부를 정한다
 * (FR-IS-01·FR-IS-07).
 */
@WebAdapter
@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "Auth", description = "인증 (회원가입·로그인·토큰 갱신·샘플 계정)")
public class AuthController {

    private final AuthService authService;
    private final AuthDemoService authDemoService;

    public AuthController(AuthService authService, AuthDemoService authDemoService) {
        this.authService = authService;
        this.authDemoService = authDemoService;
    }

    @Operation(summary = "회원가입",
            description = "이메일·비밀번호·이름으로 계정을 만든다. 이메일은 고유해야 한다. "
                    + "가입 직후에는 초기 설정을 하지 않았으므로 onboarded=false 다.\n\n"
                    + "**MVP 한시 동작(이슈 #108)** — 원래 온보딩 2단계는 금융기관에서 사용자의 실제 자산을 "
                    + "불러와야 하지만 실연동이 범위 밖이라, 가입 직후 데모 계정과 같은 샘플 자산·목표·"
                    + "진단 응답이 임시로 채워진다. 온보딩 2단계는 GET /api/v1/xray 로 그 값을 그대로 "
                    + "표시하면 된다. 실연동이 도착하면 이 동작은 사라진다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "가입 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400", description = "VALIDATION_FAILED — 입력 누락"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "409", description = "DUPLICATE_RESOURCE — 이미 가입된 이메일")
    })
    @PostMapping("/signup")
    public ApiResponse<TokenResponse> signup(@Valid @RequestBody SignupRequest request) {
        AuthTokens tokens = authService.signup(request.email(), request.password(), request.name());
        return ApiResponse.of(toTokenResponse(tokens, false, false));
    }

    @Operation(summary = "로그인",
            description = "이메일과 비밀번호로 로그인한다. 응답의 onboarded 가 false 면 초기 설정으로 보낸다(FR-IS-01).")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "로그인 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400", description = "VALIDATION_FAILED — 입력 누락"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "401", description = "UNAUTHORIZED — 이메일 미존재 또는 비밀번호 불일치")
    })
    @PostMapping("/login")
    public ApiResponse<TokenResponse> login(@Valid @RequestBody LoginRequest request) {
        AuthResult result = authService.login(request.email(), request.password());
        return ApiResponse.of(toTokenResponse(result.tokens(), false, result.onboarded()));
    }

    @Operation(summary = "토큰 갱신",
            description = "리프레시 토큰으로 새 액세스 토큰을 받는다. 리프레시 토큰은 재사용 가능하다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "갱신 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400", description = "VALIDATION_FAILED — 토큰 누락"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "401", description = "UNAUTHORIZED — 만료·위조된 리프레시 토큰")
    })
    @PostMapping("/refresh")
    public ApiResponse<TokenResponse> refresh(@Valid @RequestBody RefreshRequest request) {
        AuthResult result = authService.refreshAccessToken(request.refreshToken());
        return ApiResponse.of(toTokenResponse(result.tokens(), false, result.onboarded()));
    }

    @Operation(summary = "샘플(데모) 계정 발급",
            description = "회원가입 없이 샘플 데이터가 채워진 계정을 만들고 토큰을 발급한다(FR-IS-09). "
                    + "샘플 데이터가 이미 있으므로 onboarded=true 다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "발급 성공")
    })
    @PostMapping("/demo")
    public ApiResponse<TokenResponse> demo() {
        AuthTokens tokens = authDemoService.createDemoSession();
        return ApiResponse.of(toTokenResponse(tokens, true, true));
    }

    private TokenResponse toTokenResponse(AuthTokens tokens, boolean isDemo, boolean onboarded) {
        return new TokenResponse(
                tokens.accessToken(), tokens.refreshToken(), tokens.accessTokenTtlSeconds(), isDemo, onboarded);
    }
}
