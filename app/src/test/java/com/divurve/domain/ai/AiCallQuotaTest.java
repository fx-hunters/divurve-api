package com.divurve.domain.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * {@link AiCallQuota} 테스트 (이슈 #140).
 *
 * <p>경계가 전부다 — 상한 <b>미만</b>은 통과하고 상한 <b>이상</b>은 막는다. 한 칸 어긋나면 상한이
 * 광고한 값과 실제 값이 달라지고, 그 차이는 청구서로만 드러난다.
 */
@ExtendWith(MockitoExtension.class)
class AiCallQuotaTest {

    private static final Instant NOW = Instant.parse("2026-09-08T09:00:00Z");
    private static final String CLIENT_IP = "203.0.113.7";

    private static final int PER_USER = 100;
    private static final int PER_DEMO_USER = 30;
    private static final int PER_IP = 300;
    private static final int GLOBAL = 500;

    @Mock
    private AiCallLogRepository aiCallLogRepository;

    private final UUID userId = UUID.randomUUID();

    private AiCallQuota quota() {
        return new AiCallQuota(aiCallLogRepository, Clock.fixed(NOW, ZoneOffset.UTC),
                PER_USER, PER_DEMO_USER, PER_IP, GLOBAL);
    }

    /** 세 카운트를 한 행으로 돌려주는 쿼리를 흉내낸다 — 실제로도 한 번에 온다. */
    private void stubCounts(long userCalls, long ipCalls, long globalCalls) {
        when(aiCallLogRepository.countChargeableSince(any(), anyString(), any()))
                .thenReturn(List.<Object[]>of(new Object[] {userCalls, ipCalls, globalCalls}));
    }

    @Test
    @DisplayName("세 층 모두 상한 미만이면 통과한다")
    void passesBelowEveryLimit() {
        stubCounts(PER_USER - 1, PER_IP - 1, GLOBAL - 1);

        assertThat(quota().exceededLayer(userId, false, CLIENT_IP)).isEmpty();
    }

    @Test
    @DisplayName("사용자 상한은 '이상' 에서 막는다 — 한 칸 경계")
    void userLimitBlocksAtTheLimitNotAfterIt() {
        stubCounts(PER_USER, 0, 0);

        assertThat(quota().exceededLayer(userId, false, CLIENT_IP))
                .contains(AiCallQuota.Layer.USER);
    }

    @Test
    @DisplayName("데모 세션은 더 낮은 상한을 쓴다 — 일반 상한 미만이어도 막힌다")
    void demoSessionsUseTheLowerLimit() {
        stubCounts(PER_DEMO_USER, 0, 0);
        AiCallQuota quota = quota();

        assertThat(quota.exceededLayer(userId, true, CLIENT_IP))
                .as("데모 상한(%d)에 닿았다", PER_DEMO_USER)
                .contains(AiCallQuota.Layer.USER);
        assertThat(quota.exceededLayer(userId, false, CLIENT_IP))
                .as("같은 건수라도 일반 사용자는 상한(%d)에 한참 못 미친다", PER_USER)
                .isEmpty();
    }

    @Test
    @DisplayName("IP 상한을 넘으면 IP 층으로 막는다")
    void ipLimitBlocks() {
        stubCounts(0, PER_IP, 0);

        assertThat(quota().exceededLayer(userId, false, CLIENT_IP))
                .contains(AiCallQuota.Layer.IP);
    }

    @Test
    @DisplayName("전역 상한을 넘으면 킬스위치가 발동한다")
    void globalKillSwitchFires() {
        stubCounts(0, 0, GLOBAL);

        assertThat(quota().exceededLayer(userId, false, CLIENT_IP))
                .contains(AiCallQuota.Layer.GLOBAL);
    }

    @Test
    @DisplayName("여러 층이 동시에 걸리면 가장 좁은 층을 보고한다")
    void reportsTheNarrowestLayerWhenSeveralAreExceeded() {
        // 한 사용자가 자기 몫을 다 쓴 것과 서비스 전체가 멈춘 것은 대응이 완전히 다른 사건이다.
        stubCounts(PER_USER, PER_IP, GLOBAL);

        assertThat(quota().exceededLayer(userId, false, CLIENT_IP))
                .contains(AiCallQuota.Layer.USER);
    }

    @Test
    @DisplayName("IP 를 모르면 빈 문자열로 세어 IP 층이 자연히 건너뛰어진다")
    void unknownIpSkipsTheIpLayer() {
        stubCounts(0, 0, 0);

        assertThat(quota().exceededLayer(userId, false, null)).isEmpty();

        ArgumentCaptor<String> ip = ArgumentCaptor.forClass(String.class);
        verify(aiCallLogRepository).countChargeableSince(eq(userId), ip.capture(), any());
        assertThat(ip.getValue())
                .as("null 을 넘기면 타입 없는 바인드 파라미터로 쿼리가 깨진다(이슈 #118)")
                .isEmpty();
    }

    @Test
    @DisplayName("창은 달력상의 하루가 아니라 지난 24시간이다")
    void windowIsRollingTwentyFourHours() {
        stubCounts(0, 0, 0);

        quota().exceededLayer(userId, false, CLIENT_IP);

        ArgumentCaptor<Instant> since = ArgumentCaptor.forClass(Instant.class);
        verify(aiCallLogRepository).countChargeableSince(any(), anyString(), since.capture());
        assertThat(since.getValue())
                .as("'오늘' 로 세면 UTC 로 자르는 관리자 집계와 9시간 동안 다른 값이 보인다")
                .isEqualTo(NOW.minus(AiCallQuota.WINDOW));
    }

    @Test
    @DisplayName("층 코드는 DB·API 가 함께 쓰는 표기다")
    void layerCodesAreTheSharedVocabulary() {
        assertThat(AiCallQuota.Layer.USER.code()).isEqualTo("quota_user");
        assertThat(AiCallQuota.Layer.IP.code()).isEqualTo("quota_ip");
        assertThat(AiCallQuota.Layer.GLOBAL.code()).isEqualTo("quota_global");
    }

    @Test
    @DisplayName("기본 상한은 문서에 적은 값 그대로고, 전역이 IP 보다 높다")
    void defaultLimitsAreTheDocumentedOnes() {
        assertThat(AiCallQuota.DEFAULT_PER_USER_DAILY).isEqualTo("100");
        assertThat(AiCallQuota.DEFAULT_PER_DEMO_USER_DAILY).isEqualTo("30");
        assertThat(AiCallQuota.DEFAULT_PER_IP_DAILY).isEqualTo("300");
        assertThat(AiCallQuota.DEFAULT_GLOBAL_DAILY).isEqualTo("500");

        assertThat(Integer.parseInt(AiCallQuota.DEFAULT_PER_DEMO_USER_DAILY))
                .as("데모는 회원가입 없이 무제한 발급되므로 일반 사용자보다 낮아야 한다")
                .isLessThan(Integer.parseInt(AiCallQuota.DEFAULT_PER_USER_DAILY));
        assertThat(Integer.parseInt(AiCallQuota.DEFAULT_PER_IP_DAILY))
                .as("IP 는 공인 IP 를 공유하는 사무실·가족을 오탐하지 않게 사용자보다 높다")
                .isGreaterThan(Integer.parseInt(AiCallQuota.DEFAULT_PER_USER_DAILY));
    }

    @Test
    void 인자가_null_이면_실패한다() {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        assertThatThrownBy(() ->
                new AiCallQuota(null, clock, PER_USER, PER_DEMO_USER, PER_IP, GLOBAL))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() ->
                new AiCallQuota(aiCallLogRepository, null, PER_USER, PER_DEMO_USER, PER_IP, GLOBAL))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> quota().exceededLayer(null, false, CLIENT_IP))
                .isInstanceOf(NullPointerException.class);
    }
}
