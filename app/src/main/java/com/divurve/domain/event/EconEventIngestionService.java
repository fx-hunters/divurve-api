package com.divurve.domain.event;

import com.divurve.common.architecture.UseCase;
import com.divurve.domain.event.EconEventValidator.Result;
import com.divurve.domain.event.EconEventValidator.ValidEvent;
import com.divurve.domain.event.entity.EconEvent;
import com.divurve.domain.port.EconEventExtractor;
import com.divurve.domain.port.EconEventExtractor.ExtractedEvent;
import com.divurve.domain.port.EconEventExtractor.RawArticle;
import com.divurve.domain.ai.AiCallLogRecorder;
import com.divurve.domain.ai.AiCallOutcome;
import com.divurve.domain.ai.entity.AiCallLog;
import com.divurve.domain.port.EconEventExtractor.ExtractOutcome;
import com.divurve.domain.port.RawArticleSource;
import com.divurve.domain.port.TokenUsage;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 비정형 원문에서 추출한 경제 이벤트를 검증해 적재하는 배치 유스케이스 (이슈 #74).
 *
 * <p><b>이 서비스 어디에도 {@code @Transactional} 을 걸지 않는다.</b> 이슈 #74 제약 5(부분 실패
 * 허용)를 따르려면 원문 하나 또는 이벤트 한 건의 실패가 이미 저장된 다른 건을 되돌리면 안 된다.
 * 배치 전체 또는 이 클래스의 메서드에 트랜잭션을 걸면(같은 클래스 안에서 호출하므로 Spring 프록시를
 * 거치지 않아 어차피 적용되지도 않는다) 걸린 것처럼 보이지만 실제로는 안 걸리는 착시만 남긴다.
 * 대신 {@link EconEventRepository#save} · {@code existsBy...} 같은 Spring Data JPA 기본 메서드가
 * 이미 건별로 자체 트랜잭션을 열기 때문에 이벤트 한 건 저장 단위의 원자성은 그것으로 충분하다.
 *
 * <p><b>원문 1건 처리 중 예외가 나도 다음 원문으로 넘어간다</b>(NFR-DT-01) — 한 소스의 추출 실패나
 * 예상 밖 예외가 배치 전체를 죽이지 않는다. 예외는 로그로 남기고 {@code failedArticles} 로 집계한다
 * ({@code rejected} 는 검증기에서 탈락한 이벤트 후보 건수만 센다 — 단위가 다르다).
 *
 * <p><b>호출 메타는 {@code ai_call_logs} 에 남긴다</b>(이슈 #143). 원문 하나마다 추출 호출 한 건을
 * 기록한다 — 성공이면 모델·토큰 사용량, 실패면 {@code error} 와 사유 요약이다. 원문 전문·프롬프트는
 * 남기지 않는다(이슈 #56, 마스킹 범위 미정).
 *
 * <p><b>기록이 배치를 죽이지 않는다.</b> {@link AiCallLogRecorder} 가 저장 실패를 삼키므로, 비용
 * 기록 때문에 적재가 멈추는 일은 없다 — 이 배치의 부분 실패 허용 원칙과 같은 방향이다.
 */
@UseCase
public class EconEventIngestionService {

    private static final Logger log = LoggerFactory.getLogger(EconEventIngestionService.class);

    private final RawArticleSource source;
    private final EconEventExtractor extractor;
    private final EconEventRepository repository;
    private final EconEventValidator validator;
    private final AiCallLogRecorder aiCallLogRecorder;
    private final Clock clock;

    public EconEventIngestionService(
            RawArticleSource source,
            EconEventExtractor extractor,
            EconEventRepository repository,
            EconEventValidator validator,
            AiCallLogRecorder aiCallLogRecorder,
            Clock clock) {
        this.source = Objects.requireNonNull(source, "source");
        this.extractor = Objects.requireNonNull(extractor, "extractor");
        this.repository = Objects.requireNonNull(repository, "repository");
        this.validator = Objects.requireNonNull(validator, "validator");
        this.aiCallLogRecorder = Objects.requireNonNull(aiCallLogRecorder, "aiCallLogRecorder");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * 최근 원문을 모두 읽어 이벤트를 추출·검증·적재한다.
     *
     * @return 처리 결과 요약
     */
    public IngestionReport ingest() {
        List<RawArticle> articles = source.fetchRecent();
        Counters counters = new Counters();
        counters.articles = articles.size();

        for (RawArticle article : articles) {
            ingestArticle(article, counters);
        }

        return counters.toReport();
    }

    private void ingestArticle(RawArticle article, Counters counters) {
        Instant startedAt = Instant.now(clock);
        try {
            ExtractOutcome outcome = extractor.extract(article);
            recordCall(startedAt, outcome.model(), outcome.usage(), AiCallOutcome.SUCCESS, null);

            List<ExtractedEvent> candidates = outcome.events();
            counters.extracted += candidates.size();
            for (ExtractedEvent candidate : candidates) {
                ingestCandidate(candidate, article, counters);
            }
            log.info("econ_events_extracted source_url={} candidates={}",
                    article.sourceUrl(), candidates.size());
        } catch (RuntimeException e) {
            // 실패도 기록한다 — 원문이 건너뛰어진 사실이 남지 않으면, 관리자 화면에서 추출
            // 호출이 줄어든 것이 "원문이 없었다" 인지 "전부 실패했다" 인지 구분되지 않는다.
            recordCall(startedAt, null, TokenUsage.NONE, AiCallOutcome.ERROR,
                    AiCallLogRecorder.summarize(e));
            log.warn("econ_events_extraction_failed source_url={} reason={}",
                    article.sourceUrl(), e.getMessage());
            counters.failedArticles++;
        }
    }

    /**
     * 추출 호출 한 건을 기록한다. {@code user_id} 는 없다 — 이 경로는 배치에서 돈다.
     */
    private void recordCall(
            Instant startedAt,
            String model,
            TokenUsage usage,
            AiCallOutcome outcome,
            String errorSummary) {
        int latencyMs = (int) Duration.between(startedAt, Instant.now(clock)).toMillis();
        aiCallLogRecorder.record(AiCallLog.extract(
                startedAt, null, model, usage, outcome, latencyMs, errorSummary));
    }

    private void ingestCandidate(ExtractedEvent candidate, RawArticle article, Counters counters) {
        Result result = validator.validate(candidate, article.text());
        if (!result.valid()) {
            counters.rejected++;
            return;
        }
        if (saveIfNew(result.value(), article)) {
            counters.inserted++;
        } else {
            counters.duplicates++;
        }
    }

    /** 중복이면 건너뛰고, 아니면 저장한다. 저장 자체의 원자성은 리포지토리 기본 메서드가 보장한다. */
    private boolean saveIfNew(ValidEvent validEvent, RawArticle article) {
        boolean exists = repository.existsByEventDateAndRegionAndTitle(
                validEvent.eventDate(), validEvent.region(), validEvent.title());
        if (exists) {
            return false;
        }
        repository.save(EconEvent.extracted(
                validEvent.eventDate(), validEvent.region(), validEvent.title(),
                validEvent.impact(), article.sourceUrl(), article.fetchedAt()));
        return true;
    }

    /**
     * 배치 처리 결과 요약.
     *
     * @param articles 조회된 원문 건수
     * @param extracted 추출기가 뽑아낸 이벤트 후보 건수
     * @param inserted 검증을 통과해 신규 저장된 이벤트 건수
     * @param rejected 검증기에서 탈락한 이벤트 후보 건수 (원문 단위 실패는 포함하지 않는다)
     * @param duplicates 검증은 통과했으나 기존 데이터와 중복이라 건너뛴 이벤트 건수
     * @param failedArticles 추출 단계에서 예외가 발생해 통째로 처리하지 못한 원문 건수
     */
    public record IngestionReport(
            int articles, int extracted, int inserted, int rejected, int duplicates, int failedArticles) {
    }

    /** 배치 진행 중 누적되는 집계값. {@link #ingest()} 호출 범위 안에서만 살아있는 지역 상태다. */
    private static final class Counters {
        private int articles;
        private int extracted;
        private int inserted;
        private int rejected;
        private int duplicates;
        private int failedArticles;

        private IngestionReport toReport() {
            return new IngestionReport(articles, extracted, inserted, rejected, duplicates, failedArticles);
        }
    }
}
