package com.divurve.api.dto.admin;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

/**
 * 비정형 원문 정형화 미리보기 요청 (이슈 #111).
 *
 * @param sourceUrl 출처 URL. 손으로 붙여넣은 원문에는 없을 수 있어 선택이다
 * @param text      원문 전문
 */
public record AdminExtractPreviewRequest(
        @Schema(example = "https://example.com/news/1") String sourceUrl,
        @NotBlank(message = "원문을 입력해야 합니다.") String text) {
}
