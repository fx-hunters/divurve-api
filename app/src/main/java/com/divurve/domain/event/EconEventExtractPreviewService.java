package com.divurve.domain.event;

import com.divurve.common.architecture.UseCase;
import com.divurve.common.exception.InvalidRequestException;
import com.divurve.domain.port.EconEventExtractor;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 비정형 원문 → 구조화 추출을 <b>저장 없이</b> 돌려본다 (이슈 #111).
 *
 * <p>지금까지 이 경로를 부르는 것은 스케줄러({@code EconEventIngestionScheduler}) 하나뿐이었다.
 * 그래서 "추출이 실제로 되는가, 무엇이 왜 거부되는가" 를 확인하려면 배치를 켜고 기다리는 수밖에
 * 없었다. 이 유스케이스가 그 확인을 즉시 할 수 있게 한다.
 *
 * <p><b>{@code econ_events} 에 저장하지 않는다.</b> 테스트 호출이 실데이터를 오염시키면
 * {@code GET /events} 가 곧바로 영향을 받는다. 적재가 필요하면 스케줄러를 켜는 것이 정상 경로다.
 *
 * <p>거부된 후보도 사유와 함께 <b>전부</b> 돌려준다 — 이 화면의 주된 산출물은 통과한 이벤트가
 * 아니라 "왜 걸렀는가" 다. {@link EconEventValidator} 의 판정을 그대로 노출한다.
 *
 * <p>추출기가 꺼져 있으면({@code app.external.anthropic.extract-enabled=false})
 * {@code NoOpEconEventExtractor} 가 빈 목록을 준다. 그 사실을 응답에 실어, 0건이 "추출 결과가
 * 없음" 인지 "추출기가 꺼져 있음" 인지 구분할 수 있게 한다.
 *
 * <p><b>추출기 자체가 터진 경우도 값으로 돌려준다</b>(이슈 #122). 예전에는 {@code extract} 가 던지는
 * 것을 잡지 않아 응답 형식 위반·키 오류·rate limit·타임아웃이 전부 500
 * {@code "예상치 못한 오류가 발생했습니다."} 로 나갔다 — 외부 연동을 점검하라고 만든 화면이 정작
 * 무엇이 잘못됐는지 말하지 않았다. 형제 엔드포인트({@code fx-rates/refresh}·{@code macro/refresh})와
 * 같이 사유를 응답에 담는다. 잘못된 입력(400)은 여전히 예외로 던진다 — 그것은 외부 실패가 아니다.
 */
@UseCase
public class EconEventExtractPreviewService {

    private static final Logger log =
            LoggerFactory.getLogger(EconEventExtractPreviewService.class);

    /** 원문 길이 상한. 프롬프트 예산을 넘는 입력이 그대로 외부 API 로 가는 것을 막는다. */
    static final int MAX_TEXT_LENGTH = 20_000;

    private final EconEventExtractor econEventExtractor;
    private final EconEventValidator econEventValidator;
    private final Clock clock;

    public EconEventExtractPreviewService(
            EconEventExtractor econEventExtractor,
            EconEventValidator econEventValidator,
            Clock clock) {
        this.econEventExtractor =
                Objects.requireNonNull(econEventExtractor, "econEventExtractor");
        this.econEventValidator =
                Objects.requireNonNull(econEventValidator, "econEventValidator");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * 원문 한 건에서 이벤트 후보를 뽑고 검증 결과를 함께 돌려준다. 저장하지 않는다.
     *
     * @param sourceUrl 출처 URL. 비어 있어도 된다 — 손으로 붙여넣은 원문에는 URL 이 없다
     * @param text      원문 전문
     * @throws InvalidRequestException 원문이 비었거나 상한을 넘은 경우 (400)
     */
    public PreviewResult preview(String sourceUrl, String text) {
        if (text == null || text.isBlank()) {
            throw new InvalidRequestException("원문을 입력해야 합니다.", "text");
        }
        if (text.length() > MAX_TEXT_LENGTH) {
            throw new InvalidRequestException(
                    "원문은 " + MAX_TEXT_LENGTH + "자까지입니다.", "text");
        }

        EconEventExtractor.RawArticle article =
                new EconEventExtractor.RawArticle(sourceUrl, text, Instant.now(clock));

        List<EconEventExtractor.ExtractedEvent> candidates;
        try {
            candidates = econEventExtractor.extract(article);
        } catch (RuntimeException e) {
            // 외부 실패는 이 화면의 산출물이지 서버 오류가 아니다 — 500 대신 사유를 값으로 낸다.
            log.warn("이벤트 추출 실패 — 사유를 응답에 담는다. sourceUrl={}", sourceUrl, e);
            return new PreviewResult(
                    extractorName(), List.of(), Instant.now(clock), describeFailure(e));
        }

        List<CandidateResult> results = new ArrayList<>();
        for (EconEventExtractor.ExtractedEvent candidate : candidates) {
            EconEventValidator.Result validation = econEventValidator.validate(candidate, text);
            results.add(new CandidateResult(
                    candidate.eventDate(),
                    candidate.region(),
                    candidate.title(),
                    candidate.impact(),
                    validation.valid(),
                    validation.rejectReason()));
        }

        return new PreviewResult(extractorName(), results, Instant.now(clock), null);
    }

    /**
     * 실패 사유를 한 줄로 만든다. <b>근본 원인까지 붙인다</b> (이슈 #122).
     *
     * <p>최상위 메시지만 쓰면 {@code AnthropicIoException: Request failed} 에서 끊겨 타임아웃인지
     * 연결 실패인지 구분할 수 없다 — 대응이 완전히 다른데도. 이 화면은 그 구분을 보러 오는 곳이다.
     */
    private static String describeFailure(Throwable e) {
        String head = e.getClass().getSimpleName() + ": " + e.getMessage();
        Throwable root = e;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        if (root == e) {
            return head;
        }
        return head + " (근본 원인: " + root.getClass().getSimpleName() + ": " + root.getMessage() + ")";
    }

    /**
     * 어떤 추출기가 응답했는지. 0건일 때 원인을 가르는 유일한 단서다.
     *
     * <p>클래스 단순명을 그대로 쓴다 — 구현이 늘어도 이 메서드를 고칠 필요가 없고,
     * 어느 빈이 주입됐는지가 화면에 그대로 드러난다.
     */
    private String extractorName() {
        return econEventExtractor.getClass().getSimpleName();
    }

    /**
     * 미리보기 결과.
     *
     * @param extractor  응답한 추출기 구현 이름 (예 {@code ClaudeEconEventExtractor})
     * @param candidates 추출된 후보와 검증 결과. 거부된 것도 포함한다. 추출이 실패했으면 빈 목록
     * @param previewedAt 실행 시각
     * @param failureReason 추출기가 터진 사유. 성공했으면 {@code null} (이슈 #122).
     *                      0건이 "뽑을 게 없었다" 인지 "부르다 터졌다" 인지를 가른다
     */
    public record PreviewResult(
            String extractor,
            List<CandidateResult> candidates,
            Instant previewedAt,
            String failureReason) {
    }

    /**
     * 후보 하나와 그 검증 결과.
     *
     * <p>{@code eventDate}·{@code region}·{@code impact} 는 <b>검증 전 원시값</b>이라 형식이
     * 어긋난 문자열이나 {@code null} 도 올 수 있다 — 그것을 그대로 보는 것이 이 화면의 목적이다.
     *
     * @param eventDate    추출된 날짜 문자열
     * @param region       추출된 지역 문자열
     * @param title        추출된 제목
     * @param impact       추출된 영향도
     * @param valid        검증을 통과했는가
     * @param rejectReason 거부 사유. 통과했으면 {@code null}
     */
    public record CandidateResult(
            String eventDate,
            String region,
            String title,
            Integer impact,
            boolean valid,
            String rejectReason) {
    }
}
