package com.divurve.domain.fx;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.divurve.common.exception.InvalidRequestException;
import java.util.Arrays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link FxRateType} — DB 코드와 자바 상수의 왕복.
 */
@DisplayName("FxRateType")
class FxRateTypeTest {

    @Test
    @DisplayName("DB CHECK 제약과 같은 5종을 갖는다")
    void hasFiveTypes() {
        assertThat(Arrays.stream(FxRateType.values()).map(FxRateType::code))
                .containsExactly("mid", "tt_buy", "tt_sell", "cash_buy", "cash_sell");
    }

    @Test
    @DisplayName("코드로 왕복한다")
    void roundTrip() {
        for (FxRateType type : FxRateType.values()) {
            assertThat(FxRateType.fromCode(type.code())).isSameAs(type);
        }
        assertThat(FxRateType.valueOf("MID")).isSameAs(FxRateType.MID);
    }

    @Test
    @DisplayName("알 수 없는 코드는 400 이고 field 는 rate_type 이다")
    void unknownCode_Throws() {
        assertThatThrownBy(() -> FxRateType.fromCode("spot"))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("spot");
    }
}
