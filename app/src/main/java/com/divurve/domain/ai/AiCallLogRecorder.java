package com.divurve.domain.ai;

import com.divurve.common.architecture.UseCase;
import com.divurve.domain.ai.entity.AiCallLog;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * AI 호출 기록을 남긴다 (이슈 #143).
 *
 * <p><b>기록은 부수 효과다 — 실패해도 AI 응답을 막지 않는다</b>(NFR-AI-03). 비용 집계를 남기려다
 * 사용자 요청을 깨뜨리면 본말이 전도된다. 모든 예외를 여기서 삼키고 로그만 남긴다.
 *
 * <p><b>{@code REQUIRES_NEW} 인 이유</b> — 호출자인 {@code AiService#explain} 은
 * {@code @Transactional(readOnly = true)} 다. 그 트랜잭션에 얹으면 쓰기가 거부된다. 또한 호출자
 * 트랜잭션이 나중에 롤백되면 기록도 함께 사라지는데, <b>비용은 롤백되지 않는다</b> — 이미 토큰을
 * 썼다면 그 사실은 남아야 한다.
 */
@UseCase
public class AiCallLogRecorder {

    private static final Logger log = LoggerFactory.getLogger(AiCallLogRecorder.class);

    /** 예외 요약 길이 상한. 이 표는 비용 집계용이고 스택트레이스를 담는 곳이 아니다. */
    static final int MAX_ERROR_SUMMARY_LENGTH = 500;

    private final AiCallLogRepository aiCallLogRepository;

    public AiCallLogRecorder(AiCallLogRepository aiCallLogRepository) {
        this.aiCallLogRepository = Objects.requireNonNull(aiCallLogRepository, "aiCallLogRepository");
    }

    /**
     * 호출 한 건을 기록한다. 저장에 실패하면 로그만 남기고 삼킨다.
     *
     * @param callLog 기록할 내용
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(AiCallLog callLog) {
        Objects.requireNonNull(callLog, "callLog");
        try {
            aiCallLogRepository.save(callLog);
        } catch (RuntimeException e) {
            // 여기서 던지면 AI 응답이 500 이 된다. 비용 기록 하나를 지키려고 사용자 요청을
            // 깨뜨리지 않는다 — cause 체인을 남겨 원인은 추적할 수 있게 한다.
            log.warn("AI 호출 기록 저장 실패 — 응답에는 영향을 주지 않는다. purpose={} outcome={}",
                    callLog.getPurpose(), callLog.getOutcome(), e);
        }
    }

    /**
     * 예외를 한 줄 요약으로 줄인다.
     *
     * @param e 요약할 예외. {@code null} 이면 {@code null}
     */
    public static String summarize(Throwable e) {
        if (e == null) {
            return null;
        }
        String summary = e.getClass().getSimpleName()
                + (e.getMessage() == null ? "" : ": " + e.getMessage());
        return summary.length() <= MAX_ERROR_SUMMARY_LENGTH
                ? summary
                : summary.substring(0, MAX_ERROR_SUMMARY_LENGTH);
    }
}
