package com.divurve.domain.master;

import com.divurve.domain.master.entity.Currency;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 통화 마스터 리포지토리 (이슈 #111). Spring Data JPA 가 런타임 구현을 주입한다.
 * 자체 로직이 없는 인터페이스이므로 별도 {@code @PersistenceAdapter} 구현체는 두지 않는다.
 */
public interface CurrencyRepository extends JpaRepository<Currency, String> {

    /**
     * 자국 통화를 제외한 외화를 표시 순서대로 반환한다.
     *
     * <p>자국 통화(KRW)를 빼는 이유 — {@code GET /api/v1/currencies} 는 "목표를 세울 수 있는 외화"
     * 목록이다. 원화는 예산 통화이지 목표 통화가 아니다. 이 제외는 하드코딩 시절
     * {@code CurrencyMaster} 가 KRW 를 아예 담지 않았던 것과 같은 계약이다.
     */
    List<Currency> findByHomeCurrencyFalseOrderBySortOrderAsc();

    /** 마스터 전체를 표시 순서대로 반환한다 (자국 통화 포함) — 관리자 조회용. */
    List<Currency> findAllByOrderBySortOrderAsc();
}
