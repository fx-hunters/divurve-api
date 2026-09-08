package com.divurve.domain.auth;

import com.divurve.common.architecture.UseCase;
import com.divurve.common.exception.DuplicateResourceException;
import com.divurve.common.exception.UnauthorizedException;
import com.divurve.domain.port.AuthTokens;
import com.divurve.domain.port.AuthPrincipal;
import com.divurve.domain.port.TokenProvider;
import com.divurve.domain.user.UserRepository;
import com.divurve.domain.user.entity.User;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.annotation.Transactional;

/**
 * 회원가입·로그인·토큰 갱신 유스케이스 (이슈 #22). 비밀번호 해싱(BCrypt, NFR-SE-01),
 * 중복 검증, 토큰 발급을 통합한다.
 *
 * <p>데모 계정(AuthDemoService)과 달리, 일반 회원은 passwordHash를 가지며,
 * 이메일·비밀번호로 인증한다.
 *
 * <p>refresh 메서드는 기존 리프레시 토큰 검증 후 새 액세스 토큰만 발급한다.
 *
 * <p>가입·로그인·갱신이 성공하면 {@code users.last_login_at}/{@code last_login_ip} 를 남긴다
 * (이슈 #111). 실패한 시도는 남기지 않는다 — 마지막 값 하나로는 실패를 표현할 수 없고,
 * 그러려면 별도 이력 테이블이 필요하다. 그래서 login/refresh 는 더 이상 readOnly 트랜잭션이 아니다.
 *
 * <p>로그인·갱신 결과에는 {@code onboarded}(초기 설정 완료 여부)가 함께 실린다 — 클라이언트가 초기 설정으로
 * 보낼지 홈으로 보낼지 이 값 하나로 결정한다(API 명세 v2 §3, FR-IS-01·FR-IS-07).
 *
 * <p><b>가입 직후 샘플 자산 시드는 한시적이다</b>(이슈 #108). 원래 온보딩 2단계는 금융기관에서 사용자의
 * 실제 자산을 불러와야 하지만, MVP 범위에 실연동이 없다(명세 §241 "마이데이터·증권사 연동은 구현하지
 * 않는다"). 그동안 가입 계정을 빈 상태로 두면 온보딩 2단계부터 홈·X-ray·플랜까지 전부 빈 화면이 되므로
 * {@link SampleDataSeeder} 로 데모와 같은 샘플을 넣는다. 실연동이 도착하면 이 호출과
 * {@code app.onboarding.seed-sample-assets-on-signup} 플래그를 함께 걷어낸다.
 *
 * <p>시드를 넣어도 {@code onboarded} 는 {@code false} 다 — 자산이 있다는 것과 사용자가 초기 설정을
 * 끝냈다는 것은 다른 사실이며, 온보딩 화면 자체를 건너뛰면 안 된다(FR-IS-01).
 */
@UseCase
public class AuthService {

    private static final String EMAIL_FIELD = "email";
    private static final String DUPLICATE_EMAIL_MESSAGE = "이미 사용 중인 이메일입니다.";

    /**
     * 로그인 실패 시 계정 존재 여부와 무관하게 노출하는 단일 메시지 (사용자 열거 방지, 이슈 #61).
     * "없는 이메일"과 "비밀번호 불일치"는 상태코드·메시지 모두 이 문자열 하나로만 응답한다.
     */
    private static final String INVALID_CREDENTIALS_MESSAGE = "이메일 또는 비밀번호가 올바르지 않습니다.";

    private static final String INVALID_REFRESH_TOKEN_MESSAGE = "유효하지 않거나 만료된 리프레시 토큰입니다.";

    /** bcrypt 비교 자체를 항상 수행해, 계정 존재 여부가 응답 시간으로 새는 것도 함께 막는다. */
    private static final String DUMMY_PASSWORD_FOR_TIMING_SAFETY = "dummy-password-for-timing-safety";

