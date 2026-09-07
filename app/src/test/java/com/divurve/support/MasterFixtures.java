package com.divurve.support;

import com.divurve.domain.master.entity.Currency;
import com.divurve.domain.master.entity.CurrencyPair;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 통화·통화쌍 마스터 엔티티 픽스처 (이슈 #111).
 *
 * <p>두 엔티티는 마이그레이션 시드로만 채워지므로 공개 생성자가 없다. 테스트만을 위해 프로덕션
 * 코드에 setter 를 여는 대신 여기서 리플렉션으로 채운다 — 시드 값 자체가 맞는지는 실 DB 를 쓰는
 * {@code CurrencyMasterSchemaTest} 가 검증한다.
 */
public final class MasterFixtures {

    private MasterFixtures() {
    }

    /** 통화 마스터 한 행. */
    public static Currency currency(
            String code,
            String nameKo,
            String symbol,
            int minorUnits,
            int quoteUnit,
            String usdSide,
            boolean home,
            boolean supported,
            String supportNote,
            String colorToken,
            int sortOrder) {
        Currency entity = instantiate(Currency.class);
        ReflectionTestUtils.setField(entity, "currencyCode", code);
        ReflectionTestUtils.setField(entity, "nameKo", nameKo);
        ReflectionTestUtils.setField(entity, "symbol", symbol);
        ReflectionTestUtils.setField(entity, "minorUnits", (short) minorUnits);
        ReflectionTestUtils.setField(entity, "quoteUnit", (short) quoteUnit);
        ReflectionTestUtils.setField(entity, "usdSide", usdSide);
        ReflectionTestUtils.setField(entity, "homeCurrency", home);
        ReflectionTestUtils.setField(entity, "supported", supported);
        ReflectionTestUtils.setField(entity, "supportNote", supportNote);
        ReflectionTestUtils.setField(entity, "colorToken", colorToken);
        ReflectionTestUtils.setField(entity, "sortOrder", (short) sortOrder);
        return entity;
    }

    /** 통화쌍 마스터 한 행. */
    public static CurrencyPair pair(
            String pairCode, String base, String quote, boolean stored, String deriveVia) {
        CurrencyPair entity = instantiate(CurrencyPair.class);
        ReflectionTestUtils.setField(entity, "pairCode", pairCode);
        ReflectionTestUtils.setField(entity, "baseCurrencyCode", base);
        ReflectionTestUtils.setField(entity, "quoteCurrencyCode", quote);
        ReflectionTestUtils.setField(entity, "stored", stored);
        ReflectionTestUtils.setField(entity, "deriveViaPairCode", deriveVia);
        return entity;
    }

    private static <T> T instantiate(Class<T> type) {
        try {
            var constructor = type.getDeclaredConstructor();
            constructor.setAccessible(true);
            return constructor.newInstance();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("엔티티 인스턴스 생성 실패: " + type.getName(), e);
        }
    }
}
