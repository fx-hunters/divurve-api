package com.divurve.domain.auth;

import com.divurve.common.architecture.UseCase;
import com.divurve.domain.port.AuthTokens;
import com.divurve.domain.port.TokenProvider;
import com.divurve.domain.user.UserRepository;
import com.divurve.domain.user.entity.User;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;
import java.time.Clock;
import java.time.Instant;

/**
 * 데모 세션 발급 유스케이스 (이슈 #9, FR-ON-07). 회원가입 없이 샘플 데이터가 채워진 데모 계정을 만들고
 * 토큰을 발급한다. 데모 유저는 매 호출마다 고유 이메일로 새로 만들어 시연 간 데이터가 섞이지 않게 한다.
 *
 * <p><b>격리를 세션 단위로 두는 이유</b> — 데모 계정에는 쓰기 차단이 없어 보유 종목·목표를 실제로
 * 추가·수정할 수 있다. 고정된 단일 데모 계정을 공유하면 동시 시연 중 서로의 데이터를 침범한다.
 *
 * <p>계산 로직은 없다 — 유저 생성과 토큰 발급뿐이다. 시드 값은 {@link DemoSampleData} 한 곳에 모여 있고
 * ({@code 이슈 #78}), 복제는 {@link SampleDataSeeder} 가 한다 — 같은 시드를 일반 가입 계정도 임시로
 * 쓰기 때문이다(이슈 #108).
 */
@UseCase
public class AuthDemoService {

    private final UserRepository userRepository;
    private final SampleDataSeeder sampleDataSeeder;
    private final TokenProvider tokenProvider;
    private final Clock clock;

    public AuthDemoService(
            UserRepository userRepository,
            SampleDataSeeder sampleDataSeeder,
            TokenProvider tokenProvider,
            Clock clock) {
        this.userRepository = userRepository;
        this.sampleDataSeeder = sampleDataSeeder;
        this.tokenProvider = tokenProvider;
        this.clock = clock;
    }

    /** 데모 유저를 생성하고 샘플 데이터를 시드한 뒤, {@code is_demo=true} 토큰을 발급해 반환한다. */
    @Transactional
    public AuthTokens createDemoSession(String clientIp) {
        User demoUser = User.createDemo(newDemoEmail(), DemoSampleData.USER_NAME);
        // 데모 계정은 발급이 곧 접속이다 (이슈 #111). 관리자 목록에서 데모 세션이 언제·어디서
        // 열렸는지 보이지 않으면, 쌓인 데모 행이 무엇인지 알 방법이 없다.
        demoUser.recordLogin(Instant.now(clock), clientIp);
        demoUser = userRepository.save(demoUser);
        sampleDataSeeder.seed(demoUser);
        return tokenProvider.issue(demoUser.getId(), true);
    }

    private String newDemoEmail() {
        return DemoSampleData.EMAIL_PREFIX + UUID.randomUUID() + DemoSampleData.EMAIL_DOMAIN;
    }
}
