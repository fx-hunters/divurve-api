package com.divurve.infra.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.divurve.domain.event.EconEventRepository;
import com.divurve.domain.event.OfficialEventIngestionService;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * {@link OfficialEventIngestionScheduler} 테스트 (이슈 #163).
 *
 * <p>{@link EconEventIngestionSchedulerTest} 와 같은 것을 본다 — 위임하는지, 그리고 배치가
 * 터져도 삼켜서 다음 트리거가 멈추지 않는지. 스케줄러 스레드에서 예외가 전파되면 스케줄이
 * 통째로 죽어 배치 한 번의 실패가 이후 모든 실행을 막는다.
 */
@DisplayName("OfficialEventIngestionScheduler")
class OfficialEventIngestionSchedulerTest {

    @Test
    @DisplayName("정상 호출이면 유스케이스에 위임하고 예외를 던지지 않는다")
    void 정상이면_위임한다() {
        AtomicInteger calls = new AtomicInteger();
        StubIngestionService stub = new StubIngestionService(() -> {
            calls.incrementAndGet();
            return new OfficialEventIngestionService.IngestionReport(5, 3, 1, 1, 2);
        });

        assertThatNoException().isThrownBy(new OfficialEventIngestionScheduler(stub)::ingest);
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("배치가 터져도 삼킨다 — 다음 트리거가 멈추지 않는다")
    void 예외를_삼킨다() {
        StubIngestionService stub = new StubIngestionService(() -> {
            throw new IllegalStateException("FRED API key is not configured");
        });

        assertThatNoException().isThrownBy(new OfficialEventIngestionScheduler(stub)::ingest);
    }

    @Test
    @DisplayName("생성자는 ingestionService 가 null 이면 실패한다")
    void null이면_실패한다() {
        assertThatThrownBy(() -> new OfficialEventIngestionScheduler(null))
            .isInstanceOf(NullPointerException.class);
    }

    /**
     * {@link OfficialEventIngestionService} 는 구체 클래스라 상속으로 재정의한다.
     * {@link #ingest()} 를 완전히 덮으므로 생성자 협력자는 널 체크만 통과하면 된다.
     */
    private static final class StubIngestionService extends OfficialEventIngestionService {

        private final Supplier<IngestionReport> behavior;

        private StubIngestionService(Supplier<IngestionReport> behavior) {
            super((key, from, to) -> List.of(),
                    Mockito.mock(EconEventRepository.class),
                    Clock.systemUTC());
            this.behavior = behavior;
        }

        @Override
        public IngestionReport ingest() {
            return behavior.get();
        }
    }
}
