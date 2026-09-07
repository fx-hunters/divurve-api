package com.divurve.domain.fx;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link StoredFxRates#NONE} — 저장분을 쓰지 않는 구현 (이슈 #116).
 *
 * <p>이슈 #116 이전의 읽기 경로가 정확히 이 동작이었다. ECOS 경로 자체를 고정하는 테스트들이
 * 이것을 끼우고 돌기 때문에, "언제나 빈 값" 이라는 성질이 깨지면 그 테스트들의 의미가 사라진다.
 */
@DisplayName("StoredFxRates.NONE")
class StoredFxRatesTest {

    @Test
    @DisplayName("언제나 빈 값을 준다 — 호출자는 늘 ECOS 로 간다")
    void 언제나_비어_있다() {
        assertThat(StoredFxRates.NONE.latestPerUnitRate("USD")).isEmpty();
        assertThat(StoredFxRates.NONE.perUnitSeries("USD", LocalDate.of(2026, 9, 7), 30))
                .isEmpty();
    }
}
