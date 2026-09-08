package com.divurve.domain.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.divurve.domain.user.UserRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/**
 * {@link DemoCleanupService} 단위 테스트 (이슈 #138).
 *
 * <p>기준 시각 계산과 배치 상한을 본다. 실제 cascade 전파와 "일반 계정은 안 지워진다" 는
 * {@link DemoCleanupIntegrationTest} 가 실제 Postgres 로 확인한다 — 목으로는 FK 동작을 검증할 수 없다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("DemoCleanupService")
class DemoCleanupServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-08T04:10:00Z");

    @Mock
    private UserRepository userRepository;

    private DemoCleanupService sut(Duration retention, int batchLimit) {
        return new DemoCleanupService(
                userRepository, Clock.fixed(NOW, ZoneOffset.UTC), retention, batchLimit);
    }

    @Test
    @DisplayName("보존 기간을 뺀 시각을 기준으로 조회하고, 찾은 계정을 지운다")
    void deletesExpiredDemoUsers() {
        List<UUID> expired = List.of(UUID.randomUUID(), UUID.randomUUID());
        when(userRepository.findExpiredDemoUserIds(any(Instant.class), any(Pageable.class)))
                .thenReturn(expired);

        int deleted = sut(Duration.ofDays(1), 500).cleanUp();

        assertThat(deleted).isEqualTo(2);
        ArgumentCaptor<Instant> threshold = ArgumentCaptor.forClass(Instant.class);
        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(userRepository).findExpiredDemoUserIds(threshold.capture(), pageable.capture());
        assertThat(threshold.getValue()).isEqualTo(NOW.minus(Duration.ofDays(1)));
        assertThat(pageable.getValue().getPageSize()).isEqualTo(500);
        assertThat(pageable.getValue().getSort())
                .as("오래된 것부터 지운다 — 상한에 걸려 남는 것이 항상 최근 것이어야 한다")
                .isEqualTo(Sort.by(Sort.Direction.ASC, "createdAt"));
        verify(userRepository).deleteAllByIdInBatch(expired);
    }

    @Test
    @DisplayName("대상이 없으면 삭제를 호출하지 않는다")
    void noTargetsMeansNoDelete() {
        when(userRepository.findExpiredDemoUserIds(any(Instant.class), any(Pageable.class)))
                .thenReturn(List.of());

        assertThat(sut(Duration.ofDays(1), 500).cleanUp()).isZero();

        verify(userRepository, never()).deleteAllByIdInBatch(any());
    }

    @Test
    @DisplayName("배치 상한을 페이지 크기로 넘긴다 — 상한 없는 벌크 삭제를 하지 않는다")
    void batchLimitBecomesPageSize() {
        when(userRepository.findExpiredDemoUserIds(any(Instant.class), any(Pageable.class)))
                .thenReturn(List.of());

        sut(Duration.ofDays(1), 7).cleanUp();

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(userRepository).findExpiredDemoUserIds(any(Instant.class), pageable.capture());
        assertThat(pageable.getValue().getPageSize()).isEqualTo(7);
    }

    @Test
    @DisplayName("보존 기간을 바꾸면 기준 시각이 함께 움직인다")
    void retentionShiftsThreshold() {
        when(userRepository.findExpiredDemoUserIds(any(Instant.class), any(Pageable.class)))
                .thenReturn(List.of());

        sut(Duration.ofHours(2), 500).cleanUp();

        ArgumentCaptor<Instant> threshold = ArgumentCaptor.forClass(Instant.class);
        verify(userRepository).findExpiredDemoUserIds(threshold.capture(), any(Pageable.class));
        assertThat(threshold.getValue()).isEqualTo(NOW.minus(Duration.ofHours(2)));
    }

    @Test
    @DisplayName("협력자 null 과 1 미만의 배치 상한을 거부한다")
    void rejectsInvalidConstruction() {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

        assertThatThrownBy(() -> new DemoCleanupService(null, clock, Duration.ofDays(1), 500))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() ->
                new DemoCleanupService(userRepository, null, Duration.ofDays(1), 500))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new DemoCleanupService(userRepository, clock, null, 500))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() ->
                new DemoCleanupService(userRepository, clock, Duration.ofDays(1), 0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
