package com.divurve.domain.stress;

import com.divurve.domain.fx.StoredFxRates;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import com.divurve.common.exception.InvalidRequestException;
import com.divurve.common.exception.NotFoundException;
import com.divurve.domain.fx.PerUnitFxRates;
import com.divurve.domain.holding.DepositRepository;
import com.divurve.domain.holding.FxAssetValuator;
import com.divurve.domain.holding.HoldingRepository;
import com.divurve.domain.holding.entity.Deposit;
import com.divurve.domain.holding.entity.Holding;
import com.divurve.domain.port.FxRateProvider;
import com.divurve.domain.port.RateSnapshot;
import com.divurve.domain.stress.entity.StressScenario;
import com.divurve.domain.stress.entity.StressTestRun;
import com.divurve.domain.user.UserRepository;
import com.divurve.domain.user.entity.User;
import com.divurve.engine.stress.StressCalculator;
import com.divurve.engine.weight.QuoteUnitNormalizer;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * {@link StressRunService} 검증 (명세 v2 §5.9).
 *
 * <p>핵심은 <b>명세 fixture 재현</b>이다 — 해외주식 20,000,000 · 외화자산 24,720,000 에
 * 시나리오 {@code equity_down_krw_weak}(-0.20 / +0.10)을 적용하면
 * 주가 -4,000,000 / 환율 +2,072,000 / 합계 -1,928,000 / 적용 후 22,792,000 이어야 한다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("StressRunService")
class StressRunServiceTest {

    private static final UUID USER_ID = UUID.randomUUID();
    private static final LocalDate TODAY = LocalDate.of(2026, 9, 1);
    private static final String SCENARIO_CODE = "equity_down_krw_weak";

    /** fixture 환율. 해외주식 20,000,000 KRW = 14,467.59... USD 가 되지 않게 정수로 떨어지는 값을 쓴다. */
    private static final BigDecimal USD_KRW = new BigDecimal("1000");

    @Mock
    private StressScenarioRepository scenarioRepository;
    @Mock
    private StressTestRunRepository runRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private HoldingRepository holdingRepository;
    @Mock
    private DepositRepository depositRepository;
    @Mock
    private FxRateProvider fxRateProvider;

    private StressRunService service;

    @BeforeEach
    void setUp() {
        service = new StressRunService(
                scenarioRepository,
                runRepository,
                userRepository,
                holdingRepository,
                depositRepository,
                new FxAssetValuator(new PerUnitFxRates(StoredFxRates.NONE, fxRateProvider, new QuoteUnitNormalizer())),
                new StressCalculator(),
                Clock.fixed(TODAY.atStartOfDay().toInstant(ZoneOffset.UTC), ZoneOffset.UTC));
    }

    @Test
    @DisplayName("명세 §5.9 검산 — 주가 -4,000,000 / 환율 +2,072,000 / 합계 -1,928,000 / 적용 후 22,792,000")
    void reproducesSpecFixture() {
        givenUser();
        givenScenario(SCENARIO_CODE, "-0.2000", "0.1000");
        givenRates();
        // 해외주식 20,000 USD × 1000 = 20,000,000 KRW, 외화예금 4,720 USD × 1000 = 4,720,000 KRW
        when(holdingRepository.findByOwner_Id(USER_ID)).thenReturn(List.of(holding("USD", 20_000.0)));
        when(depositRepository.findByOwner_Id(USER_ID)).thenReturn(List.of(deposit("USD", "4720")));
        givenSaveEchoesBack();

        StressRunService.RunView view = service.run(USER_ID, SCENARIO_CODE);

        assertEquals(20_000_000L, view.equityAssetKrw());
        assertEquals(24_720_000L, view.fxAssetBeforeKrw());
        assertEquals(-4_000_000L, view.equityEffectKrw());
        assertEquals(2_072_000L, view.fxEffectKrw());
        assertEquals(-1_928_000L, view.totalEffectKrw());
        assertEquals(22_792_000L, view.fxAssetAfterKrw());
        assertEquals(StressCalculator.FX_CUSHIONS_EQUITY_LOSS, view.interpretationCode());
        assertEquals(TODAY, view.baseDate());
        assertEquals(-0.20, view.equityShockPct());
        assertEquals(0.10, view.fxShockPct());
        assertEquals(SCENARIO_CODE, view.scenario().scenarioCode());
        assertEquals("주가 하락 + 원화 약세", view.scenario().nameKo());
        assertTrue(StressRunService.CONDITIONAL_NOTE.contains("예측이 아니라"));
    }

