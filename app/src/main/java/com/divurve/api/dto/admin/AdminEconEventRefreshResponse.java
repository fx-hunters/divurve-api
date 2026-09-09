package com.divurve.api.dto.admin;

import com.divurve.domain.event.EconEventAdminService;
import com.divurve.domain.event.OfficialEventIngestionService;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

/**
 * 경제 일정 공식 캘린더 수동 갱신 결과 (이슈 #176).
 *
 * <p>집계를 그대로 낸다 — 성공 여부만 알려주면 이 화면의 목적이 사라진다. {@code unknown} 이
 * 크면 대조표의 지표명이 캘린더가 쓰는 이름과 어긋난다는 뜻이고, {@code scheduled} 가 0 이면
 * 캘린더가 일정을 주지 않았다는 뜻이다. 둘은 전혀 다른 문제다.
 */
@Schema(description = "공식 캘린더 수동 갱신 결과")
public record AdminEconEventRefreshResponse(
        @Schema(description = "캘린더가 준 일정 수. 실패면 null") Integer scheduled,
        @Schema(description = "새로 저장한 수. 실패면 null") Integer inserted,
        @Schema(description = "낮은 신뢰도 행을 공식으로 승격한 수. 실패면 null") Integer promoted,
        @Schema(description = "이미 공식이라 그대로 둔 수. 실패면 null") Integer skipped,
        @Schema(description = "대조표에 없어 저장하지 않은 수. 실패면 null") Integer unknown,
        @Schema(description = "실패 사유. 성공이면 null") String failureReason,
        Instant refreshedAt,
        long elapsedMs) {

    public static AdminEconEventRefreshResponse from(EconEventAdminService.RefreshResult result) {
        OfficialEventIngestionService.IngestionReport report = result.report();
        return new AdminEconEventRefreshResponse(
                report == null ? null : report.scheduled(),
                report == null ? null : report.inserted(),
                report == null ? null : report.promoted(),
                report == null ? null : report.skipped(),
                report == null ? null : report.unknown(),
                result.failureReason(),
                result.refreshedAt(),
                result.elapsedMs());
    }
}
