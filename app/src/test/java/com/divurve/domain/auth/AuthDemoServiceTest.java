package com.divurve.domain.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.divurve.common.exception.ForbiddenException;
import com.divurve.domain.port.AuthTokens;
import com.divurve.domain.port.TokenProvider;
import com.divurve.domain.user.UserRepository;
import com.divurve.domain.user.entity.User;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import java.time.ZoneOffset;
import java.time.Duration;
import java.time.Instant;
import java.time.Clock;

/**
 * {@link AuthDemoService} 단위 테스트 — 데모 유저 생성·시드 위임·토큰 발급의 협력을 검증한다.
 *
 * <p>무엇이 시드되는지는 보지 않는다. 복제는 {@link SampleDataSeeder} 의 몫이고
 * ({@link SampleDataSeederTest}), 값이 시연 시나리오를 만족하는지는 {@link DemoSampleDataTest} 가
 * 본다(이슈 #78·#108).
 */
@ExtendWith(MockitoExtension.class)
class AuthDemoServiceTest {

    @Mock
    private UserRepository userRepository;
    @Mock
    private SampleDataSeeder sampleDataSeeder;
    @Mock
    private TokenProvider tokenProvider;

    /** 접속 기록(이슈 #111) 확인용 고정 IP·시각. */
    private static final String CLIENT_IP = "198.51.100.7";

    private static final Clock TEST_CLOCK =
            Clock.fixed(Instant.parse("2026-09-07T12:00:00Z"), ZoneOffset.UTC);

    /** 발급 상한 (이슈 #140). 경계를 눈에 보이게 낮춰 둔다 — 기본값 20 은 테스트에서 다루기 멀다. */
    private static final int ISSUANCE_LIMIT = 3;

    private AuthDemoService authDemoService;

    @BeforeEach
    void setUp() {
        authDemoService = new AuthDemoService(userRepository, sampleDataSeeder, tokenProvider, TEST_CLOCK,
                ISSUANCE_LIMIT);
    }

    @Test
    void createDemoSession_은_데모_유저를_만들고_샘플을_시드한_뒤_데모_토큰을_발급한다() {
        AuthTokens issued = new AuthTokens("access", "refresh", 1800L);
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
        when(tokenProvider.issue(any(), eq(true))).thenReturn(issued);

        AuthTokens result = authDemoService.createDemoSession(CLIENT_IP);

        assertThat(result).isSameAs(issued);

        // 데모 유저는 고유 이메일 + is_demo=true 로 생성된다.
        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(userCaptor.capture());
        User demoUser = userCaptor.getValue();
        assertThat(demoUser.isDemo()).isTrue();
        assertThat(demoUser.getName()).isEqualTo(DemoSampleData.USER_NAME);
        assertThat(demoUser.getEmail())
                .startsWith(DemoSampleData.EMAIL_PREFIX)
                .endsWith(DemoSampleData.EMAIL_DOMAIN);

        // 시드는 저장된 유저를 대상으로, 데모 계정에서는 플래그와 무관하게 항상 들어간다.
        verify(sampleDataSeeder).seed(demoUser);

        // 토큰은 데모 플래그(true)로 발급된다.
        verify(tokenProvider).issue(any(), eq(true));
    }

    @Test
    void 데모_이메일은_호출마다_달라_시연_간_데이터가_섞이지_않는다() {
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
        when(tokenProvider.issue(any(), eq(true))).thenReturn(new AuthTokens("a", "r", 1L));

        authDemoService.createDemoSession(CLIENT_IP);
        authDemoService.createDemoSession(CLIENT_IP);

        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        verify(userRepository, times(2)).save(userCaptor.capture());
        List<User> created = userCaptor.getAllValues();
        assertThat(created.get(0).getEmail()).isNotEqualTo(created.get(1).getEmail());
    }

    // ---------------------------------------------------------------------
    // 발급 IP당 상한 (이슈 #140)
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("상한 미만이면 발급한다")
    void issuesBelowTheLimit() {
        when(userRepository.countDemoUsersFromIpSince(eq(CLIENT_IP), any()))
                .thenReturn((long) ISSUANCE_LIMIT - 1);
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
        when(tokenProvider.issue(any(), eq(true)))
                .thenReturn(new AuthTokens("access", "refresh", 1800L));

        assertThat(authDemoService.createDemoSession(CLIENT_IP)).isNotNull();
    }

    @Test
    @DisplayName("상한 이상이면 403 이고 계정을 만들지 않는다")
    void blocksAtTheLimitWithoutCreatingAnything() {
        when(userRepository.countDemoUsersFromIpSince(eq(CLIENT_IP), any()))
                .thenReturn((long) ISSUANCE_LIMIT);

        assertThatThrownBy(() -> authDemoService.createDemoSession(CLIENT_IP))
                .as("§1.3 에러코드가 닫힌 집합이라 429 를 새로 만들지 않는다")
                .isInstanceOf(ForbiddenException.class);

        // 막았는데 행이 생기면 상한이 데이터 증가를 막지 못한다.
        verify(userRepository, never()).save(any(User.class));
        verify(sampleDataSeeder, never()).seed(any(User.class));
        verify(tokenProvider, never()).issue(any(), anyBoolean());
    }

    @Test
    @DisplayName("창은 1시간이다 — 발급은 몰아치는 쪽이 문제다")
    void countsOnlyTheLastHour() {
        when(userRepository.countDemoUsersFromIpSince(eq(CLIENT_IP), any())).thenReturn(0L);
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
        when(tokenProvider.issue(any(), eq(true)))
                .thenReturn(new AuthTokens("access", "refresh", 1800L));

        authDemoService.createDemoSession(CLIENT_IP);

        ArgumentCaptor<Instant> since = ArgumentCaptor.forClass(Instant.class);
        verify(userRepository).countDemoUsersFromIpSince(eq(CLIENT_IP), since.capture());
        assertThat(since.getValue())
                .isEqualTo(TEST_CLOCK.instant().minus(AuthDemoService.ISSUANCE_WINDOW));
    }

    @Test
    @DisplayName("IP 를 모르면 상한을 적용하지 않는다 — 출처 불명끼리 서로를 막지 않는다")
    void unknownIpIsNotRateLimited() {
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
        when(tokenProvider.issue(any(), eq(true)))
                .thenReturn(new AuthTokens("access", "refresh", 1800L));

        assertThat(authDemoService.createDemoSession(null)).isNotNull();

        // 프록시 뒤의 정상 사용자들을 한 바구니로 묶어 차단하는 피해가, 우회를 조금 더 막는
        // 이득보다 크다. 그래서 아예 세지 않는다.
        verify(userRepository, never()).countDemoUsersFromIpSince(any(), any());
    }

    @Test
    @DisplayName("기본 상한은 문서에 적은 값 그대로다")
    void defaultIssuanceLimitIsTheDocumentedOne() {
        assertThat(AuthDemoService.DEFAULT_ISSUANCE_PER_IP).isEqualTo("20");
        assertThat(AuthDemoService.ISSUANCE_WINDOW).isEqualTo(Duration.ofHours(1));
    }
}
