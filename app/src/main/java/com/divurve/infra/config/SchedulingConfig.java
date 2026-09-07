package com.divurve.infra.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * {@code @Scheduled} 배선 (이슈 #74, 이슈 #111 로 게이팅 구조 변경).
 *
 * <p><b>바뀐 점</b> — 예전에는 이 클래스 자체가 {@code app.external.anthropic.extract-schedule-enabled}
 * 조건 뒤에 있어서, 그 프로퍼티 하나가 <b>스케줄링 인프라 전체</b>를 켜고 껐다. 그 상태로는 환율
 * 적재 배치를 켜려면 Anthropic 추출 배치도 함께 켜야 했다 — 서로 다른 외부 API 를 치는 두 작업이
 * 한 스위치에 묶여 있었다.
 *
 * <p>지금은 <b>인프라는 항상 켜고, 작업별로 각자 끈다</b>. 각 스케줄러 빈이 자기
 * {@code @ConditionalOnProperty} 를 갖는다:
 * <ul>
 *   <li>{@code app.external.anthropic.extract-schedule-enabled} — 경제 이벤트 추출</li>
 *   <li>{@code app.external.ecos.ingest-schedule-enabled} — 환율 적재</li>
 * </ul>
 * 둘 다 기본 꺼짐이다.
 *
 * <p><b>이 변경이 무언가를 깨우지 않는 근거</b> — 조건이 false 면 스케줄러 빈 자체가 만들어지지
 * 않으므로 등록될 {@code @Scheduled} 메서드가 없다. {@code @EnableScheduling} 이 켜져 있어도
 * 아무 작업도 깨어나지 않는다. 남는 것은 태스크 스케줄러 스레드 하나뿐이다.
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
