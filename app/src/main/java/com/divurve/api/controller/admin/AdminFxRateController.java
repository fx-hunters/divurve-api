package com.divurve.api.controller.admin;

import com.divurve.api.config.auth.CurrentAdmin;
import com.divurve.api.dto.admin.AdminFxRateSeriesResponse;
import com.divurve.api.dto.admin.AdminMacroRefreshRequest;
import com.divurve.api.dto.admin.AdminMacroRefreshResponse;
import com.divurve.api.dto.admin.AdminRefreshResponse;
import com.divurve.common.architecture.WebAdapter;
import com.divurve.common.response.ApiResponse;
import com.divurve.domain.fx.FxRateQueryService;
import com.divurve.domain.fx.FxRateRefreshService;
import com.divurve.domain.macro.MacroRefreshService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.time.Clock;
import java.time.LocalDate;
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
    private final MacroRefreshService macroRefreshService;
    private final Clock clock;

    public AdminFxRateController(
            FxRateQueryService fxRateQueryService,
            FxRateRefreshService fxRateRefreshService,
            MacroRefreshService macroRefreshService,
            Clock clock) {
        this.fxRateQueryService = fxRateQueryService;
        this.fxRateRefreshService = fxRateRefreshService;
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

    @Operation(summary = "FRED 거시지표 수동 갱신",
            description = "저장하지 않는다 — 거시지표 테이블이 아직 없고, 목적은 연동 점검이다.")
    @PostMapping("/macro/refresh")
    public ApiResponse<AdminMacroRefreshResponse> refreshMacro(
            @CurrentAdmin UUID adminId, @Valid @RequestBody AdminMacroRefreshRequest request) {
        return ApiResponse.of(AdminMacroRefreshResponse.from(
                macroRefreshService.refresh(request.seriesIds())));
    }
}
