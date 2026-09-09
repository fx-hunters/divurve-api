package com.divurve.api.dto.admin;

import com.divurve.domain.event.EconEventAdminService;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * 경제 일정 적재 현황 (이슈 #176).
 *
 * <p>출처를 나눠 낸다 — 공식 파서·AI 추출·시연용 예시를 한 숫자로 합치면 신뢰도 혼합 금지
 * (이슈 #74 제약 4)가 무의미해진다. 시연용 시드가 아직 남아 있는지도 여기서 보인다.
 */
@Schema(description = "경제 일정 적재 현황")
public record AdminEconEventStatusResponse(
        List<SourceStatus> sources,
        Instant checkedAt) {

    public static AdminEconEventStatusResponse from(EconEventAdminService.StatusResult result) {
        return new AdminEconEventStatusResponse(
                result.sources().stream().map(SourceStatus::from).toList(),
                result.checkedAt());
    }

    /**
     * 출처 하나의 현황.
     *
     * @param sourceKind    {@code OFFICIAL_PARSER} · {@code AI_EXTRACTED} · {@code DEMO_SAMPLE}
     * @param total         건수
     * @param lastFetchedAt 마지막 적재 시각 — 화면의 "마지막 갱신"
     * @param lastEventDate 가장 먼 일정 날짜 — 얼마나 앞까지 채워져 있는가
     */
    @Schema(description = "출처별 적재 현황")
    public record SourceStatus(
            String sourceKind, long total, Instant lastFetchedAt, LocalDate lastEventDate) {

        static SourceStatus from(EconEventAdminService.SourceStatus source) {
            return new SourceStatus(
                    source.sourceKind(), source.total(),
                    source.lastFetchedAt(), source.lastEventDate());
        }
    }
}