    private final UserRepository userRepository;
    private final TokenProvider tokenProvider;
    private final SampleDataSeeder sampleDataSeeder;
    private final boolean seedSampleAssetsOnSignup;
    private final Clock clock;
    private final BCryptPasswordEncoder passwordEncoder;
    private final String dummyPasswordHash;

    public AuthService(
            UserRepository userRepository,
            TokenProvider tokenProvider,
            SampleDataSeeder sampleDataSeeder,
            @Value("${app.onboarding.seed-sample-assets-on-signup:true}") boolean seedSampleAssetsOnSignup,
            Clock clock) {
        this.userRepository = Objects.requireNonNull(userRepository, "userRepository");
        this.tokenProvider = Objects.requireNonNull(tokenProvider, "tokenProvider");
        this.sampleDataSeeder = Objects.requireNonNull(sampleDataSeeder, "sampleDataSeeder");
        this.seedSampleAssetsOnSignup = seedSampleAssetsOnSignup;
        this.clock = Objects.requireNonNull(clock, "clock");
        this.passwordEncoder = new BCryptPasswordEncoder();
        this.dummyPasswordHash = passwordEncoder.encode(DUMMY_PASSWORD_FOR_TIMING_SAFETY);
    }

    /**
     * 회원가입: 이메일 중복 검사 → 비밀번호 BCrypt 해시 → User 저장 → 샘플 자산 시드(한시적) → 토큰 발급.
     *
     * @param email 이메일
     * @param password 평문 비밀번호
     * @param name 사용자 이름
     * @param clientIp 접속 IP. 알 수 없으면 {@code null}
     * @return 발급된 액세스·리프레시 토큰
     * @throws DuplicateResourceException 이메일이 이미 가입돼 있을 때 (409)
     */
    @Transactional
    public AuthTokens signup(String email, String password, String name, String clientIp) {
        Objects.requireNonNull(email, "email must not be null");
        Objects.requireNonNull(password, "password must not be null");
        Objects.requireNonNull(name, "name must not be null");

        if (userRepository.findByEmail(email).isPresent()) {
            throw new DuplicateResourceException(DUPLICATE_EMAIL_MESSAGE, EMAIL_FIELD);
        }

        String passwordHash = passwordEncoder.encode(password);
        User user = User.create(email, name, passwordHash);
        // 가입 직후 곧바로 토큰을 발급하므로 이 시점이 첫 접속이다 (이슈 #111).
        user.recordLogin(Instant.now(clock), clientIp);
        User savedUser = userRepository.save(user);

        // 실연동이 도착할 때까지의 임시 조치 — 클래스 javadoc 참고.
        if (seedSampleAssetsOnSignup) {
            sampleDataSeeder.seed(savedUser);
        }

        return tokenProvider.issue(savedUser.getId(), false);
    }

    /**
     * 로그인: 이메일로 User 조회 → 비밀번호 검증 → 토큰 발급.
     *
     * @param email 이메일
     * @param password 평문 비밀번호
     * @param clientIp 접속 IP. 알 수 없으면 {@code null}
     * @return 발급된 토큰과 초기 설정 완료 여부
     * @throws UnauthorizedException 이메일이 없거나 비밀번호가 틀렸을 때 (401). 사용자 열거를 막기 위해
     *                                두 경우를 구분하지 않고 같은 메시지로 던진다(이슈 #61) — 이메일이
     *                                없어도 더미 해시로 bcrypt 비교를 수행해 응답 시간도 동일하게 만든다.
     */
    @Transactional
    public AuthResult login(String email, String password, String clientIp) {
        Objects.requireNonNull(email, "email must not be null");
        Objects.requireNonNull(password, "password must not be null");

        Optional<User> user = userRepository.findByEmail(email);
        boolean passwordMatches =
                passwordEncoder.matches(password, user.map(User::getPasswordHash).orElse(dummyPasswordHash));

        if (user.isEmpty() || !passwordMatches) {
            throw new UnauthorizedException(INVALID_CREDENTIALS_MESSAGE);
        }

        User authenticatedUser = user.get();
        authenticatedUser.recordLogin(Instant.now(clock), clientIp);
        return new AuthResult(
                tokenProvider.issue(authenticatedUser.getId(), false),
                authenticatedUser.isOnboarded());
    }

