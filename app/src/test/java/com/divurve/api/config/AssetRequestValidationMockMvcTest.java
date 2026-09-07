package com.divurve.api.config;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.divurve.api.controller.AssetController;
import com.divurve.domain.holding.DepositService;
import com.divurve.domain.holding.HoldingService;
import com.divurve.domain.holding.KrwAssetService;
import com.divurve.domain.port.AuthPrincipal;
import com.divurve.domain.port.DataSourceStatus;
import com.divurve.domain.port.TokenProvider;
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
 * 이슈 #75 — {@code POST /holdings}·{@code POST /deposits} 의 필수 필드({@code currency_code} 등)
 * 누락이 {@code @Valid} 로 실제 요청 경로에서 400 으로 응답하는지 확인한다.
 *
 * <p>{@code currency_code} 가 없으면 매입일이 함께 오는 경우 {@code PurchaseFxRateResolver} 가
 * {@code null.toUpperCase()} 로 NPE 를 던져 500 으로 샜다 — 여기서는 매입일 유무와 무관하게 컨트롤러
 * 경계에서 먼저 막히는지를 확인한다.
 */
@WebMvcTest(AssetController.class)
class AssetRequestValidationMockMvcTest {

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
    private HoldingService holdingService;

    @MockBean
    private DepositService depositService;

    @MockBean
    private KrwAssetService krwAssetService;

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
    void 종목_추가_통화코드가_없으면_400_VALIDATION_FAILED_이고_서비스는_호출되지_않는다() throws Exception {
        mockMvc.perform(post("/api/v1/holdings")
                        .header("Authorization", AUTH_HEADER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ticker\":\"AAPL\",\"quantity\":10,\"avg_price\":150.0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.field").value("currency_code"));

        verifyNoInteractions(holdingService);
    }

    @Test
    void 종목_추가_티커가_없으면_400_VALIDATION_FAILED_이다() throws Exception {
        mockMvc.perform(post("/api/v1/holdings")
                        .header("Authorization", AUTH_HEADER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currency_code\":\"USD\",\"quantity\":10,\"avg_price\":150.0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.field").value("ticker"));
    }

    @Test
    void 예금_추가_통화코드가_없으면_400_VALIDATION_FAILED_이고_서비스는_호출되지_않는다() throws Exception {
        mockMvc.perform(post("/api/v1/deposits")
                        .header("Authorization", AUTH_HEADER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":1000.0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.field").value("currency_code"));

        verifyNoInteractions(depositService);
    }

    @Test
    void 예금_추가_금액이_없으면_400_VALIDATION_FAILED_이다() throws Exception {
        mockMvc.perform(post("/api/v1/deposits")
                        .header("Authorization", AUTH_HEADER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currency_code\":\"USD\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.field").value("amount"));
    }
}
