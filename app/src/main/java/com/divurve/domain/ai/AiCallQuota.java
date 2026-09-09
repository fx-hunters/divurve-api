package com.divurve.domain.ai;

import com.divurve.common.architecture.UseCase;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.transaction.annotation.Transactional;

/**
 * AI 서술 호출의 총량 상한 (이슈 #140).
 *
 * <p><b>무엇을 막는가.</b> 요청당 타임아웃(5s)·총예산(8s)·SDK 재시도 0 은 <b>1건의 지연과 중복</b>을
 * 막는 장치다. 입력 상한과 응답 캐시(이슈 #139)는 <b>1건당 비용과 중복 요청</b>을 줄인다. 둘 다
 * 총량은 막지 못한다 — 토큰만 있으면 호출 횟수에 상한이 없었고, 데모 세션은 회원가입 없이 무제한
 * 발급되므로 데모 토큰을 반복 발급하며 부르면 상한이 사실상 없었다.
 *
 * <p><b>카운터를 따로 만들지 않는다.</b> {@code ai_call_logs}(이슈 #143)를 그대로 센다. 인메모리
 * 카운터는 재시작마다 리셋되고 인스턴스가 갈리면 근거가 사라진다. 그리고 남기는 기록이 곧 쿼터의
 * 근거여야 관리자 화면의 숫자와 차단 판정이 어긋나지 않는다 — 두 벌을 두면 반드시 갈린다.
 *
 * <p><b>세 층의 신뢰도가 다르다.</b> 사용자 층과 전역 층은 토큰·행 수에 기대므로 위조할 수 없다.
 * IP 층은 {@code X-Forwarded-For} 에 기대는데 <b>그 헤더는 클라이언트가 마음대로 보낼 수 있다</b>
 * ({@code ClientIpArgumentResolver} 가 같은 이유로 "차단 판단에 쓰지 않는다" 고 적어 두었고, 이
 * 클래스가 그 방침을 IP 층 한 곳에서만 의도적으로 뒤집는다). 그래서 IP 층은 실수로 도는 루프와
 * 소박한 스크립트를 막는 <b>과속방지턱</b>이고, 헤더를 바꿔 가며 부르는 상대에게는 뚫린다. 그
 * 상대를 막는 것은 <b>전역 킬스위치</b>다 — 그쪽은 무엇을 위조해도 우회할 수 없다.
 *
 * <p><b>창은 달력상의 하루가 아니라 지난 24시간이다.</b> 이유 둘 — (a) 관리자 화면의 일자 집계는
 * UTC 로 자르는데({@code AiCallLogRepository#summarize}) 서비스 시계는 KST 라, "오늘" 로 세면
 * 운영자가 화면의 숫자와 쿼터 잔량을 비교할 때 9시간 동안 서로 다른 값을 본다. (b) 자정을 기다려
 * 상한을 두 배로 쓰는 우회가 없다. 대신 "언제 풀리는가" 가 한 시점이 아니라 오래된 호출이 창을
 * 빠져나가며 서서히 회복된다 — 비용 상한으로서는 그편이 맞다.
 */
@UseCase
public class AiCallQuota {

    /**
     * 일반 사용자 1명의 24시간 상한. 예측·홈 화면을 하루에 여러 번 열어도 10~20건 수준이므로
     * 다섯 배 여유다. 넉넉하지만 무한은 아니다.
     */
    static final String DEFAULT_PER_USER_DAILY = "100";

    /**
     * 데모 세션 1개의 상한. 데모는 회원가입 없이 무제한 발급되므로 낮게 잡는다 — 시연에서 화면
     * 몇 개를 여는 데 필요한 양이면 충분하다.
     */
    static final String DEFAULT_PER_DEMO_USER_DAILY = "30";

    /**
     * IP 1개의 상한. 사용자 상한보다 높다 — 가족·사무실처럼 하나의 공인 IP 를 여럿이 쓰는 경우를
     * 오탐으로 막지 않기 위해서다.
     *
     * <p><b>전역 상한보다 낮게 두는 것이 의도다.</b> {@code X-Forwarded-For} 가 없는 환경에서는
     * 모든 요청이 프록시 주소 하나로 접혀 이 층이 사실상 두 번째 전역 상한이 된다. 그때 걸리는
     * 결과는 "전건 폴백" 인데, 그건 전역 킬스위치가 의도하는 상태와 같은 종류다 — 조금 낮은
     * 문턱에서 같은 일이 일어나는 것이므로 피해가 새로 생기지는 않는다.
     */
    static final String DEFAULT_PER_IP_DAILY = "300";

    /**
     * 서비스 전체의 24시간 상한 — <b>킬스위치</b>다. 넘으면 프로바이더 호출을 끊고 전건 폴백한다.
     *
     * <p>{@code narrate} 와 {@code extract} 를 <b>함께</b> 센다. 이것은 가용성 장치가 아니라 비용
     * 상한이고, 지출은 두 경로에서 함께 나간다 — 배치가 그날 예산을 다 썼으면 서술도 멈추는 것이
     * 맞다. 한쪽만 세는 킬스위치는 절반의 지출을 못 보는 킬스위치다.
     */
    static final String DEFAULT_GLOBAL_DAILY = "500";

