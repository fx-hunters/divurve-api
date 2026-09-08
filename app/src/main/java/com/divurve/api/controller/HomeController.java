package com.divurve.api.controller;

import com.divurve.api.dto.forecast.EventsResponse.Event;
import com.divurve.api.dto.home.HomeSummaryResponse;
import com.divurve.api.dto.home.HomeSummaryResponse.ActiveGoalDto;
import com.divurve.api.dto.home.HomeSummaryResponse.AttentionDto;
import com.divurve.api.dto.home.HomeSummaryResponse.BlockDto;
import com.divurve.api.dto.home.HomeSummaryResponse.FxStatusDto;
import com.divurve.api.dto.home.HomeSummaryResponse.ForecastDto;
import com.divurve.api.dto.home.HomeSummaryResponse.GoalsRouteDto;
import com.divurve.api.dto.home.HomeSummaryResponse.HistoryPointDto;
import com.divurve.api.dto.home.HomeSummaryResponse.Interval80Dto;
import com.divurve.api.dto.home.HomeSummaryResponse.ProfileFitDto;
import com.divurve.api.dto.home.HomeSummaryResponse.TodayDto;
import com.divurve.api.dto.xray.XrayResponse;
import com.divurve.api.config.auth.CurrentUser;
import com.divurve.common.architecture.WebAdapter;
import com.divurve.common.response.ApiResponse;
import com.divurve.common.response.Meta;
import com.divurve.domain.home.HomeSummaryService;
import com.divurve.domain.home.HomeSummaryService.HomeSummaryView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 홈 요약 엔드포인트 (API 명세 v2 §5.11, 요구사항 v2 §4.4 FR-HM-01~08, 이슈 #54(7.5)).
 * 화면 v2 §11 6블록을 <b>고정 순서</b>로 반환한다 — 프로필·설정은 마이페이지로 분리한다(FR-HM-08).
 */
@WebAdapter
@RestController
@RequestMapping("/api/v1/home")
@Tag(name = "Home", description = "홈 화면 6블록 요약")
public class HomeController {

    private final HomeSummaryService homeSummaryService;

    public HomeController(HomeSummaryService homeSummaryService) {
        this.homeSummaryService = homeSummaryService;
    }

    @Operation(
            summary = "홈 요약 6블록 조회",
            description = "오늘의 핵심·위험성향 Fit·외화현황·목표 영역·주의필요·Forecast 요약을 "
                    + "고정 순서로 반환한다. 데이터가 없는 블록도 생략하지 않고 state 로만 구분한다"
                    + "(filled/empty/not_measured).")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "홈 요약"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "사용자 없음")
    })
    @GetMapping("/summary")
    public ApiResponse<HomeSummaryResponse> getSummary(@CurrentUser UUID userId) {
        HomeSummaryView view = homeSummaryService.getSummary(userId);
        Meta meta = Meta.mock(view.referenceTime()).withRegime(view.regime());
        return ApiResponse.of(toResponse(view), meta);
    }

    private HomeSummaryResponse toResponse(HomeSummaryView view) {
        return new HomeSummaryResponse(
                view.blocks().stream()
                        .map(block -> new BlockDto(block.order(), block.key(), block.state()))
                        .toList(),
                new TodayDto(view.today().headlineCode(), view.today().badge()),
                new ProfileFitDto(view.profileFit().grade(), view.profileFit().concentrationStatus()),
                new FxStatusDto(
                        view.fxStatus().fxRatio(),
                        view.fxStatus().topCurrencyCode(),
                        view.fxStatus().sensitivity1pctKrw(),
                        view.fxStatus().dayChangeKrw(),
                        toExposure(view.fxStatus())),
                new GoalsRouteDto(
                        view.goalsRoute().activeGoals().stream()
                                .map(goal -> new ActiveGoalDto(
                                        goal.id(), goal.name(), goal.currencyCode(), goal.targetAmount(),
                                        goal.targetDate(), goal.status()))
                                .toList()),
                new AttentionDto(
                        view.attention().regimeBadge(),
                        view.attention().upcomingEvents().stream()
                                .map(event -> new Event(
                                        event.date(), event.title(), event.currencyCode(), event.importance()))
                                .toList()),
                view.forecast() == null
                        ? null
                        : new ForecastDto(
                                view.forecast().pairCode(),
                                view.forecast().currentRate(),
                                new Interval80Dto(
                                        view.forecast().interval80().lo(), view.forecast().interval80().hi()),
                                toHistory(view.forecast())));
    }

    /**
     * {@code GET /xray} 의 {@code exposure[]} 와 동일한 조합 규칙 — {@link XrayController} 의
     * 매핑을 그대로 따른다(이슈 #94). 새 계산이 아니라 {@link HomeSummaryService} 가 이미 조회한
     * {@code XrayService.PortfolioSnapshot} 유래 값을 그대로 옮긴다.
     */
    private List<XrayResponse.Exposure> toExposure(HomeSummaryService.FxStatusView fxStatus) {
        return fxStatus.currencyToAssetKrw().entrySet().stream()
                .map(entry -> new XrayResponse.Exposure(
                        entry.getKey(),
                        entry.getValue(),
                        fxStatus.exposureShare().getOrDefault(entry.getKey(), 0.0)))
                .toList();
    }

    /** {@code forecast.history} 스파크라인 매핑 — {@link HomeSummaryService} 가 이미 30영업일로 잘라 둔다. */
    private List<HistoryPointDto> toHistory(HomeSummaryService.ForecastSummaryView forecast) {
        return forecast.history().stream()
                .map(point -> new HistoryPointDto(point.date(), point.rate()))
                .toList();
    }
}
