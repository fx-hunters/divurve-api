package com.divurve.api.config;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.divurve.api.controller.MeController;
import com.divurve.domain.port.AuthPrincipal;
import com.divurve.domain.port.DataSourceStatus;
import com.divurve.domain.port.TokenProvider;
import com.divurve.domain.settings.RiskProfileService;
import com.divurve.domain.settings.UserSettingsService;
import com.divurve.domain.user.UserProfileService;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import com.divurve.domain.user.AdminAccessService;

/**
 * 이슈 #75(②) — {@code PUT /me} 에 {@code name} 없이 (또는 빈 문자열로) 요청하면 이전에는
 * {@code users.name} NOT NULL 위반이 그대로 DB 까지 내려가 409 {@code DUPLICATE_RESOURCE} 로
 * 잘못 응답했다. {@code @NotBlank} + {@code @Valid} 를 컨트롤러 경계에 두어 400
 * {@code VALIDATION_FAILED} 로 바뀌었는지, 그리고 서비스가 아예 호출되지 않는지 확인한다.
 */
@WebMvcTest(MeController.class)
class MeRequestValidationMockMvcTest {

    private static final String AUTH_HEADER = "Bearer valid-token";

    @Autowired
    private MockMvc mockMvc;

    /**
     * {@code WebAuthConfig} 가 관리자 리졸버를 배선하면서 이 빈을 요구한다 (이슈 #111).
     * 이 테스트는 관리자 경로를 다루지 않지만, 없으면 컨텍스트 자체가 뜨지 않는다.
     */
    @MockBean
    private AdminAccessService adminAccessService;

    @MockBean
    private UserProfileService userProfileService;

    @MockBean
    private RiskProfileService riskProfileService;

    @MockBean
    private UserSettingsService userSettingsService;

    @MockBean
    private TokenProvider tokenProvider;

    @MockBean
    private DataSourceStatus dataSourceStatus;

    @BeforeEach
    void authenticate() {
        when(tokenProvider.verify(anyString()))
                .thenReturn(Optional.of(new AuthPrincipal(UUID.randomUUID(), false)));
    }

    @Test
    void 이름이_없으면_409_가_아니라_400_VALIDATION_FAILED_이고_서비스는_호출되지_않는다() throws Exception {
        mockMvc.perform(put("/api/v1/me")
                        .header("Authorization", AUTH_HEADER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.field").value("name"));

        verifyNoInteractions(userProfileService);
    }

    @Test
    void 이름이_공백만_있으면_400_VALIDATION_FAILED_이다() throws Exception {
        mockMvc.perform(put("/api/v1/me")
                        .header("Authorization", AUTH_HEADER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"   \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.field").value("name"));
    }
}
