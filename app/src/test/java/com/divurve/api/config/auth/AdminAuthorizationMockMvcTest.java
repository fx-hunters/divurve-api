package com.divurve.api.config.auth;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.divurve.api.controller.admin.AdminCurrencyController;
import com.divurve.common.exception.ForbiddenException;
import com.divurve.domain.master.MasterDataService;
import com.divurve.domain.port.AuthPrincipal;
import com.divurve.domain.port.DataSourceStatus;
import com.divurve.domain.port.TokenProvider;
import com.divurve.domain.user.AdminAccessService;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 관리자 경로의 인증·인가가 HTTP 로 실제 강제되는지 확인한다 (이슈 #111).
 *
 * <p>단위 테스트로는 잡히지 않는 것을 본다 — 컨트롤러 시그니처에 {@code @CurrentAdmin} 이 정말
 * 붙어 있는가, {@code WebAuthConfig} 가 리졸버를 정말 등록했는가. 둘 중 하나만 빠져도 관리자
 * 엔드포인트가 <b>무인증으로 열린다</b>. 그 실패를 여기서 막는다.
 */
@WebMvcTest(AdminCurrencyController.class)
@DisplayName("관리자 경로 인가")
class AdminAuthorizationMockMvcTest {

    private static final UUID ADMIN_ID = UUID.randomUUID();
    private static final String BEARER = "test-token";

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AdminAccessService adminAccessService;

    @MockBean
    private MasterDataService masterDataService;

    @MockBean
    private TokenProvider tokenProvider;

    @MockBean
    private DataSourceStatus dataSourceStatus;

    private void givenAuthenticated() {
        when(tokenProvider.verify(BEARER))
                .thenReturn(Optional.of(new AuthPrincipal(ADMIN_ID, false)));
    }

    @Test
    @DisplayName("토큰이 없으면 401 이고 인가 검사까지 가지 않는다")
    void noToken_Unauthorized() throws Exception {
        mockMvc.perform(get("/api/v1/admin/currencies"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));

        verify(adminAccessService, never()).requireAdmin(any());
    }

    @Test
    @DisplayName("일반 사용자는 403 이다 — 토큰을 갱신해도 해결되지 않는다")
    void normalUser_Forbidden() throws Exception {
        givenAuthenticated();
        doThrow(new ForbiddenException("관리자 권한이 필요합니다."))
                .when(adminAccessService).requireAdmin(ADMIN_ID);

        mockMvc.perform(get("/api/v1/admin/currencies").header("Authorization", "Bearer " + BEARER))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    @Test
    @DisplayName("관리자는 200 이고 data/meta 로 응답한다")
    void admin_Ok() throws Exception {
        givenAuthenticated();
        when(masterDataService.listAllCurrencies()).thenReturn(List.of());
        when(masterDataService.listCurrencyPairs()).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/admin/currencies").header("Authorization", "Bearer " + BEARER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.currencies").isArray())
                .andExpect(jsonPath("$.data.currency_pairs").isArray())
                .andExpect(jsonPath("$.meta").exists());

        verify(adminAccessService).requireAdmin(ADMIN_ID);
    }

    @Test
    @DisplayName("무효한 토큰은 401 이다 — 인터셉터가 컨텍스트를 채우지 않는다")
    void invalidToken_Unauthorized() throws Exception {
        when(tokenProvider.verify("bad-token")).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/v1/admin/currencies").header("Authorization", "Bearer bad-token"))
                .andExpect(status().isUnauthorized());

        verify(adminAccessService, never()).requireAdmin(any());
    }
}
