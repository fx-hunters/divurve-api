package com.divurve.support;

import com.divurve.domain.fx.entity.FxRate;
import com.divurve.domain.fx.entity.FxRateId;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * {@code fx_rates} 엔티티 픽스처 (이슈 #116).
 *
 * <p>{@link FxRate} 는 upsert 네이티브 쿼리로만 쓰이는 읽기 전용 투영이라 공개 생성자가 없다.
 * 테스트를 위해 프로덕션에 setter 를 여는 대신 여기서 리플렉션으로 채운다
 * ({@link MasterFixtures} 와 같은 이유). 실제 매핑이 맞는지는 {@code FxRateRepositoryTest} 가
 * 실 DB 로 검증한다.
 */
public final class FxRateFixtures {

    private static final Instant FETCHED_AT = Instant.parse("2026-09-07T00:30:00Z");

    private FxRateFixtures() {
    }

    /** 매매기준율 한 행 (1 외화당 원화). */
    public static FxRate rate(String pairCode, LocalDate quoteDate, String perUnitRate) {
        FxRateId id = instantiate(FxRateId.class);
        ReflectionTestUtils.setField(id, "pairCode", pairCode);
        ReflectionTestUtils.setField(id, "quoteDate", quoteDate);
        ReflectionTestUtils.setField(id, "rateType", "mid");

        FxRate entity = instantiate(FxRate.class);
        ReflectionTestUtils.setField(entity, "id", id);
        ReflectionTestUtils.setField(entity, "rate", new BigDecimal(perUnitRate));
        ReflectionTestUtils.setField(entity, "dataSource", "ECOS");
        ReflectionTestUtils.setField(entity, "fetchedAt", FETCHED_AT);
        return entity;
    }

    private static <T> T instantiate(Class<T> type) {
        try {
            var constructor = type.getDeclaredConstructor();
            constructor.setAccessible(true);
            return constructor.newInstance();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("픽스처 생성 실패: " + type.getSimpleName(), e);
        }
    }
}
