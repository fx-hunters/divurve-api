package com.divurve.domain.auth;

import com.divurve.common.architecture.UseCase;
import com.divurve.common.exception.ForbiddenException;
import com.divurve.domain.port.AuthTokens;
import com.divurve.domain.port.TokenProvider;
import com.divurve.domain.user.UserRepository;
import com.divurve.domain.user.entity.User;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.transaction.annotation.Transactional;
import java.time.Clock;
import java.time.Duration;
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
 *
 * <p><b>발급에 IP당 상한이 있다</b>(이슈 #140). 데모 세션은 회원가입 없이 발급되므로, 상한이 없으면
 * 데모 토큰을 반복 발급해 <b>사용자당 AI 쿼터를 우회</b>할 수 있다 — 토큰마다 다른 사용자이므로
 * 사용자 층 카운트가 매번 0 에서 시작한다. 이 상한이 그 우회의 단가를 올린다.
 *
 * <p><b>이것도 과속방지턱이다.</b> 근거가 {@code X-Forwarded-For} 헤더인데 그 값은 클라이언트가
 * 위조할 수 있다. 위조 불가능한 상한은 AI
 * 쪽의 전역 킬스위치({@code AiCallQuota.Layer#GLOBAL})다 — 데모를 몇 개 발급하든 그쪽은 우회되지
 * 않는다. 그리고 쌓인 데모 행 자체는 일일 정리(이슈 #138)가 걷어낸다.
 *
 * <p><b>429 가 아니라 403 이다.</b> 명세 §1.3 의 에러코드는 6종 닫힌 집합이고 거기에 레이트리밋용
 * 코드가 없다. 새 코드를 만들면 프론트의 에러 처리에 분기가 생기는 API 계약 변경이 되는데, 이
 * 이슈는 계약을 바꾸지 않기로 했다. {@code FORBIDDEN} 은 "형식은 맞지만 지금은 거절한다" 에
 * 가장 가까운 기존 값이다.
 */
@UseCase
public class AuthDemoService {

    /**
     * IP 1개의 발급 상한. 시연에서 한 사무실·한 강의실에서 여러 명이 동시에 데모를 여는 것을
     * 막지 않을 만큼 넉넉하되, 무한은 아니다.
     */
    static final String DEFAULT_ISSUANCE_PER_IP = "20";

    /** 발급 카운트 창. AI 쿼터(24시간)보다 짧다 — 발급은 순간적으로 몰아치는 쪽이 문제다. */
    static final Duration ISSUANCE_WINDOW = Duration.ofHours(1);

    private static final Logger log = LoggerFactory.getLogger(AuthDemoService.class);

    private final UserRepository userRepository;
    private final SampleDataSeeder sampleDataSeeder;
    private final TokenProvider tokenProvider;
    private final Clock clock;
    private final int issuancePerIp;

    public AuthDemoService(
            UserRepository userRepository,
            SampleDataSeeder sampleDataSeeder,
            TokenProvider tokenProvider,
            Clock clock,
            @Value("${app.demo.issuance.per-ip-hourly:" + DEFAULT_ISSUANCE_PER_IP + "}")
            int issuancePerIp) {
        this.userRepository = userRepository;
        this.sampleDataSeeder = sampleDataSeeder;
        this.tokenProvider = tokenProvider;
        this.clock = clock;
        this.issuancePerIp = issuancePerIp;
    }

    /**
     * 데모 유저를 생성하고 샘플 데이터를 시드한 뒤, {@code is_demo=true} 토큰을 발급해 반환한다.
     *
     * @param clientIp 요청 출처 IP. 알 수 없으면 {@code null} — 그때는 발급 상한을 적용하지 않는다
     * @return 발급된 토큰
     * @throws ForbiddenException 이 IP 의 발급 상한을 넘었을 때 (403, 이슈 #140)
     */
    @Transactional
    public AuthTokens createDemoSession(String clientIp) {
        requireIssuanceQuota(clientIp);

        User demoUser = User.createDemo(newDemoEmail(), DemoSampleData.USER_NAME);
        // 데모 계정은 발급이 곧 접속이다 (이슈 #111). 관리자 목록에서 데모 세션이 언제·어디서
        // 열렸는지 보이지 않으면, 쌓인 데모 행이 무엇인지 알 방법이 없다.
        demoUser.recordLogin(Instant.now(clock), clientIp);
        demoUser = userRepository.save(demoUser);
        sampleDataSeeder.seed(demoUser);
        return tokenProvider.issue(demoUser.getId(), true);
    }

    /**
     * 이 IP 의 발급 상한을 확인한다 (이슈 #140).
     *
     * <p>IP 를 모르면 통과시킨다. 모르는 출처를 하나로 묶으면 프록시 뒤의 정상 사용자들이 서로를
     * 차단하게 되는데, 그 피해가 우회를 조금 더 막는 이득보다 크다.
     */
    private void requireIssuanceQuota(String clientIp) {
        if (clientIp == null) {
            return;
        }
        Instant since = Instant.now(clock).minus(ISSUANCE_WINDOW);
        long issued = userRepository.countDemoUsersFromIpSince(clientIp, since);
        if (issued >= issuancePerIp) {
            log.warn("데모 발급 상한 초과 — 거절한다. issued={} limit={} window={}",
                    issued, issuancePerIp, ISSUANCE_WINDOW);
            throw new ForbiddenException(
                    "이 네트워크에서 짧은 시간에 너무 많은 데모 세션이 열렸습니다. "
                            + "잠시 후 다시 시도하거나 회원가입 후 이용해 주세요.");
        }
    }

    private String newDemoEmail() {
        return DemoSampleData.EMAIL_PREFIX + UUID.randomUUID() + DemoSampleData.EMAIL_DOMAIN;
    }
}