    @Test
    @DisplayName("어댑터가 고시하지 않는 통화는 평가에서 빠질 뿐 실행을 막지 않는다 — /xray 와 같은 규약 (이슈 #57)")
    void skipsCurrenciesWithoutRate() {
        givenUser();
        givenScenario(SCENARIO_CODE, "0.0000", "0.0000");
        when(fxRateProvider.fetchLatest("USD_KRW"))
                .thenReturn(rateSnapshot("USD_KRW", new BigDecimal("1382.40")));
        // GBP 는 ECOS item-code 가 없다. 예전에는 이 예외가 그대로 올라가 400 이 났다.
        when(fxRateProvider.fetchLatest("GBP_KRW"))
                .thenThrow(new IllegalArgumentException("Unsupported pairCode for ECOS: GBP_KRW"));
        when(holdingRepository.findByOwner_Id(USER_ID))
                .thenReturn(List.of(holding("USD", 100.0), holding("GBP", 100.0)));
        when(depositRepository.findByOwner_Id(USER_ID)).thenReturn(List.of(deposit("GBP", "1000")));
        givenSaveEchoesBack();

        StressRunService.RunView view = service.run(USER_ID, SCENARIO_CODE);

        // USD 100 × 1,382.40 = 138,240 만 남는다. GBP 종목·예금은 빠진다.
        assertEquals(138_240L, view.equityAssetKrw());
        assertEquals(138_240L, view.fxAssetBeforeKrw());
    }

    @Test
    @DisplayName("고시 단위가 100단위인 통화(JPY)는 1단위 환율로 정규화해 평가한다")
    void normalizesQuoteUnit() {
        givenUser();
        givenScenario(SCENARIO_CODE, "0.0000", "0.0000");
        when(fxRateProvider.fetchLatest("JPY_KRW"))
                .thenReturn(rateSnapshot("JPY_KRW", new BigDecimal("900")));
        when(holdingRepository.findByOwner_Id(USER_ID)).thenReturn(List.of(holding("JPY", 10_000.0)));
        when(depositRepository.findByOwner_Id(USER_ID)).thenReturn(List.of());
        givenSaveEchoesBack();

        StressRunService.RunView view = service.run(USER_ID, SCENARIO_CODE);

        // 100엔당 900원 → 1엔당 9원. 10,000엔 = 90,000원 (정규화 없이는 9,000,000원)
        assertEquals(90_000L, view.equityAssetKrw());
    }

    @Test
    @DisplayName("scenario_code 가 비면 400")
    void blankScenarioCode() {
        assertThrows(InvalidRequestException.class, () -> service.run(USER_ID, null));
        assertThrows(InvalidRequestException.class, () -> service.run(USER_ID, "  "));
    }

