package com.divurve.api.controller.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.divurve.api.dto.admin.AdminCurrencyResponse;
import com.divurve.api.dto.admin.AdminExtractPreviewRequest;
import com.divurve.api.dto.admin.AdminExtractPreviewResponse;
import com.divurve.api.dto.admin.AdminFxRateBackfillResponse;
import com.divurve.api.dto.admin.AdminFxRateCoverageResponse;
import com.divurve.api.dto.admin.AdminFxRateSeriesResponse;
import com.divurve.api.dto.admin.AdminMacroRefreshRequest;
import com.divurve.api.dto.admin.AdminMacroRefreshResponse;
import com.divurve.api.dto.admin.AdminRefreshResponse;
import com.divurve.api.dto.admin.AdminUserDataResponse;
import com.divurve.api.dto.admin.AdminUserListResponse;
import com.divurve.common.response.ApiResponse;
import com.divurve.domain.event.EconEventExtractPreviewService;
import com.divurve.domain.fx.FxRateGapService;
import com.divurve.domain.fx.FxRateIngestionService;
import com.divurve.domain.fx.FxRateQueryService;
import com.divurve.domain.fx.FxRateRefreshService;
import com.divurve.domain.macro.MacroRefreshService;
import com.divurve.domain.master.MasterDataService;
import com.divurve.domain.port.MacroSnapshot;
import com.divurve.domain.user.AdminDataQueryService;
import com.divurve.domain.user.AdminUserQueryService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 관리자 컨트롤러 4종의 매핑 검증 (이슈 #111).
 *
 * <p>인가는 컨트롤러가 아니라 {@code CurrentAdminArgumentResolver} 가 강제하므로 여기서는 검증하지
 * 않는다 — 그것은 {@code AdminAuthorizationMockMvcTest} 가 HTTP 로 확인한다. 여기서 고정하는 것은
 * "서비스 결과가 응답 형태로 정확히 옮겨지는가" 다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("관리자 컨트롤러")
class AdminControllerTest {

    private static final UUID ADMIN_ID = UUID.randomUUID();
    private static final UUID USER_ID = UUID.randomUUID();
    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-09-07T00:30:00Z"), ZoneOffset.UTC);

    @Mock private AdminUserQueryService adminUserQueryService;
    @Mock private AdminDataQueryService adminDataQueryService;
    @Mock private MasterDataService masterDataService;
    @Mock private FxRateQueryService fxRateQueryService;
    @Mock private FxRateRefreshService fxRateRefreshService;
    @Mock private FxRateGapService fxRateGapService;
    @Mock private MacroRefreshService macroRefreshService;
    @Mock private EconEventExtractPreviewService econEventExtractPreviewService;

    private static AdminUserQueryService.UserSummary summary() {
        return new AdminUserQueryService.UserSummary(
                USER_ID, "user@example.com", "사용자", "USER", false, true,
                Instant.parse("2026-09-01T00:00:00Z"), Instant.parse("2026-09-01T01:00:00Z"),
                Instant.parse("2026-09-06T10:00:00Z"), "203.0.113.42");
    }

    @Nested
    @DisplayName("AdminUserController")
    class Users {

        private AdminUserController controller() {
            return new AdminUserController(adminUserQueryService, adminDataQueryService);
        }

        @Test
        @DisplayName("목록은 페이지 메타와 전 필드를 data/meta 로 래핑한다")
        void list_WrapsPage() {
            when(adminUserQueryService.list("user", true, 0, 50)).thenReturn(
                    new AdminUserQueryService.UserPage(List.of(summary()), 0, 50, 1L, 1));

            ApiResponse<AdminUserListResponse> response =
                    controller().list(ADMIN_ID, "user", true, 0, 50);

            assertThat(response.meta()).isNotNull();
            assertThat(response.data().page()).isZero();
            assertThat(response.data().size()).isEqualTo(50);
            assertThat(response.data().totalElements()).isEqualTo(1L);
            assertThat(response.data().totalPages()).isEqualTo(1);
            assertThat(response.data().items()).singleElement().satisfies(user -> {
                assertThat(user.id()).isEqualTo(USER_ID);
                assertThat(user.email()).isEqualTo("user@example.com");
                assertThat(user.name()).isEqualTo("사용자");
                assertThat(user.role()).isEqualTo("USER");
                assertThat(user.isDemo()).isFalse();
                assertThat(user.sampleDataSeeded()).isTrue();
                assertThat(user.createdAt()).isEqualTo(Instant.parse("2026-09-01T00:00:00Z"));
                assertThat(user.onboardedAt()).isEqualTo(Instant.parse("2026-09-01T01:00:00Z"));
                assertThat(user.lastLoginAt()).isEqualTo(Instant.parse("2026-09-06T10:00:00Z"));
                assertThat(user.lastLoginIp()).isEqualTo("203.0.113.42");
            });
        }

        @Test
        @DisplayName("단건 조회는 같은 형태를 쓴다")
        void get_ReturnsSameShape() {
            when(adminUserQueryService.get(USER_ID)).thenReturn(summary());

            assertThat(controller().get(ADMIN_ID, USER_ID).data().email())
                    .isEqualTo("user@example.com");
        }

        @Test
        @DisplayName("전 데이터 조회는 도메인별 배열을 그대로 담는다")
        void data_WrapsBundle() {
            when(adminDataQueryService.collect(USER_ID)).thenReturn(
                    new AdminDataQueryService.UserDataBundle(
                            List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                            null, null, List.of()));

            ApiResponse<AdminUserDataResponse> response = controller().data(ADMIN_ID, USER_ID);

            assertThat(response.data().holdings()).isEmpty();
            assertThat(response.data().riskProfile()).isNull();
            assertThat(response.data().userSettings()).isNull();
        }
    }

    @Nested
    @DisplayName("AdminCurrencyController")
    class Currencies {

        @Test
        @DisplayName("통화·통화쌍 전 컬럼을 담는다")
        void list_IncludesEveryColumn() {
            when(masterDataService.listAllCurrencies()).thenReturn(List.of(
                    new MasterDataService.CurrencyView(
                            "GBP", "영국 파운드", "£", (short) 2, (short) 1, "quote",
                            false, false, "ECOS 미고시", "currency-gbp", (short) 4)));
            when(masterDataService.listCurrencyPairs()).thenReturn(List.of(
                    new MasterDataService.CurrencyPairView(
                            "JPYKRW", "JPY", "KRW", false, "USDJPY")));

            ApiResponse<AdminCurrencyResponse> response =
                    new AdminCurrencyController(masterDataService).list(ADMIN_ID);

            assertThat(response.meta()).isNotNull();
            assertThat(response.data().currencies()).singleElement().satisfies(currency -> {
                assertThat(currency.currencyCode()).isEqualTo("GBP");
                assertThat(currency.nameKo()).isEqualTo("영국 파운드");
                assertThat(currency.symbol()).isEqualTo("£");
                assertThat(currency.minorUnits()).isEqualTo((short) 2);
                assertThat(currency.quoteUnit()).isEqualTo((short) 1);
                assertThat(currency.usdSide()).isEqualTo("quote");
                assertThat(currency.isHomeCurrency()).isFalse();
                assertThat(currency.isSupported()).isFalse();
                assertThat(currency.supportNote()).isEqualTo("ECOS 미고시");
                assertThat(currency.colorToken()).isEqualTo("currency-gbp");
                assertThat(currency.sortOrder()).isEqualTo((short) 4);
            });
            assertThat(response.data().currencyPairs()).singleElement().satisfies(pair -> {
                assertThat(pair.pairCode()).isEqualTo("JPYKRW");
                assertThat(pair.baseCurrencyCode()).isEqualTo("JPY");
                assertThat(pair.quoteCurrencyCode()).isEqualTo("KRW");
                assertThat(pair.isStored()).isFalse();
                assertThat(pair.deriveViaPairCode()).isEqualTo("USDJPY");
            });
        }
    }

    @Nested
    @DisplayName("AdminFxRateController")
    class FxRates {

        private AdminFxRateController controller() {
            return new AdminFxRateController(
                    fxRateQueryService, fxRateRefreshService, fxRateGapService,
                    macroRefreshService, CLOCK);
        }

        @Test
        @DisplayName("기간을 생략하면 오늘 기준 1년을 본다")
        void series_DefaultsToOneYear() {
            when(fxRateQueryService.series(any(), any(), any(), any())).thenReturn(
                    new FxRateQueryService.RateSeries(
                            "USDKRW", "mid", LocalDate.of(2025, 9, 7), LocalDate.of(2026, 9, 7),
                            List.of(new FxRateQueryService.Point(
                                    LocalDate.of(2026, 9, 4), new BigDecimal("1380.5"),
                                    "ECOS", Instant.parse("2026-09-07T00:30:00Z")))));

            ApiResponse<AdminFxRateSeriesResponse> response =
                    controller().series(ADMIN_ID, "USDKRW", null, null, "mid");

            verify(fxRateQueryService).series(
                    eq("USDKRW"), eq(LocalDate.of(2025, 9, 7)), eq(LocalDate.of(2026, 9, 7)),
                    eq("mid"));
            assertThat(response.data().count()).isEqualTo(1);
            assertThat(response.data().points()).singleElement().satisfies(point -> {
                assertThat(point.quoteDate()).isEqualTo(LocalDate.of(2026, 9, 4));
                assertThat(point.rate()).isEqualByComparingTo("1380.5");
                assertThat(point.dataSource()).isEqualTo("ECOS");
                assertThat(point.fetchedAt()).isEqualTo(Instant.parse("2026-09-07T00:30:00Z"));
            });
        }

        @Test
        @DisplayName("기간을 지정하면 그대로 넘긴다")
        void series_UsesGivenRange() {
            LocalDate from = LocalDate.of(2026, 8, 1);
            LocalDate to = LocalDate.of(2026, 9, 1);
            when(fxRateQueryService.series(any(), any(), any(), any())).thenReturn(
                    new FxRateQueryService.RateSeries("USDKRW", "mid", from, to, List.of()));

            controller().series(ADMIN_ID, "USDKRW", from, to, "mid");

            verify(fxRateQueryService).series(eq("USDKRW"), eq(from), eq(to), eq("mid"));
        }

        @Test
        @DisplayName("환율 갱신은 반영 건수와 실패 사유를 그대로 내보낸다")
        void refreshFxRates_ExposesReport() {
            when(fxRateRefreshService.refresh(any(), anyInt())).thenReturn(
                    new FxRateRefreshService.RefreshReport(
                            List.of("fx-latest"),
                            List.of(new FxRateIngestionService.PairResult(
                                    "EURKRW", 0, null, null, "ECOS 응답 없음")),
                            0, true, 0, 0, List.of(), Instant.now(CLOCK), 120L));

            ApiResponse<AdminRefreshResponse> response =
                    controller().refreshFxRates(ADMIN_ID, null);

            verify(fxRateRefreshService).refresh(
                    eq(LocalDate.now(CLOCK)),
                    eq(AdminFxRateController.DEFAULT_REFRESH_LOOKBACK_DAYS));
            assertThat(response.data().evictedCaches()).containsExactly("fx-latest");
            assertThat(response.data().totalUpserted()).isZero();
            assertThat(response.data().hasFailure()).isTrue();
            assertThat(response.data().elapsedMs()).isEqualTo(120L);
            assertThat(response.data().pairs()).singleElement().satisfies(pair -> {
                assertThat(pair.pairCode()).isEqualTo("EURKRW");
                assertThat(pair.upserted()).isZero();
                assertThat(pair.firstDate()).isNull();
                assertThat(pair.lastDate()).isNull();
                assertThat(pair.failureReason()).isEqualTo("ECOS 응답 없음");
            });
        }

        @Test
        @DisplayName("조회 기간을 지정하면 그대로 넘긴다")
        void refreshFxRates_UsesGivenLookback() {
            when(fxRateRefreshService.refresh(any(), anyInt())).thenReturn(
                    new FxRateRefreshService.RefreshReport(
                            List.of(), List.of(), 0, false, 0, 0, List.of(),
                            Instant.now(CLOCK), 0L));

            controller().refreshFxRates(ADMIN_ID, 30);

            verify(fxRateRefreshService).refresh(any(), eq(30));
        }

        @Test
        @DisplayName("거시지표 갱신은 성공·실패를 모두 담는다")
        void refreshMacro_ExposesBothOutcomes() {
            when(macroRefreshService.refresh(List.of("DGS10", "BAD"))).thenReturn(
                    new MacroRefreshService.MacroRefreshReport(
                            List.of("macro-latest"),
                            List.of(
                                    new MacroRefreshService.SeriesResult(
                                            "DGS10",
                                            new MacroSnapshot(
                                                    "DGS10", new BigDecimal("4.21"),
                                                    LocalDate.of(2026, 9, 5), "FRED",
                                                    Instant.now(CLOCK)),
                                            null),
                                    new MacroRefreshService.SeriesResult(
                                            "BAD", null, "FRED 응답 없음")),
                            Instant.now(CLOCK), 80L));

            ApiResponse<AdminMacroRefreshResponse> response = controller().refreshMacro(
                    ADMIN_ID, new AdminMacroRefreshRequest(List.of("DGS10", "BAD")));

            assertThat(response.data().evictedCaches()).containsExactly("macro-latest");
            assertThat(response.data().elapsedMs()).isEqualTo(80L);
            assertThat(response.data().series()).hasSize(2);
            assertThat(response.data().series().get(0).value()).isEqualByComparingTo("4.21");
            assertThat(response.data().series().get(0).asOf()).isEqualTo(LocalDate.of(2026, 9, 5));
            assertThat(response.data().series().get(0).source()).isEqualTo("FRED");
            assertThat(response.data().series().get(0).fetchedAt()).isEqualTo(Instant.now(CLOCK));
            assertThat(response.data().series().get(1).value()).isNull();
            assertThat(response.data().series().get(1).failureReason()).isEqualTo("FRED 응답 없음");
        }

        // ── 구멍 조회·백필 (이슈 #116) ────────────────────────────────────

        private FxRateGapService.PairCoverage coverage(String pairCode, boolean complete) {
            return new FxRateGapService.PairCoverage(
                    pairCode, "mid", LocalDate.of(2025, 9, 7), LocalDate.of(2026, 9, 7),
                    10, complete ? 10 : 8, complete ? 0 : 2, complete ? 1.0 : 0.8, complete,
                    complete ? List.of() : List.of(new FxRateGapService.Gap(
                            LocalDate.of(2026, 9, 2), LocalDate.of(2026, 9, 3), 2)));
        }

        @Test
        @DisplayName("pair_code 를 비우면 저장 대상 전부를 본다")
        void gaps_DefaultsToAllStoredPairs() {
            when(fxRateGapService.coverageOfStoredPairs(any(), any()))
                    .thenReturn(List.of(coverage("USDKRW", false)));

            // 비었다는 것은 null 일 수도, 공백일 수도 있다 — 둘 다 "전부 보기" 다.
            controller().gaps(ADMIN_ID, null, null, null);
            ApiResponse<AdminFxRateCoverageResponse> response =
                    controller().gaps(ADMIN_ID, "  ", null, null);

            verify(fxRateGapService, times(2)).coverageOfStoredPairs(
                    LocalDate.now(CLOCK).minusDays(AdminFxRateController.DEFAULT_RANGE_DAYS),
                    LocalDate.now(CLOCK));
            assertThat(response.data().pairs()).singleElement().satisfies(pair -> {
                assertThat(pair.pairCode()).isEqualTo("USDKRW");
                assertThat(pair.complete()).isFalse();
                assertThat(pair.missingBusinessDays()).isEqualTo(2);
                assertThat(pair.coverageRatio()).isEqualTo(0.8);
                assertThat(pair.gaps()).containsExactly(new AdminFxRateCoverageResponse.Gap(
                        LocalDate.of(2026, 9, 2), LocalDate.of(2026, 9, 3), 2));
            });
        }

        @Test
        @DisplayName("pair_code 를 주면 그 쌍만 본다")
        void gaps_SinglePair() {
            LocalDate from = LocalDate.of(2026, 1, 1);
            LocalDate to = LocalDate.of(2026, 9, 7);
            when(fxRateGapService.coverage(eq("USDKRW"), eq(from), eq(to)))
                    .thenReturn(coverage("USDKRW", true));

            ApiResponse<AdminFxRateCoverageResponse> response =
                    controller().gaps(ADMIN_ID, "USDKRW", from, to);

            assertThat(response.data().pairs()).singleElement()
                    .extracting(AdminFxRateCoverageResponse.PairCoverage::complete)
                    .isEqualTo(true);
        }

        @Test
        @DisplayName("백필도 pair_code 를 비우면 저장 대상 전부를 돈다")
        void backfill_DefaultsToAllStoredPairs() {
            when(fxRateGapService.backfillStoredPairs(any(), any())).thenReturn(
                    new FxRateGapService.BackfillReport(
                            List.of(new FxRateGapService.PairBackfill(
                                    "USDKRW", 2, 1, 3, 0, true, List.of(), null)),
                            Instant.now(CLOCK)));

            controller().backfill(ADMIN_ID, "  ", null, null);
            ApiResponse<AdminFxRateBackfillResponse> response =
                    controller().backfill(ADMIN_ID, null, null, null);

            assertThat(response.data().totalFilled()).isEqualTo(2);
            assertThat(response.data().totalConfirmedAbsent()).isEqualTo(1);
            assertThat(response.data().complete()).isTrue();
            assertThat(response.data().hasFailure()).isFalse();
            assertThat(response.data().pairs()).singleElement().satisfies(pair -> {
                assertThat(pair.pairCode()).isEqualTo("USDKRW");
                assertThat(pair.missingBefore()).isEqualTo(3);
                assertThat(pair.missingAfter()).isZero();
                assertThat(pair.remainingGaps()).isEmpty();
            });
        }

        @Test
        @DisplayName("백필에 pair_code 를 주면 그 쌍만 돈다 — 남은 구멍이 응답에 드러난다")
        void backfill_SinglePair() {
            LocalDate from = LocalDate.of(2026, 1, 1);
            LocalDate to = LocalDate.of(2026, 9, 7);
            when(fxRateGapService.backfill("USDKRW", from, to)).thenReturn(
                    new FxRateGapService.PairBackfill(
                            "USDKRW", 0, 0, 2, 2, false,
                            List.of(new FxRateGapService.Gap(
                                    LocalDate.of(2026, 9, 2), LocalDate.of(2026, 9, 3), 2)),
                            "ECOS 응답 없음"));

            ApiResponse<AdminFxRateBackfillResponse> response =
                    controller().backfill(ADMIN_ID, "USDKRW", from, to);

            assertThat(response.data().hasFailure()).isTrue();
            assertThat(response.data().complete()).isFalse();
            assertThat(response.data().backfilledAt()).isEqualTo(Instant.now(CLOCK));
            assertThat(response.data().pairs()).singleElement().satisfies(pair -> {
                assertThat(pair.failureReason()).isEqualTo("ECOS 응답 없음");
                assertThat(pair.remainingGaps()).hasSize(1);
            });
        }
    }

    @Nested
    @DisplayName("AdminAiController")
    class Ai {

        @Test
        @DisplayName("추출 미리보기는 거부 사유와 추출기 이름을 그대로 내보낸다")
        void extractPreview_ExposesRejectReason() {
            when(econEventExtractPreviewService.preview("https://x", "원문")).thenReturn(
                    new EconEventExtractPreviewService.PreviewResult(
                            "NoOpEconEventExtractor",
                            List.of(new EconEventExtractPreviewService.CandidateResult(
                                    "어제", "MARS", "제목", 5, false, "허용되지 않는 region")),
                            Instant.now(CLOCK)));

            ApiResponse<AdminExtractPreviewResponse> response =
                    new AdminAiController(econEventExtractPreviewService).extractPreview(
                            ADMIN_ID, new AdminExtractPreviewRequest("https://x", "원문"));

            assertThat(response.meta()).isNotNull();
            assertThat(response.data().extractor()).isEqualTo("NoOpEconEventExtractor");
            assertThat(response.data().count()).isEqualTo(1);
            assertThat(response.data().previewedAt()).isEqualTo(Instant.now(CLOCK));
            assertThat(response.data().candidates()).singleElement().satisfies(candidate -> {
                assertThat(candidate.eventDate()).isEqualTo("어제");
                assertThat(candidate.region()).isEqualTo("MARS");
                assertThat(candidate.title()).isEqualTo("제목");
                assertThat(candidate.impact()).isEqualTo(5);
                assertThat(candidate.valid()).isFalse();
                assertThat(candidate.rejectReason()).isEqualTo("허용되지 않는 region");
            });
        }
    }
}
