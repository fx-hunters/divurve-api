package com.divurve.api.controller.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.divurve.api.dto.admin.AdminEconEventRefreshResponse;
import com.divurve.api.dto.admin.AdminEconEventStatusResponse;
import com.divurve.domain.event.EconEventAdminService;
import com.divurve.domain.event.OfficialEventIngestionService.IngestionReport;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * {@link AdminEconEventController} 매핑 (이슈 #176).
 *
 * <p>실패한 갱신도 200 으로 나가며 집계 자리는 {@code null} 이 된다 — 프론트가 그 구분으로
 * 붉게 강조한다(관리자 콘솔 문서 §2-4).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AdminEconEventController")
class AdminEconEventControllerTest {

    private static final UUID ADMIN_ID = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-09-09T04:10:00Z");

    @Mock
    private EconEventAdminService econEventAdminService;

    private AdminEconEventController controller() {
        return new AdminEconEventController(econEventAdminService);
    }

    @Test
    @DisplayName("성공한 갱신은 집계를 그대로 옮긴다")
    void 성공_집계를_옮긴다() {
        when(econEventAdminService.refresh()).thenReturn(
                new EconEventAdminService.RefreshResult(
                        new IngestionReport(12, 5, 2, 3, 2), null, NOW, 840L));

        AdminEconEventRefreshResponse data = controller().refresh(ADMIN_ID).data();

        assertThat(data.scheduled()).isEqualTo(12);
        assertThat(data.inserted()).isEqualTo(5);
        assertThat(data.promoted()).isEqualTo(2);
        assertThat(data.skipped()).isEqualTo(3);
        assertThat(data.unknown()).isEqualTo(2);
        assertThat(data.failureReason()).isNull();
        assertThat(data.elapsedMs()).isEqualTo(840L);
    }

    @Test
    @DisplayName("실패한 갱신은 집계가 null 이고 사유가 실린다 — 500 이 아니다")
    void 실패는_사유를_싣는다() {
        when(econEventAdminService.refresh()).thenReturn(
                new EconEventAdminService.RefreshResult(null, "IllegalStateException", NOW, 12L));

        AdminEconEventRefreshResponse data = controller().refresh(ADMIN_ID).data();

        assertThat(data.scheduled()).isNull();
        assertThat(data.inserted()).isNull();
        assertThat(data.promoted()).isNull();
        assertThat(data.skipped()).isNull();
        assertThat(data.unknown()).isNull();
        assertThat(data.failureReason()).isEqualTo("IllegalStateException");
    }

    @Test
    @DisplayName("현황은 출처별로 옮긴다")
    void 현황을_옮긴다() {
        when(econEventAdminService.status()).thenReturn(
                new EconEventAdminService.StatusResult(List.of(
                        new EconEventAdminService.SourceStatus(
                                "OFFICIAL_PARSER", 24, NOW, LocalDate.of(2026, 12, 15)),
                        new EconEventAdminService.SourceStatus(
                                "DEMO_SAMPLE", 7, NOW, LocalDate.of(2026, 11, 20))),
                        NOW));

        AdminEconEventStatusResponse data = controller().status(ADMIN_ID).data();

        assertThat(data.sources())
                .extracting(AdminEconEventStatusResponse.SourceStatus::sourceKind)
                .containsExactly("OFFICIAL_PARSER", "DEMO_SAMPLE");
        assertThat(data.sources().get(0).total()).isEqualTo(24);
        assertThat(data.sources().get(1).lastEventDate()).isEqualTo(LocalDate.of(2026, 11, 20));
        assertThat(data.checkedAt()).isEqualTo(NOW);
    }
}
