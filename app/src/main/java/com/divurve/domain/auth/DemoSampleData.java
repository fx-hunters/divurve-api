package com.divurve.domain.auth;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 데모(둘러보기) 계정에 시드하는 샘플 데이터의 <b>단일 정의 지점</b> (이슈 #78).
 *
 * <p>{@link AuthDemoService} 는 이 정의를 읽어 세션마다 새 데모 유저로 복제만 한다 —
 * <b>템플릿은 하나, 인스턴스는 세션마다</b>. 시연 데이터를 바꿀 때 유스케이스 코드를 건드리지 않고
 * 이 파일만 고치면 되도록 값을 여기 모았다.
 *
 * <p><b>값을 고를 때 지킨 것</b>
 * <ol>
 *   <li><b>목표는 미달 상태로 둔다.</b> 보유 USD(${@code 42,200})가 목표(${@code 60,000})의 약 70%다.
 *       초과 달성 상태면 "얼마나 더 모아야 하는지"를 보여주는 플랜·실행 화면이 데모에서 의미를 잃는다.</li>
 *   <li><b>한 종목이 집중도 기준선을 넘게 둔다.</b> AAPL 이 외화 자산의 약 63% 로,
 *       {@code balanced} 유형의 참고 기준선 0.60 을 넘는다 — X-ray 집중도 경고가 실제로 뜬다.</li>
 *   <li><b>원화 자산을 함께 둔다.</b> 원화 자산이 없으면 외화 비중의 분모가 외화뿐이라 항상 100% 로 보인다.</li>
 *   <li><b>통화를 셋으로 둔다.</b> USD·JPY·EUR — 통화 노출·집중도 화면이 한 통화만 그리면 비교할 것이 없다.</li>
 *   <li><b>날짜는 전부 상대값이다.</b> 언제 시연해도 매입일이 과거, 목표일이 미래로 유지된다.</li>
 * </ol>
 *
 * <p><b>규모를 화면 목업에 맞춘 이유</b>(이슈 #108) — 온보딩 2단계 "보유 자산을 불러올까요?" 는 더 이상
 * 하드코딩된 수치를 그리지 않고 {@code POST /auth/demo} 로 시드한 뒤 {@code GET /xray} 응답
 * ({@code fx_asset_krw}·{@code krw_asset_krw}·{@code exposure})을 그대로 표시한다. 시드가 화면에
 * 직접 보이므로 규모가 목업과 어긋나면 화면 정의서의 "화면 간 금액이 같은 Mock fixture 일치" 가 깨진다.
 * 목표는 외화 약 {@code 64,000,000} 원 · 원화 {@code 36,000,000} 원(외화 비중 약 64%)이며,
 * 외화 환산액은 조회 시점 환율에 따라 움직인다 — 고정값이 아니라 <b>대략적인 규모</b>를 맞춘 것이다.
 *
 * <p><b>수치를 여기 적어도 되는 이유</b> — 이것은 계산 결과가 아니라 사용자가 입력했을 법한 <b>입력값</b>이다.
 * 평가액·비중·집중도·플랜 같은 파생 수치는 여전히 {@code engine} 의 결정론적 계산이 만든다(CLAUDE.md 1장).
 * 같은 이유로 위험성향도 유형을 직접 박지 않고 {@link #RISK_PROFILE_ANSWERS 진단 응답}만 두어,
 * {@code RiskProfileScorer} 가 유형·점수·기준선을 산출하게 한다.
 */
public final class DemoSampleData {

    /** 데모 유저 이메일 접두사. 뒤에 UUID 가 붙어 세션마다 고유해진다. */
    public static final String EMAIL_PREFIX = "demo-";

    /** 데모 유저 이메일 도메인. 실제로 수신 가능한 주소가 아니라는 뜻으로 {@code .local} 을 쓴다. */
    public static final String EMAIL_DOMAIN = "@divurve.local";

    /** 데모 유저 표시 이름. */
    public static final String USER_NAME = "데모 사용자";

    /** 매입 환율의 출처 표기 — 사용자가 직접 입력한 값과 같은 취급이다(조회한 시세가 아니다). */
    public static final String PURCHASE_FX_RATE_SOURCE = "manual";

    /**
     * 보유 종목 2건. 개별주(AAPL)와 지수 ETF(VOO)를 섞어 X-ray 집중도 대비가 드러나게 한다.
     * 평가액 기준 AAPL ${@code 28,800} · VOO ${@code 8,400}.
     */
    public static final List<HoldingSample> HOLDINGS = List.of(
            new HoldingSample("AAPL", "USD", 160, 180.00, 8, new BigDecimal("1318.5000")),
            new HoldingSample("VOO", "USD", 20, 420.00, 5, new BigDecimal("1372.0000")));

    /**
     * 외화 예금 3건. 금액은 소수 4자리로 둔다(명세 1.4).
     *
     * <p>USD 외에 JPY·EUR 를 소액으로 두는 이유는 <b>통화 노출 화면이 다통화로 보여야 하기 때문</b>이다 —
     * 온보딩 2단계가 시드 실제값을 표시하므로({@code GET /xray} 의 {@code exposure}), 단일 통화면
     * "확인 통화" 가 USD 하나로만 뜬다. 비중은 USD 에 남겨 집중도 경고는 그대로 유지한다.
     *
     * <p>매입 환율은 <b>1통화 단위당 원</b>이다({@code QuoteUnitNormalizer} 규약) — JPY 는 원/100엔
     * 고시를 접은 값을 넣는다. 100엔 고시값을 그대로 넣으면 JPY 매입원가가 100배가 된다.
     */
    public static final List<DepositSample> DEPOSITS = List.of(
            new DepositSample("USD", new BigDecimal("5000.0000"), 3, new BigDecimal("1390.0000")),
            new DepositSample("JPY", new BigDecimal("400000.0000"), 4, new BigDecimal("9.2000")),
            new DepositSample("EUR", new BigDecimal("1000.0000"), 2, new BigDecimal("1450.0000")));

    /**
     * 원화 자산 2건, 합계 {@code 36,000,000} 원. 외화 비중의 분모가 되어
     * 총자산 대비 외화 비중이 100% 가 아닌 현실적인 값(외화 약 64%)으로 보이게 한다.
     */
    public static final List<KrwAssetSample> KRW_ASSETS = List.of(
            new KrwAssetSample("cash", "수시입출금 통장", 12_000_000L),
            new KrwAssetSample("deposit", "정기예금", 24_000_000L));

    /** 목표 1건. 보유 USD(${@code 42,200})가 목표(${@code 60,000})에 못 미치는 상태로 시작한다(약 70%). */
    public static final GoalSample GOAL = new GoalSample(
            "미국 대학원 학비", "study", "유학", "USD", 60_000.0, 12,
            3_000_000L, "KRW", "monthly", false, "active");

    /**
     * 간편 진단(Q1~Q3) 응답. 합계 4점으로 {@code balanced}(균형항로형)가 산출된다.
     *
     * <p>이 조합을 고른 이유는 <b>문서 근거가 있는 유일한 조합</b>이기 때문이다 —
     * {@code q1:B · q2:C · q3:B} 는 API 명세 §5.1 예시 그대로이고, 그 결과인 {@code balanced} 의
     * 집중도 기준선 0.60 은 명세 §4 Mock fixture 확정값이다. 나머지 유형의 기준선은
     * {@code RiskProfileScorer} 주석대로 아직 가정값이라 데모가 그 위에 서지 않게 한다.
     */
    public static final Map<String, String> RISK_PROFILE_ANSWERS = Map.of("q1", "B", "q2", "C", "q3", "B");

    private DemoSampleData() {
    }

    /**
     * 보유 종목 샘플.
     *
     * @param ticker             종목 코드
     * @param currencyCode       거래 통화
     * @param quantity           수량
     * @param avgPrice           평균 매입 단가 (거래 통화 기준)
     * @param purchasedMonthsAgo 매입 시점 — 기준일로부터 몇 개월 전인지
     * @param purchaseFxRateKrw  매입 시점 환율 (원)
     */
    public record HoldingSample(
            String ticker,
            String currencyCode,
            double quantity,
            double avgPrice,
            int purchasedMonthsAgo,
            BigDecimal purchaseFxRateKrw) {

        /** 기준일 기준 매입일. */
        public LocalDate purchasedOn(LocalDate today) {
            return today.minusMonths(purchasedMonthsAgo);
        }
    }

    /**
     * 외화 예금 샘플.
     *
     * @param currencyCode       통화
     * @param amount             금액 (소수 4자리)
     * @param purchasedMonthsAgo 매입 시점 — 기준일로부터 몇 개월 전인지
     * @param purchaseFxRateKrw  매입 시점 환율 (원)
     */
    public record DepositSample(
            String currencyCode,
            BigDecimal amount,
            int purchasedMonthsAgo,
            BigDecimal purchaseFxRateKrw) {

        /** 기준일 기준 매입일. */
        public LocalDate purchasedOn(LocalDate today) {
            return today.minusMonths(purchasedMonthsAgo);
        }
    }

    /**
     * 원화 자산 샘플.
     *
     * @param kind      종류 — {@code cash}/{@code deposit}/{@code domestic_equity}/{@code other}
     * @param label     이름표
     * @param amountKrw 금액 (원)
     */
    public record KrwAssetSample(String kind, String label, long amountKrw) {
    }

    /**
     * 목표 샘플.
     *
     * @param name                 목표 이름
     * @param kind                 목표 종류
     * @param purpose              사용 목적
     * @param currencyCode         목표 통화
     * @param targetAmount         목표 금액 (목표 통화 기준)
     * @param targetDateMonthsAhead 목표 시점 — 기준일로부터 몇 개월 후인지
     * @param budgetAmount         정기 예산 금액
     * @param budgetCurrencyCode   정기 예산 통화
     * @param budgetPeriod         정기 예산 주기
     * @param isSpeculative        투기성 목표 여부
     * @param status               목표 상태
     */
    public record GoalSample(
            String name,
            String kind,
            String purpose,
            String currencyCode,
            double targetAmount,
            int targetDateMonthsAhead,
            long budgetAmount,
            String budgetCurrencyCode,
            String budgetPeriod,
            boolean isSpeculative,
            String status) {

        /** 기준일 기준 목표일. */
        public LocalDate targetDate(LocalDate today) {
            return today.plusMonths(targetDateMonthsAhead);
        }
    }
}
