package com.divurve.infra.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.divurve.domain.user.AdminBootstrapService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link AdminBootstrapRunner} — 기동 트리거.
 *
 * <p>실패해도 기동을 막지 않아야 한다. 관리자 계정이 없는 것은 서비스 사용자에게 영향이 없고,
 * 그 때문에 앱 전체가 안 뜨는 편이 훨씬 나쁘다.
 */
@DisplayName("AdminBootstrapRunner")
class AdminBootstrapRunnerTest {

    private AdminBootstrapService bootstrapService;
    private AdminBootstrapRunner runner;

    @BeforeEach
    void setUp() {
        bootstrapService = mock(AdminBootstrapService.class);
        runner = new AdminBootstrapRunner(bootstrapService);
    }

    @Test
    @DisplayName("기동 시 부트스트랩을 한 번 부른다")
    void run_DelegatesOnce() {
        when(bootstrapService.bootstrap())
                .thenReturn(AdminBootstrapService.BootstrapOutcome.CREATED);

        runner.run(null);

        verify(bootstrapService).bootstrap();
    }

    @Test
    @DisplayName("부트스트랩이 실패해도 기동을 막지 않는다")
    void run_Failure_DoesNotBlockStartup() {
        when(bootstrapService.bootstrap()).thenThrow(new IllegalStateException("DB 연결 실패"));

        assertThatCode(() -> runner.run(null)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("null 의존은 거부한다")
    void nullDependency_Throws() {
        assertThatThrownBy(() -> new AdminBootstrapRunner(null))
                .isInstanceOf(NullPointerException.class);
    }
}
