package com.divurve.api.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.divurve.common.exception.InvalidRequestException;
import com.divurve.domain.goal.GoalCreateCommand;
import com.divurve.domain.goal.GoalService;
import com.divurve.domain.port.AuthPrincipal;
import com.divurve.domain.port.DataSourceStatus;
import com.divurve.domain.port.TokenProvider;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import com.divurve.domain.user.AdminAccessService;

/**
 * {@code POST /api/v1/goals} 입력 검증이 실제 Spring MVC 요청 경로를 타는지 확인한다(이슈 #77).
 *
 * <p>{@link com.divurve.domain.goal.GoalServiceTest} 는 서비스 메서드를 직접 호출하므로
 * {@code @Valid} 가 컨트롤러 경계에서 실제로 걸리는지는 검증하지 못한다.
 * {@code ContentTypeErrorHandlingMockMvcTest} · {@code SignupRequestValidationTest} 의 패턴을 따라
 * {@code @WebMvcTest} 로 실제 디스패처를 태운다.
 *
 * <p>{@code @CurrentUser} 가 인증을 요구하므로 {@link TokenProvider} 를 목업해 유효 토큰을
 * 흉내 낸다 — {@link MetaDataStateAdvice} 가 생성자 주입받는 {@link DataSourceStatus} 도
 * 함께 목업해야 컨텍스트가 로딩된다.
 */
@WebMvcTest(GoalController.class)
class GoalControllerValidationMockMvcTest {

    private static final String BEARER_TOKEN = "test-token";

    @Autowired
    private MockMvc mockMvc;

    /**
     * {@code WebAuthConfig} 가 관리자 리졸버를 배선하면서 이 빈을 요구한다 (이슈 #111).
     * 이 테스트는 관리자 경로를 다루지 않지만, 없으면 컨텍스트 자체가 뜨지 않는다.
     */
    @MockBean
    private AdminAccessService adminAccessService;

    @MockBean
    private GoalService goalService;

    @MockBean
    private TokenProvider tokenProvider;

    @MockBean
    private DataSourceStatus dataSourceStatus;

    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        when(tokenProvider.verify(BEARER_TOKEN)).thenReturn(Optional.of(new AuthPrincipal(userId, false)));
    }

    private String requestBody(String name, double targetAmount, String currencyCode,
            String targetDate, String purpose) {
        return """
                {
                  "name": "%s",
                  "kind": "deadline",
                  "purpose": "%s",
                  "currency_code": "%s",
                  "target_amount": %s,
                  "target_date": "%s",
                  "budget_amount": 0,
                  "budget_currency_code": "KRW",
                  "is_speculative": false
                }
                """.formatted(name, purpose, currencyCode, targetAmount, targetDate);
    }

    @Test
    @DisplayName("name 이 빈 문자열이면 400 VALIDATION_FAILED, field=name")
    void 이름이_비어있으면_400이다() throws Exception {
        mockMvc.perform(post("/api/v1/goals")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + BEARER_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody("", 10000.0, "USD", "2026-12-31", "TRAVEL")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.field").value("name"));
    }

    /**
     * {@code target_amount}·{@code currency_code}·{@code purpose}·{@code target_date} 검증은
     * {@code GoalService}(여기서는 목업)에 있다 — 이 슬라이스 테스트의 목적은 "서비스가 던진
     * {@code InvalidRequestException} 이 실제 디스패처를 거쳐 400 envelope 으로 나가는가"다.
     * 각 규칙 자체의 단위 테스트는 {@code GoalServiceTest} 가 맡는다.
     */
    private void givenGoalServiceRejects(InvalidRequestException exception) {
        when(goalService.create(any(), any(GoalCreateCommand.class))).thenThrow(exception);
    }

    @Test
    @DisplayName("target_amount 가 0 이면 400 VALIDATION_FAILED, field=target_amount")
    void 목표금액이_0이면_400이다() throws Exception {
        givenGoalServiceRejects(new InvalidRequestException("목표 금액은 0보다 커야 합니다.", "target_amount"));

        mockMvc.perform(post("/api/v1/goals")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + BEARER_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody("목표", 0, "USD", "2026-12-31", "TRAVEL")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.field").value("target_amount"));
    }

    @Test
    @DisplayName("지원하지 않는 통화코드(XYZ)면 서비스 계층 검증이 400 을 낸다")
    void 지원하지_않는_통화코드는_400이다() throws Exception {
        givenGoalServiceRejects(
                new InvalidRequestException("환율 조회가 지원되지 않는 통화입니다: XYZ", "currency_code"));

        mockMvc.perform(post("/api/v1/goals")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + BEARER_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody("목표", 10000.0, "XYZ", "2026-12-31", "TRAVEL")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.field").value("currency_code"));
    }

    @Test
    @DisplayName("인증 토큰이 없으면 401 이라 검증 이전에 이미 막힌다")
    void 토큰이_없으면_401이다() throws Exception {
        mockMvc.perform(post("/api/v1/goals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody("목표", 10000.0, "USD", "2026-12-31", "TRAVEL")))
                .andExpect(status().isUnauthorized());
    }
}