    /**
     * 추출(배치) 전용 24시간 상한 (이슈 #178). <b>전역 상한보다 낮게 두는 것이 핵심이다.</b>
     *
     * <p>추출과 서술은 전역 상한을 공유하는데, 예전에는 서술만 그 상한을 검사했다. 그래서 배치가
     * 새벽에 예산을 먹으면 아침에 접속한 <b>사용자가</b> 템플릿 문장을 보고, 정작 배치는 계속
     * 돌았다 — 브레이크가 잘못된 쪽에 달려 있었다.
     *
     * <p>배치에 더 낮은 상한을 따로 두면 배치가 먼저 멈추고 나머지가 사용자 몫으로 남는다.
     * 배치는 사람이 안 볼 때 도는 것이라 다음 창으로 밀려도 되지만, 서술은 지금 화면을 보고 있는
     * 사람에게 나간다.
     */
    static final String DEFAULT_EXTRACT_DAILY = "150";

    /** 카운트 창. 위 상한들이 "24시간 안에 몇 건" 인지를 정한다. */
    static final Duration WINDOW = Duration.ofHours(24);

    /**
     * IP 를 모를 때 카운트 쿼리에 넘기는 값. {@code null} 을 넘기지 않는 이유는 타입 없는 바인드
     * 파라미터 함정(이슈 #118) 때문이고, 빈 문자열이 안전한 이유는 저장되는 값이 실제 IP 또는
     * {@code null} 뿐이어서 어떤 행과도 일치하지 않기 때문이다.
     */
    private static final String UNKNOWN_IP = "";

    /**
     * 배치에는 사용자가 없다. 전역 건수만 필요한데 집계 쿼리가 {@code userId} 를 요구하므로,
     * 어떤 행과도 일치하지 않는 고정 id 를 넘겨 사용자 층 카운트를 0 으로 만든다.
     */
    private static final UUID SYSTEM_USER = new UUID(0L, 0L);

    private static final Logger log = LoggerFactory.getLogger(AiCallQuota.class);

    private final AiCallLogRepository aiCallLogRepository;
    private final Clock clock;
    private final int perUserDaily;
    private final int perDemoUserDaily;
    private final int perIpDaily;
    private final int globalDaily;
    private final int extractDaily;

    public AiCallQuota(
            AiCallLogRepository aiCallLogRepository,
            Clock clock,
            @Value("${app.external.anthropic.quota.per-user-daily:"
                    + DEFAULT_PER_USER_DAILY + "}") int perUserDaily,
            @Value("${app.external.anthropic.quota.per-demo-user-daily:"
                    + DEFAULT_PER_DEMO_USER_DAILY + "}") int perDemoUserDaily,
            @Value("${app.external.anthropic.quota.per-ip-daily:"
                    + DEFAULT_PER_IP_DAILY + "}") int perIpDaily,
            @Value("${app.external.anthropic.quota.global-daily:"
                    + DEFAULT_GLOBAL_DAILY + "}") int globalDaily,
            @Value("${app.external.anthropic.quota.extract-daily:"
                    + DEFAULT_EXTRACT_DAILY + "}") int extractDaily) {
        this.aiCallLogRepository =
                Objects.requireNonNull(aiCallLogRepository, "aiCallLogRepository");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.perUserDaily = perUserDaily;
        this.perDemoUserDaily = perDemoUserDaily;
        this.perIpDaily = perIpDaily;
        this.globalDaily = globalDaily;
        this.extractDaily = extractDaily;
    }

    /**
     * 이 요청이 상한을 넘는지 본다.
     *
     * <p>층 순서는 <b>구체적인 것부터</b>다 — 사용자 → IP → 전역. 사용자 한 명이 자기 몫을 다
     * 쓴 것과 서비스 전체가 멈춘 것은 운영상 완전히 다른 사건이므로, 둘이 겹칠 때 더 좁은 원인을
     * 보고해야 한다.
     *
     * @param userId   요청한 사용자
     * @param demo     데모 세션의 요청인지 — 더 낮은 상한을 적용한다
     * @param clientIp 요청 출처 IP. 모르면 {@code null} — IP 층을 건너뛴다
     * @return 넘긴 층. 통과하면 빈 값
     */
    @Transactional(readOnly = true)
    public Optional<Layer> exceededLayer(UUID userId, boolean demo, String clientIp) {
        Objects.requireNonNull(userId, "userId");

        Instant since = clock.instant().minus(WINDOW);
        Object[] counts = aiCallLogRepository
                .countChargeableSince(userId, clientIp == null ? UNKNOWN_IP : clientIp, since)
                .get(0);

        long userCalls = ((Number) counts[0]).longValue();
        long ipCalls = ((Number) counts[1]).longValue();
        long globalCalls = ((Number) counts[2]).longValue();

        int userLimit = demo ? perDemoUserDaily : perUserDaily;
        if (userCalls >= userLimit) {
            return blocked(Layer.USER, userCalls, userLimit);
        }
        if (ipCalls >= perIpDaily) {
            return blocked(Layer.IP, ipCalls, perIpDaily);
        }
        if (globalCalls >= globalDaily) {
            return blocked(Layer.GLOBAL, globalCalls, globalDaily);
        }
        return Optional.empty();
    }

