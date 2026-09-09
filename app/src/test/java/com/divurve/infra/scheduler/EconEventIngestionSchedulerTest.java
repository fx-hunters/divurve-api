package com.divurve.infra.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatNoException;

import com.divurve.domain.event.EconEventIngestionService;
import com.divurve.domain.event.EconEventRepository;
import com.divurve.domain.event.EconEventValidator;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * {@link EconEventIngestionScheduler} 테스트 (이슈 #74).
 *
 * <p>확인하는 것: (1) 정상 호출 시 유스케이스를 위임하는지, (2) {@code ingest()} 가 예외를
 * 던져도 스케줄러가 삼켜 애플리케이션을 죽이지 않는지 — "실패해도 서비스 기동/응답에 영향
 * 없어야 한다"(이슈 #74 제약)를 코드로 고정한다.
 */
class EconEventIngestionSchedulerTest {

    @Test
    void ingest_는_정상_호출이면_유스케이스_결과를_로그로_남기고_예외를_던지지_않는다() {
        AtomicInteger calls = new AtomicInteger();
        StubIngestionService stub = new StubIngestionService(() -> {
            calls.incrementAndGet();
            return new EconEventIngestionService.IngestionReport(2, 3, 2, 1, 0, 0, 0, 0);
        });
        EconEventIngestionScheduler sut = new EconEventIngestionScheduler(stub);

        assertThatNoException().isThrownBy(sut::ingest);
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void ingest_는_유스케이스가_예외를_던져도_삼킨다() {
        StubIngestionService stub = new StubIngestionService(() -> {
            throw new IllegalStateException("ingestion failed");
        });
        EconEventIngestionScheduler sut = new EconEventIngestionScheduler(stub);

        assertThatNoException().isThrownBy(sut::ingest);
    }

    @Test
    void 생성자는_ingestionService가_null이면_실패한다() {
        assertThatThrownBy(() -> new EconEventIngestionScheduler(null))
            .isInstanceOf(NullPointerException.class);
    }

    /**
     * {@link EconEventIngestionService} 는 구체 클래스라 목(mock)이 아니라 상속으로 재정의한다.
     * 생성자 협력자는 {@link #ingest()} 를 완전히 재정의하므로 실제로 쓰이지 않지만, 널 체크를
     * 통과할 최소한의 실물(빈 원문 소스, 빈 목록 추출기, Mockito 리포지토리, 실제 검증기)을 넘긴다.
     */
    private static final class StubIngestionService extends EconEventIngestionService {

        private final java.util.function.Supplier<IngestionReport> behavior;

        StubIngestionService(java.util.function.Supplier<IngestionReport> behavior) {
            super(List::of,
                Mockito.mock(com.divurve.domain.ai.AiCallQuota.class),
                article -> com.divurve.domain.port.EconEventExtractor.ExtractOutcome
                    .withoutLlm(List.of()),
                Mockito.mock(EconEventRepository.class),
                new EconEventValidator(),
                Mockito.mock(com.divurve.domain.ai.AiCallLogRecorder.class),
                java.time.Clock.systemUTC());
            this.behavior = behavior;
        }

        @Override
        public IngestionReport ingest() {
            return behavior.get();
        }
    }
}
