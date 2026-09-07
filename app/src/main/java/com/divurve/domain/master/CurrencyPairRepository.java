package com.divurve.domain.master;

import com.divurve.domain.master.entity.CurrencyPair;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 통화쌍 마스터 리포지토리 (이슈 #111). Spring Data JPA 가 런타임 구현을 주입한다.
 */
public interface CurrencyPairRepository extends JpaRepository<CurrencyPair, String> {

    /** 통화쌍 전체를 코드 순으로 반환한다 — 관리자 조회·통화쌍 선택지 구성용. */
    List<CurrencyPair> findAllByOrderByPairCodeAsc();

    /** 환율을 실제로 적재하는 쌍만 반환한다 — 적재 파이프라인과 차트 조회의 대상 집합이다. */
    List<CurrencyPair> findByStoredTrueOrderByPairCodeAsc();
}
