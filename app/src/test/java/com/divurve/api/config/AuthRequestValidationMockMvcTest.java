package com.divurve.api.config;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.divurve.api.controller.AuthController;
import com.divurve.domain.auth.AuthDemoService;
import com.divurve.domain.auth.AuthService;
import com.divurve.domain.port.DataSourceStatus;
import com.divurve.domain.port.TokenProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import com.divurve.domain.user.AdminAccessService;

/**
 * 이슈 #75 — {@code POST /auth/login}·{@code POST /auth/refresh} 의 필수 필드 누락이
 * {@code @Valid} 로 실제 Spring MVC 요청 경로에서 400 으로 응답하는지 확인한다.
 *
 * <p>{@link com.divurve.domain.auth.AuthServiceTest} 등 서비스 단위 테스트는 컨트롤러에 {@code @Valid}
 * 가 실제로 붙어 있는지를 검증하지 못한다 — DTO 를 직접 만들어 서비스나 검증기를 호출하는 방식이라
 * 컨트롤러 바인딩 단계를 건너뛰기 때문이다. 여기서는 {@code @WebMvcTest} 로 실제 디스패처를 태워
 * "필수 필드가 없는 요청이 서비스까지 내려가지 않고 컨트롤러 경계에서 막히는가"를 확인한다.
 */
@WebMvcTest(AuthController.class)
class AuthRequestValidationMockMvcTest {

    @Autowired
    private MockMvc mockMvc;

    /**
     * {@code WebAuthConfig} 가 관리자 리졸버를 배선하면서 이 빈을 요구한다 (이슈 #111).
     * 이 테스트는 관리자 경로를 다루지 않지만, 없으면 컨텍스트 자체가 뜨지 않는다.
     */
    @MockBean
    private AdminAccessService adminAccessService;

    @MockBean
    private AuthService authService;

    @MockBean
    private AuthDemoService authDemoService;

    @MockBean
    private TokenProvider tokenProvider;

    @MockBean
    private DataSourceStatus dataSourceStatus;

    @Test
    void 로그인_이메일이_없으면_400_VALIDATION_FAILED_이고_서비스는_호출되지_않는다() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"secret123\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.field").value("email"));

        org.mockito.Mockito.verifyNoInteractions(authService);
    }

    @Test
    void 로그인_비밀번호가_공백이면_400_VALIDATION_FAILED_이다() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"user@example.com\",\"password\":\"  \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.field").value("password"));
    }

    @Test
    void 리프레시_토큰이_없으면_400_VALIDATION_FAILED_이고_서비스는_호출되지_않는다() throws Exception {
        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.field").value("refresh_token"));

        org.mockito.Mockito.verifyNoInteractions(authService);
    }
}
