package com.divurve.domain.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.divurve.domain.port.AuthTokens;
import com.divurve.domain.port.TokenProvider;
import com.divurve.domain.user.UserRepository;
import com.divurve.domain.user.entity.User;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

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

    private AuthDemoService authDemoService;

    @BeforeEach
    void setUp() {
        authDemoService = new AuthDemoService(userRepository, sampleDataSeeder, tokenProvider);
    }

    @Test
    void createDemoSession_은_데모_유저를_만들고_샘플을_시드한_뒤_데모_토큰을_발급한다() {
        AuthTokens issued = new AuthTokens("access", "refresh", 1800L);
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
        when(tokenProvider.issue(any(), eq(true))).thenReturn(issued);

        AuthTokens result = authDemoService.createDemoSession();

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

        authDemoService.createDemoSession();
        authDemoService.createDemoSession();

        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        verify(userRepository, times(2)).save(userCaptor.capture());
        List<User> created = userCaptor.getAllValues();
        assertThat(created.get(0).getEmail()).isNotEqualTo(created.get(1).getEmail());
    }
}
