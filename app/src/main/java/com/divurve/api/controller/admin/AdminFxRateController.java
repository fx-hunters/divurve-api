package com.divurve.api.controller.admin;

import com.divurve.api.config.auth.CurrentAdmin;
import com.divurve.api.dto.admin.AdminFxRateBackfillResponse;
import com.divurve.api.dto.admin.AdminFxRateCoverageResponse;
import com.divurve.api.dto.admin.AdminFxRateSeriesResponse;
import com.divurve.api.dto.admin.AdminFxRateStatusResponse;
import com.divurve.api.dto.admin.AdminMacroRefreshRequest;
import com.divurve.api.dto.admin.AdminMacroRefreshResponse;
import com.divurve.api.dto.admin.AdminRefreshResponse;
import com.divurve.common.architecture.WebAdapter;
import com.divurve.common.response.ApiResponse;
import com.divurve.domain.fx.FxRateGapService;
import com.divurve.domain.fx.FxRateQueryService;
import com.divurve.domain.fx.FxRateRefreshService;
import com.divurve.domain.fx.FxRateStatusService;
import com.divurve.domain.macro.MacroRefreshService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 관리자 환율 차트·수동 갱신 (이슈 #111).
 *
 * <p>갱신이 {@code POST} 인 이유 — 외부 API 를 호출하고 DB 를 바꾼다. 멱등하지만 안전하지 않다.
 *
 * <p>갱신 응답에는 반영 건수·소요 시간·실패 사유가 값으로 담긴다. 성공 여부만 알려 주면
 * "갱신했는데 왜 값이 그대로인가" 를 화면에서 알 수 없다.
 *
 * <p>구멍 조회·백필이 함께 있다 (이슈 #116). 읽기 경로가 저장분을 신뢰하는 조건이 "구간이
 * 완전한가" 이므로, 그 조건을 눈으로 확인하고 손으로 고칠 수 있어야 전환이 운영 가능해진다.
 */
@WebAdapter
@RestController
@RequestMapping("/api/v1/admin")
@Tag(name = "Admin - FX Rates", description = "관리자: 환율 시계열 조회와 ECOS·FRED 수동 갱신")
public class AdminFxRateController {

    /** 차트 기본 조회 구간 — 1년. 지정하지 않으면 이 범위를 본다. */
    static final int DEFAULT_RANGE_DAYS = 365;

    /** 갱신 시 거슬러 올라가는 기본 달력일 수. ECOS 정정 반영을 위해 2주를 다시 받는다. */
    static final int DEFAULT_REFRESH_LOOKBACK_DAYS = 14;

    private final FxRateQueryService fxRateQueryService;
    private final FxRateRefreshService fxRateRefreshService;
    private final FxRateGapService fxRateGapService;
    private final FxRateStatusService fxRateStatusService;
    private final MacroRefreshService macroRefreshService;
    private final Clock clock;

    public AdminFxRateController(
            FxRateQueryService fxRateQueryService,
            FxRateRefreshService fxRateRefreshService,
            FxRateGapService fxRateGapService,
            FxRateStatusService fxRateStatusService,
            MacroRefreshService macroRefreshService,
            Clock clock) {
        this.fxRateQueryService = fxRateQueryService;
        this.fxRateRefreshService = fxRateRefreshService;
        this.fxRateGapService = fxRateGapService;
        this.fxRateStatusService = fxRateStatusService;
        this.macroRefreshService = macroRefreshService;
        this.clock = clock;
    }

    @Operation(summary = "환율 시계열 (차트용)",
            description = "관측은 영업일에만 존재하므로 요청 구간보다 개수가 적다. 빠진 날짜를 채우지 않는다.")
    @GetMapping("/fx-rates")
    public ApiResponse<AdminFxRateSeriesResponse> series(
            @CurrentAdmin UUID adminId,
            @RequestParam("pair_code") String pairCode,
            @RequestParam(value = "from", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(value = "to", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(value = "rate_type", defaultValue = "mid") String rateType) {
        LocalDate end = to == null ? LocalDate.now(clock) : to;
        LocalDate start = from == null ? end.minusDays(DEFAULT_RANGE_DAYS) : from;
        return ApiResponse.of(AdminFxRateSeriesResponse.from(
                fxRateQueryService.series(pairCode, start, end, rateType)));
    }

    @Operation(summary = "환율 적재 현황 (마지막 갱신 시각)",
            description = "화면 진입만으로 마지막 갱신 시각을 보여 주기 위한 읽기 전용 조회다. "
                    + "갱신을 트리거하지 않는다. 스케줄러가 돌린 갱신도 같은 값에 반영된다 — "
                    + "근거가 fx_rates.fetched_at 이라 적재 경로를 가리지 않는다. "
                    + "거시지표는 저장하지 않으므로 last_refreshed_at 이 비어 있다.")
    @GetMapping("/fx-rates/status")
    public ApiResponse<AdminFxRateStatusResponse> status(@CurrentAdmin UUID adminId) {
        return ApiResponse.of(AdminFxRateStatusResponse.from(fxRateStatusService.status()));
    }

    @Operation(summary = "ECOS 환율 수동 갱신",
            description = "환율 캐시를 비우고 ECOS 를 다시 조회해 fx_rates 에 반영한다.")
    @PostMapping("/fx-rates/refresh")
    public ApiResponse<AdminRefreshResponse> refreshFxRates(
            @CurrentAdmin UUID adminId,
            @RequestParam(value = "lookback_days", required = false) Integer lookbackDays) {
        int lookback = lookbackDays == null ? DEFAULT_REFRESH_LOOKBACK_DAYS : lookbackDays;
        return ApiResponse.of(AdminRefreshResponse.from(
                fxRateRefreshService.refresh(LocalDate.now(clock), lookback)));
    }

    @Operation(summary = "환율 구멍 조회",
            description = "영업일 달력과 대조해 빠진 구간을 돌려준다. pair_code 를 비우면 저장 대상 전부를 본다. "
                    + "백필을 한 번 돌린 뒤 남은 구멍은 공휴일이 아니라 우리가 못 받은 날이다.")
    @GetMapping("/fx-rates/gaps")
    public ApiResponse<AdminFxRateCoverageResponse> gaps(
            @CurrentAdmin UUID adminId,
            @RequestParam(value = "pair_code", required = false) String pairCode,
            @RequestParam(value = "from", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(value = "to", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        LocalDate end = to == null ? LocalDate.now(clock) : to;
        LocalDate start = from == null ? end.minusDays(DEFAULT_RANGE_DAYS) : from;
        List<FxRateGapService.PairCoverage> coverages = pairCode == null || pairCode.isBlank()
                ? fxRateGapService.coverageOfStoredPairs(start, end)
                : List.of(fxRateGapService.coverage(pairCode, start, end));
        return ApiResponse.of(AdminFxRateCoverageResponse.from(coverages));
    }

    @Operation(summary = "환율 구멍 백필",
            description = "빠진 구간만 ECOS 에서 다시 받아 메운다(전체 재적재가 아니다). "
                    + "ECOS 도 값이 없는 날짜는 고시 부재로 확정해 다음 판정에서 구멍으로 세지 않는다. "
                    + "초기 5년 백필도 이 경로를 쓴다.")
    @PostMapping("/fx-rates/backfill")
    public ApiResponse<AdminFxRateBackfillResponse> backfill(
            @CurrentAdmin UUID adminId,
            @RequestParam(value = "pair_code", required = false) String pairCode,
            @RequestParam(value = "from", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(value = "to", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        LocalDate end = to == null ? LocalDate.now(clock) : to;
        LocalDate start = from == null ? end.minusDays(DEFAULT_RANGE_DAYS) : from;
        if (pairCode == null || pairCode.isBlank()) {
            return ApiResponse.of(AdminFxRateBackfillResponse.from(
                    fxRateGapService.backfillStoredPairs(start, end)));
        }
        FxRateGapService.PairBackfill one = fxRateGapService.backfill(pairCode, start, end);
        return ApiResponse.of(AdminFxRateBackfillResponse.from(
                new FxRateGapService.BackfillReport(List.of(one), clock.instant())));
    }

    @Operation(summary = "FRED 거시지표 수동 갱신",
            description = "저장하지 않는다 — 거시지표 테이블이 아직 없고, 목적은 연동 점검이다.")
    @PostMapping("/macro/refresh")
    public ApiResponse<AdminMacroRefreshResponse> refreshMacro(
            @CurrentAdmin UUID adminId, @Valid @RequestBody AdminMacroRefreshRequest request) {
        return ApiResponse.of(AdminMacroRefreshResponse.from(
                macroRefreshService.refresh(request.seriesIds())));
    }
}
