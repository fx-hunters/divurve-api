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
 * 이슈 #69 — {@code Content-Type} 이 없거나 지원하지 않는 요청이 실제 Spring MVC 요청 경로를 타고도
 * 500 이 아니라 400 으로 응답하는지 확인한다.
 *
 * <p>{@link GlobalExceptionHandlerTest} 는 {@code GlobalExceptionHandler} 메서드를 직접 호출하므로
 * "Spring 이 실제로 {@code HttpMediaTypeNotSupportedException} 을 던지는가"는 검증하지 못한다.
 * 이 테스트는 {@code @WebMvcTest} 로 실제 디스패처를 태워 그 경로 전체를 확인한다.
 *
 * <p>{@link AuthController#login} 은 {@code @CurrentUser} 인증이 필요 없는 유일한 계열의 엔드포인트라
 * 슬라이스 테스트 대상으로 골랐다 — 인증 인터셉터·리졸버 배선 없이도 Content-Type 판단 지점(요청 본문
 * 바인딩 이전)에 도달한다.
 *
 * <p>{@code Accept} 헤더 불일치(406, {@link org.springframework.web.HttpMediaTypeNotAcceptableException})는
 * 여기서 다루지 않는다 — 직접 확인 결과 이 예외는 응답 <b>쓰기</b> 단계에서 발생해 Spring 기본 리졸버가
 * 이미 순수 406(본문 없음)으로 정상 종료시키고 있었다({@code GlobalExceptionHandler} 의 판단 근거 참고).
 * 500 으로 새지 않으므로 이슈 #69 범위가 아니다.
 */
@WebMvcTest(AuthController.class)
class ContentTypeErrorHandlingMockMvcTest {

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
    void Content_Type_헤더가_없으면_400_VALIDATION_FAILED_이다() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                        .content("{\"email\":\"a@b.com\",\"password\":\"pw\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    @Test
    void Content_Type_이_지원하지_않는_값이면_400_VALIDATION_FAILED_이다() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("{\"email\":\"a@b.com\",\"password\":\"pw\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    @Test
    void Content_Type_이_application_x_www_form_urlencoded_이면_400_VALIDATION_FAILED_이다() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .content("scenario_code=equity_down_krw_weak"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }
}
