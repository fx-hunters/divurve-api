package com.divurve.domain.event;

import static org.assertj.core.api.Assertions.assertThat;

import com.divurve.domain.RepositoryTestBase;
import com.divurve.domain.event.entity.EconEvent;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * V28 시연용 시드와 구간 조회 (이슈 #162).
 *
 * <p>읽기 경로를 {@code econ_events} 로 돌리면서 목 8건이 사라졌다. 이 표를 채우는 유일한 경로인
 * 추출 배치는 기본이 꺼짐이라, 시드가 빠지면 {@code GET /events} 와 홈 {@code attention} 이 항상
 * 빈 배열이 된다 — 그 회귀를 여기서 잡는다.
 *
 * <p><b>시드의 지역·영향도가 {@link EconEventVocabulary} 표와 맞는지도 함께 본다.</b> 두 파일이
 * 따로 놀면 화면에서 통화나 중요도가 통째로 사라지는데, 각각을 따로 본 테스트는 그것을 놓친다.
 */
@DisplayName("econ_events 시드")
class EconEventSeedRepositoryTest extends RepositoryTestBase {

    private static final String DEMO_SAMPLE = "DEMO_SAMPLE";

    @Autowired
    private EconEventRepository repository;

    private List<EconEvent> seeded() {
        return repository.findAll().stream()
                .filter(event -> DEMO_SAMPLE.equals(event.getSourceKind()))
                .toList();
    }

    @Test
    @DisplayName("마이그레이션이 시연용 일정을 넣는다 — 표가 비어 있지 않다")
    void 시드가_적용된다() {
        assertThat(seeded()).isNotEmpty();
    }

    @Test
    @DisplayName("시연용 행은 출처를 지어내지 않는다 — source_url 이 비어 있다")
    void 시연용_행은_출처가_없다() {
        assertThat(seeded()).allSatisfy(event ->
                assertThat(event.getSourceUrl()).isNull());
    }

    @Test
    @DisplayName("시드의 지역·영향도는 모두 응답 어휘로 옮겨진다 — 변환표와 어긋나지 않는다")
    void 시드는_변환표와_맞는다() {
        assertThat(seeded()).allSatisfy(event -> {
            assertThat(EconEventVocabulary.toImportance(event.getImpact())).isNotNull();
            // GLOBAL 만 통화가 없다. 그 외 지역이 null 을 내면 변환표에 구멍이 뚫린 것이다.
            if (!"GLOBAL".equals(event.getRegion())) {
                assertThat(EconEventVocabulary.toCurrencyCode(event.getRegion())).isNotNull();
            }
        });
    }

    @Test
    @DisplayName("구간 조회는 창 안의 일정만 날짜 오름차순으로 돌려준다")
    void 구간_조회는_날짜순이다() {
        LocalDate from = LocalDate.of(2026, 9, 1);
        LocalDate to = LocalDate.of(2026, 12, 31);

        List<EconEvent> found = repository.findByEventDateBetweenOrderByEventDateAsc(from, to);

        assertThat(found).isNotEmpty();
        assertThat(found).isSortedAccordingTo(Comparator.comparing(EconEvent::getEventDate));
        assertThat(found).allSatisfy(event ->
                assertThat(event.getEventDate()).isBetween(from, to));
    }

    @Test
    @DisplayName("창 밖은 읽지 않는다 — 서비스가 다시 거를 필요가 없다")
    void 창_밖은_읽지_않는다() {
        LocalDate past = LocalDate.of(2000, 1, 1);

        assertThat(repository.findByEventDateBetweenOrderByEventDateAsc(past, past.plusDays(30)))
                .isEmpty();
    }
}