    @Test
    @DisplayName("사용자가 없으면 404")
    void unknownUser() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());

        assertThrows(NotFoundException.class, () -> service.run(USER_ID, SCENARIO_CODE));
    }

    @Test
    @DisplayName("시나리오가 없으면 404")
    void unknownScenario() {
        givenUser();
        when(scenarioRepository.findById("nope")).thenReturn(Optional.empty());

        assertThrows(NotFoundException.class, () -> service.run(USER_ID, "nope"));
    }

    @Test
    @DisplayName("engine 계약 위반(해외주식 > 외화자산)은 400 으로 표면화한다")
    void engineContractViolationBecomes400() {
        givenUser();
        // 허용 범위(-1.0 ~ 10.0)를 벗어난 충격률이 마스터에 들어온 경우.
        givenScenario(SCENARIO_CODE, "-2.0000", "0.1000");
        givenRates();
        when(holdingRepository.findByOwner_Id(USER_ID)).thenReturn(List.of(holding("USD", 100.0)));
        when(depositRepository.findByOwner_Id(USER_ID)).thenReturn(List.of());

        assertThrows(InvalidRequestException.class, () -> service.run(USER_ID, SCENARIO_CODE));
    }

    @Test
    @DisplayName("이력은 실행 시점 충격률 스냅샷을 그대로 낸다")
    void listRuns() {
        when(scenarioRepository.findAll()).thenReturn(List.of(scenario(SCENARIO_CODE, "-0.2000", "0.1000")));
        when(runRepository.findByOwner_IdOrderByCreatedAtDesc(USER_ID)).thenReturn(List.of(
                run(SCENARIO_CODE, -0.20, 0.10),
                run("deleted_scenario", -0.30, 0.20)));

        List<StressRunService.RunHistoryView> history = service.listRuns(USER_ID);

        assertEquals(2, history.size());
        assertEquals(SCENARIO_CODE, history.get(0).scenario().scenarioCode());
        assertEquals(-4_000_000L, history.get(0).equityEffectKrw());
        assertEquals(2_072_000L, history.get(0).fxEffectKrw());
        assertEquals(-1_928_000L, history.get(0).totalEffectKrw());
        assertEquals(TODAY, history.get(0).baseDate());
        // 마스터에서 사라진 시나리오의 이력도 지우지 않는다 — 이름 없이 남는다.
        assertNull(history.get(1).scenario());
        assertEquals(-0.30, history.get(1).equityShockPct());
    }

    @Test
    @DisplayName("이력이 없으면 빈 목록 — 오류가 아니다")
    void listRunsEmpty() {
        when(scenarioRepository.findAll()).thenReturn(List.of());
        when(runRepository.findByOwner_IdOrderByCreatedAtDesc(USER_ID)).thenReturn(List.of());

        assertTrue(service.listRuns(USER_ID).isEmpty());
    }

    // ── 픽스처 ───────────────────────────────────────────────────

    private void givenUser() {
        lenient().when(userRepository.findById(USER_ID)).thenReturn(Optional.of(testUser()));
    }

    private void givenScenario(String code, String equityShock, String fxShock) {
        lenient().when(scenarioRepository.findById(code))
                .thenReturn(Optional.of(scenario(code, equityShock, fxShock)));
    }

    private void givenRates() {
        lenient().when(fxRateProvider.fetchLatest("USD_KRW"))
                .thenReturn(rateSnapshot("USD_KRW", USD_KRW));
    }

    private void givenSaveEchoesBack() {
        lenient().when(runRepository.save(any(StressTestRun.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    private static RateSnapshot rateSnapshot(String pairCode, BigDecimal rate) {
        return new RateSnapshot(pairCode, rate, TODAY, "TEST",
                TODAY.atStartOfDay().toInstant(ZoneOffset.UTC));
    }

    private static User testUser() {
        return User.create("test@divurve.local", "테스트", "hash");
    }

    private static Holding holding(String currencyCode, double amountLocal) {
        return Holding.create(testUser(), "TEST", currencyCode, amountLocal, 1.0);
    }

    private static Deposit deposit(String currencyCode, String amount) {
        return Deposit.create(testUser(), currencyCode, new BigDecimal(amount));
    }

    private static StressScenario scenario(String code, String equityShock, String fxShock) {
        StressScenario scenario = newInstance(StressScenario.class);
        set(scenario, "scenarioCode", code);
        set(scenario, "nameKo", "주가 하락 + 원화 약세");
        set(scenario, "equityShockPct", new BigDecimal(equityShock));
        set(scenario, "fxShockPct", new BigDecimal(fxShock));
        set(scenario, "referenceEvent", "2020년 3월 변동성 급등 참고");
        set(scenario, "assumptionNote", "주가 충격을 먼저 적용한 뒤 환율 충격을 적용합니다.");
        set(scenario, "isDefault", true);
        set(scenario, "sortOrder", (short) 1);
        return scenario;
    }

    private static StressTestRun run(String scenarioCode, double equityShock, double fxShock) {
        StressTestRun run = StressTestRun.create(
                testUser(), scenarioCode, TODAY, equityShock, fxShock,
                -4_000_000L, 2_072_000L, -1_928_000L);
        set(run, "id", UUID.randomUUID());
        set(run, "createdAt", Instant.parse("2026-09-01T15:30:00Z"));
        return run;
    }

    /** JPA 전용 protected 생성자와 DB 가 채우는 필드를 테스트에서 직접 구성한다. */
    private static <T> T newInstance(Class<T> type) {
        try {
            var constructor = type.getDeclaredConstructor();
            constructor.setAccessible(true);
            return constructor.newInstance();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void set(Object target, String fieldName, Object value) {
        try {
            Field field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
