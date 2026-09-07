package com.divurve.api.dto.admin;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;

/**
 * FRED 거시지표 갱신 요청 (이슈 #111).
 *
 * @param seriesIds 조회할 FRED 시리즈 id
 */
public record AdminMacroRefreshRequest(
        @Schema(example = "[\"DGS10\"]")
        @NotEmpty(message = "조회할 시리즈를 지정해야 합니다.") List<String> seriesIds) {
}
