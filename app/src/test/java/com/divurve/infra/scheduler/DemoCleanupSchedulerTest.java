package com.divurve.infra.scheduler;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.divurve.domain.auth.DemoCleanupService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * {@link DemoCleanupScheduler} 단위 테스트 (이슈 #138).
 *
 * <p>핵심은 <b>예외를 삼킨다</b>는 것이다 — 스케줄러 스레드로 예외가 전파되면 다음 트리거부터
 * 스케줄이 통째로 멈추고, 그러면 원래 문제(더미 데이터 누적)가 조용히 돌아온다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("DemoCleanupScheduler")
class DemoCleanupSchedulerTest {

    @Mock
    private DemoCleanupService demoCleanupService;

    @Test
    @DisplayName("정리 유스케이스를 호출한다")
    void delegatesToUseCase() {
        when(demoCleanupService.cleanUp()).thenReturn(3);

        new DemoCleanupScheduler(demoCleanupService).cleanUp();

        verify(demoCleanupService).cleanUp();
    }

    @Test
    @DisplayName("정리가 실패해도 예외를 던지지 않는다 — 다음 실행이 멈추면 안 된다")
    void swallowsFailure() {
        when(demoCleanupService.cleanUp()).thenThrow(new IllegalStateException("DB 연결 끊김"));

        // 던지지 않는 것 자체가 검증 대상이다.
        new DemoCleanupScheduler(demoCleanupService).cleanUp();
    }

    @Test
    @DisplayName("협력자가 null 이면 실패한다")
    void rejectsNull() {
        assertThatThrownBy(() -> new DemoCleanupScheduler(null))
                .isInstanceOf(NullPointerException.class);
    }
}
