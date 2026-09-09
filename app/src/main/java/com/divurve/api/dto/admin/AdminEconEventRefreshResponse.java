package com.divurve.api.dto.admin;

import com.divurve.domain.event.EconEventAdminService;
import com.divurve.domain.event.OfficialEventIngestionService;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.time.LocalDate;

/**
 * 경제 일정 공식 캘린더 수동 갱신 결과 (이슈 #176).
 *
 * <p>집계를 그대로 낸다 — 성공 여부만 알려주면 이 화면의 목적이 사라진다.
 * {@code failed_calendars} 가 0 이 아니면 그 지표의 조회가 실패했다는 뜻이고,
 * {@code scheduled} 가 0 이면 캘린더가 일정을 주지 않았다는 뜻이다. 둘은 전혀 다른 문제다.
 *
 * <p>{@code central_bank_calendar_through} 는 중앙은행 회의 표가 언제까지 유효한지를 말한다
 * (이슈 #191). 이 날짜가 가까워지면 표를 갱신해야 하며, 방치하면 회의 일정만 조용히 빈다.
 */
@Schema(description = "공식 캘린더 수동 갱신 결과")
public record AdminEconEventRefreshResponse(
        @Schema(description = "캘린더가 준 일정 수. 실패면 null") Integer scheduled,
        @Schema(description = "새로 저장한 수. 실패면 null") Integer inserted,
        @Schema(description = "낮은 신뢰도 행을 공식으로 승격한 수. 실패면 null") Integer promoted,
        @Schema(description = "이미 공식이라 그대로 둔 수. 실패면 null") Integer skipped,
        @Schema(description = "조회가 실패한 지표 수. 실패면 null") Integer failedCalendars,
        @Schema(description = "중앙은행 회의 표가 유효한 마지막 날. 실패면 null")
        LocalDate centralBankCalendarThrough,
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
                report == null ? null : report.failedCalendars(),
                report == null ? null : report.centralBankCalendarThrough(),
                result.failureReason(),
                result.refreshedAt(),
                result.elapsedMs());
    }
}