    /**
     * 추출(배치) 호출이 상한을 넘는지 본다 (이슈 #178).
     *
     * <p><b>{@link #exceededLayer} 와 따로 두는 이유</b> — 그쪽은 {@code userId} 를 필수로 받는데
     * 배치에는 사용자가 없다. 가짜 id 를 만들어 넘기면 사용자·IP 층이 무의미한 값을 세게 된다.
     *
     * <p>층 순서는 <b>추출 → 전역</b>이다. 추출 상한이 전역보다 낮으므로 보통 이쪽이 먼저 걸리고,
     * 그 경우 "배치 몫을 다 썼다"(서술은 아직 살아 있다)와 "예산 전체가 끝났다"는 서로 다른 사건이라
     * 구분해서 보고한다.
     *
     * @return 넘긴 층. 통과하면 빈 값
     */
    @Transactional(readOnly = true)
    public Optional<ExtractBlock> extractBlocked() {
        Instant since = clock.instant().minus(WINDOW);

        long extractCalls = aiCallLogRepository.countExtractSince(since);
        if (extractCalls >= extractDaily) {
            log.warn("AI 추출 쿼터 초과 — 배치를 멈춘다. calls={} limit={} window={}",
                    extractCalls, extractDaily, WINDOW);
            return Optional.of(ExtractBlock.EXTRACT);
        }

        // 배치 몫이 남아 있어도 예산 전체가 끝났으면 멈춘다 — 전역은 킬스위치다.
        Object[] counts =
                aiCallLogRepository.countChargeableSince(SYSTEM_USER, UNKNOWN_IP, since).get(0);
        long globalCalls = ((Number) counts[2]).longValue();
        if (globalCalls >= globalDaily) {
            log.warn("AI 전역 쿼터 초과 — 배치를 멈춘다. calls={} limit={} window={}",
                    globalCalls, globalDaily, WINDOW);
            return Optional.of(ExtractBlock.GLOBAL);
        }
        return Optional.empty();
    }

    private Optional<Layer> blocked(Layer layer, long calls, int limit) {
        // WARN 이다 — 전역 층이 걸린 것은 서비스 전체가 폴백으로 내려간 사건이고, 사용자·IP 층도
        // 누군가 상한에 닿았다는 사실 자체가 운영자가 봐야 하는 정보다.
        log.warn("AI 호출 쿼터 초과 — {} 층에서 막는다. calls={} limit={} window={}",
                layer.code(), calls, limit, WINDOW);
        return Optional.of(layer);
    }

    /**
     * 상한을 넘긴 층.
     *
     * <p>어느 층이었는지를 값으로 남기는 이유 — 관리자 화면에서 "한 사용자가 자기 몫을 다 썼다" 와
     * "서비스 전체가 멈췄다" 가 같은 값으로 보이면 대응이 완전히 다른 두 사건을 구분할 수 없다.
     * {@code ai_call_logs.fallback_reason} 과 API 응답의 {@code fallback_reason} 이 같은 코드를
     * 쓴다({@code AiCallVocabularyTest} 가 {@code AiService.FallbackReason} 과의 일치를 지킨다).
     */
    public enum Layer {

        /** 이 사용자가 자기 24시간 상한을 다 썼다. 데모 세션은 더 낮은 상한을 쓴다. */
        USER,

        /** 이 IP 에서 온 호출이 상한을 넘었다. 위조 가능한 헤더에 기대므로 과속방지턱이다. */
        IP,

        /** 서비스 전체가 24시간 상한을 넘었다 — 킬스위치가 발동했다. */
        GLOBAL;

        /** DB 컬럼·API 응답에 쓰는 표기. */
        public String code() {
            return "quota_" + name().toLowerCase(Locale.ROOT);
        }
    }

    /**
     * 추출(배치)을 막은 층 (이슈 #178).
     *
     * <p>{@link Layer} 와 따로 둔다 — 그쪽은 사용자 요청의 폴백 사유와 짝이 맞춰져 있고
     * ({@code AiService.FallbackReason}), 배치에는 사용자·IP 층이 없다. {@code GLOBAL} 의 코드
     * 문자열은 양쪽이 같은 사건을 가리키므로 일부러 일치시킨다.
     */
    public enum ExtractBlock {

        /** 배치가 자기 몫을 다 썼다. 사용자 서술은 아직 살아 있다. */
        EXTRACT,

        /** 예산 전체가 끝났다 — 킬스위치. 서술도 함께 멈춘다. */
        GLOBAL;

        /** 로그·응답에 쓰는 표기. */
        public String code() {
            return "quota_" + name().toLowerCase(Locale.ROOT);
        }
    }
}