    /**
     * 토큰 갱신: 리프레시 토큰 검증 → 새 액세스 토큰 발급.
     * 리프레시 토큰은 재사용되며, 새 리프레시 토큰은 발급하지 않는다.
     *
     * @param refreshToken 리프레시 토큰
     * @param clientIp 접속 IP. 알 수 없으면 {@code null}
     * @return 새 액세스 토큰과 초기 설정 완료 여부 (refreshToken 필드는 입력 값과 동일)
     * @throws UnauthorizedException 리프레시 토큰이 위조·만료됐거나, 그 사용자가 더 이상
     *         존재하지 않을 때 (401) — 데모 정리(이슈 #138)로 지워진 계정이 그 경우다
     */
    @Transactional
    public AuthResult refreshAccessToken(String refreshToken, String clientIp) {
        Objects.requireNonNull(refreshToken, "refreshToken must not be null");

        AuthPrincipal authPrincipal = tokenProvider.verifyRefreshToken(refreshToken)
                .orElseThrow(() -> new UnauthorizedException(INVALID_REFRESH_TOKEN_MESSAGE));
        AuthTokens newTokens = tokenProvider.issue(authPrincipal.userId(), authPrincipal.isDemo());

        // 사용자가 없으면 401 이다 (이슈 #138). 예전에는 없는 사용자도 onboarded=false 로 통과해
        // 새 액세스 토큰을 받았다 — 데모 정리로 계정이 지워진 뒤에도 리프레시 토큰 수명(14일)
        // 동안 갱신이 계속 성공하고, 그 토큰으로 오는 요청은 401 이 아니라 "빈 데이터로 200" 이
        // 된다. 인증은 통과하는데 소유 데이터만 전부 비어 있는 상태라, 화면에서는 로그아웃도
        // 아니고 오류도 아닌 것으로 보여 원인 추적이 매우 어렵다.
        //
        // 조회는 이미 하고 있었으므로 추가 비용은 없다. 요청마다 사용자 존재를 확인하는 방식은
        // 택하지 않았다 — 보존 기간(1일)이 액세스 토큰 TTL(30분)보다 길어 삭제된 계정의 액세스
        // 토큰은 이미 만료돼 있고, 갱신만 막으면 새 토큰이 나가지 않는다.
        User authenticatedRefreshUser = userRepository.findById(authPrincipal.userId())
                .orElseThrow(() -> new UnauthorizedException(INVALID_REFRESH_TOKEN_MESSAGE));

        // 갱신도 접속이다 — 앱을 계속 쓰는 사용자는 로그인을 다시 하지 않으므로,
        // 갱신을 세지 않으면 "마지막 접속" 이 최초 로그인 시각에 멈춘다. 이 기록이
        // 데모 정리(이슈 #138)의 판정 근거이기도 하다: 쓰고 있는 세션은 대상이 되지 않는다.
        authenticatedRefreshUser.recordLogin(Instant.now(clock), clientIp);
        boolean onboarded = authenticatedRefreshUser.isOnboarded();

        // 기존 리프레시 토큰 유지 (클라이언트는 리프레시 토큰 교체 필요 없음)
        return new AuthResult(
                new AuthTokens(newTokens.accessToken(), refreshToken, newTokens.accessTokenTtlSeconds()),
                onboarded);
    }

    /**
     * 인증 결과 — 토큰과 초기 설정 완료 여부.
     *
     * @param tokens    발급된 액세스·리프레시 토큰
     * @param onboarded {@code users.onboarded_at} 이 기록됐는지. false 면 클라이언트가 초기 설정으로 보낸다
     */
    public record AuthResult(AuthTokens tokens, boolean onboarded) {
    }
}
