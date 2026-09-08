package com.divurve.domain.fit;

import com.divurve.common.architecture.UseCase;
import com.divurve.common.exception.InvalidRequestException;
import com.divurve.common.exception.NotFoundException;
import com.divurve.domain.holding.DepositRepository;
import com.divurve.domain.holding.FxAssetValuator;
import com.divurve.domain.holding.HoldingRepository;
import com.divurve.domain.settings.RiskProfileService;
import com.divurve.domain.settings.RiskProfileView;
import com.divurve.domain.user.UserRepository;
import com.divurve.engine.concentration.ConcentrationCalculator;
import com.divurve.engine.concentration.ConcentrationThresholdTable;
import com.divurve.engine.diversification.DiversificationAdjustmentException;
import com.divurve.engine.diversification.DiversificationSimulator;
import com.divurve.engine.weight.WeightCalculator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

/**
 * Fit 유스케이스 — 성향과 현재 노출의 <b>관계</b>를 사실값으로만 보고한다 (FR-FT-01~03, 명세 §5.5 · §5.6).
 *
 * <p>이슈 #54(7.3)에서 고친 것:
 * <ul>
 *   <li>{@code GET /fit/concentration} → {@code GET /fit}, {@code POST /fit/simulate} →
 *       {@code POST /fit/preview} (명세 §3).</li>
 *   <li>집중도 기준선 {@code 0.35} 하드코딩 → 위험성향 등급 기반 {@link ConcentrationThresholdTable}.</li>
 *   <li>{@code suggested_goal} 과 "분산 투자를 고려하세요" 류의 문구 <b>삭제</b> — 서버가 통화별 매수를
 *       제안하는 것은 FR-FT-04·FR-FT-06 위반이다. {@code relation} 은 <b>코드와 사실값만</b> 내린다.</li>
 *   <li>미리보기가 임의 상수(변동성 0.12/0.14/0.10, 상관 0.5)로 포트폴리오 변동성을 만들어내던 것을
 *       빼고, 명세 §5.6 그대로 <b>집중도·민감도 변화</b>만 계산한다.</li>
 *   <li>JPY 100엔 고시 미정규화(자산 100배)를 {@link FxAssetValuator} 로 해소.</li>
 * </ul>
 */
@UseCase
public class FitService {

    /** 기준선이 MVP 가설값임을 사용자에게 고지한다 (명세 §5.5). */
    public static final String BASIS_NOTE =
            "참고 기준선은 MVP 가설값이며 통계적으로 검증된 배분 기준이 아닙니다.";

    /** 주력 통화 비중이 성향 기준선을 넘은 관계. */
    public static final String RELATION_ABOVE = "concentration_above_profile";
    /** 주력 통화 비중이 성향 기준선 이내인 관계. */
    public static final String RELATION_WITHIN = "concentration_within_profile";
    /** 성향 미측정 또는 외화자산 없음 — 관계를 말할 수 없다. */
    public static final String RELATION_UNKNOWN = "risk_profile_not_measured";

    private static final String FIELD_CURRENCY_CODE = "currency_code";
    private static final String FIELD_DELTA_SHARE = "delta_share";

    private final HoldingRepository holdingRepository;
    private final DepositRepository depositRepository;
    private final UserRepository userRepository;
    private final RiskProfileService riskProfileService;
    private final FxAssetValuator fxAssetValuator;
    private final WeightCalculator weightCalculator;
    private final ConcentrationCalculator concentrationCalculator;
    private final ConcentrationThresholdTable concentrationThresholdTable;
    private final DiversificationSimulator diversificationSimulator;

    public FitService(
            HoldingRepository holdingRepository,
            DepositRepository depositRepository,
            UserRepository userRepository,
            RiskProfileService riskProfileService,
            FxAssetValuator fxAssetValuator,
            WeightCalculator weightCalculator,
            ConcentrationCalculator concentrationCalculator,
            ConcentrationThresholdTable concentrationThresholdTable,
            DiversificationSimulator diversificationSimulator) {
        this.holdingRepository = Objects.requireNonNull(holdingRepository, "holdingRepository is null");
        this.depositRepository = Objects.requireNonNull(depositRepository, "depositRepository is null");
        this.userRepository = Objects.requireNonNull(userRepository, "userRepository is null");
        this.riskProfileService = Objects.requireNonNull(riskProfileService, "riskProfileService is null");
        this.fxAssetValuator = Objects.requireNonNull(fxAssetValuator, "fxAssetValuator is null");
        this.weightCalculator = Objects.requireNonNull(weightCalculator, "weightCalculator is null");
        this.concentrationCalculator =
                Objects.requireNonNull(concentrationCalculator, "concentrationCalculator is null");
        this.concentrationThresholdTable =
                Objects.requireNonNull(concentrationThresholdTable, "concentrationThresholdTable is null");
        this.diversificationSimulator =
                Objects.requireNonNull(diversificationSimulator, "diversificationSimulator is null");
    }

    /**
     * 성향·집중도·둘의 관계를 조회한다 (명세 §5.5).
     *
     * @param userId 사용자 ID (NFR-SE-03)
     * @return 성향 블록 + 집중도 + 관계 코드·사실값
     * @throws NotFoundException 사용자를 찾을 수 없는 경우
     */
    @Transactional(readOnly = true)
    public FitDiagnosis getFit(UUID userId) {
        requireUser(userId);

        RiskProfileView profile = riskProfileService.getRiskProfile(userId);
        Double threshold = concentrationThresholdTable.thresholdFor(profile.riskType());
        ConcentrationCalculator.ConcentrationResult concentration =
                concentrationCalculator.diagnose(currencyAssets(userId), threshold);

        return new FitDiagnosis(
                profile, view(concentration), relationCode(concentration.status()));
    }

    /**
     * 통화 비중을 <b>가정해서</b> 바꿨을 때의 집중도·민감도 변화만 계산한다 (FR-FT-03, 명세 §5.6).
     * 저장하지 않는다.
     *
     * @param userId       사용자 ID
     * @param currencyCode 가정을 적용할 통화
     * @param deltaShare   비중 변화량 (예: {@code 0.10} 은 10%p 상향)
     * @return 가정 전후의 노출·집중도·민감도
     * @throws NotFoundException       사용자를 찾을 수 없는 경우
     * @throws InvalidRequestException 통화가 비었거나, 포트폴리오에 없거나, 조정 후 비중이 범위를 벗어난 경우
     */
    @Transactional(readOnly = true)
    public FitPreview preview(UUID userId, String currencyCode, double deltaShare) {
        requireUser(userId);

        if (currencyCode == null || currencyCode.isBlank()) {
            throw new InvalidRequestException("통화코드는 필수입니다.", FIELD_CURRENCY_CODE);
        }

        Map<String, Long> before = currencyAssets(userId);
        long fxAssetKrw = before.values().stream().mapToLong(Long::longValue).sum();

        // 외화자산이 0 이어도 여기서 따로 걸러내지 않는다 — redistributeAmounts 가 같은 사유를
        // EMPTY_PORTFOLIO 로 판정해 아래 catch 에서 field=currency_code 로 나간다. 검증을 두 곳에
        // 두면(이 메서드와 engine) 나중에 한쪽만 바뀌어 어긋날 수 있다(이슈 #89).
        Map<String, Long> after;
        try {
            after = diversificationSimulator.redistributeAmounts(
                    before, currencyCode.toUpperCase(), deltaShare);
        } catch (DiversificationAdjustmentException ex) {
            // engine 계약 위반은 사용자 입력 오류(400)로 표면화한다 — 어느 필드가 원인인지는
            // 메시지 문자열이 아니라 engine 이 구조화해 올린 reason() 으로 판정한다(이슈 #89).
            throw new InvalidRequestException(ex.getMessage(), fieldFor(ex.reason()));
        }

        Double threshold =
                concentrationThresholdTable.thresholdFor(riskProfileService.getRiskProfile(userId).riskType());

        WeightCalculator.Sensitivity sensitivityBefore =
                weightCalculator.calculateSensitivity1pct(before);
        WeightCalculator.Sensitivity sensitivityAfter =
                weightCalculator.calculateSensitivity1pct(after);

        return new FitPreview(
                currencyCode.toUpperCase(),
                deltaShare,
                fxAssetKrw,
                weightCalculator.calculateExposureMap(before, fxAssetKrw),
                weightCalculator.calculateExposureMap(after, fxAssetKrw),
                view(concentrationCalculator.diagnose(before, threshold)),
                view(concentrationCalculator.diagnose(after, threshold)),
                threshold,
                new SensitivityView(sensitivityBefore.totalKrw(), sensitivityBefore.byCurrency()),
                new SensitivityView(sensitivityAfter.totalKrw(), sensitivityAfter.byCurrency()));
    }

    /**
     * {@link DiversificationAdjustmentException.Reason} → 요청 필드 매핑 (이슈 #89).
     *
     * <p>대상 통화 문제({@code UNKNOWN_CURRENCY}·{@code EMPTY_PORTFOLIO})는 {@code currency_code} 가
     * 원인이고, 조정량이 범위를 벗어난 문제({@code SHARE_OUT_OF_RANGE})만 {@code delta_share} 가
     * 원인이다. enum 이 닫힌 집합이라 새 원인이 추가되면 컴파일이 이 메서드를 놓치지 않는다.
     */
    private static String fieldFor(DiversificationAdjustmentException.Reason reason) {
        return switch (reason) {
            case UNKNOWN_CURRENCY, EMPTY_PORTFOLIO -> FIELD_CURRENCY_CODE;
            case SHARE_OUT_OF_RANGE -> FIELD_DELTA_SHARE;
        };
    }

    private Map<String, Long> currencyAssets(UUID userId) {
        return fxAssetValuator.valuate(
                        holdingRepository.findByOwner_Id(userId),
                        depositRepository.findByOwner_Id(userId))
                .currencyToAssetKrw();
    }

    private void requireUser(UUID userId) {
        userRepository.findById(userId)
                .orElseThrow(() -> new NotFoundException("사용자를 찾을 수 없습니다."));
    }

    /**
     * 집중도 상태를 관계 코드로 옮긴다. 여기서 끝이다 — "적합"·"부적합"도, 점수도, 등급도 내리지 않는다
     * (FR-FT-04). 문장화는 {@code /ai/explain} 이 담당한다.
     */
    private String relationCode(String concentrationStatus) {
        return switch (concentrationStatus) {
            case ConcentrationCalculator.ABOVE_THRESHOLD -> RELATION_ABOVE;
            case ConcentrationCalculator.WITHIN_THRESHOLD -> RELATION_WITHIN;
            default -> RELATION_UNKNOWN;
        };
    }

    /** engine 결과를 도메인 경계 record 로 옮긴다 — api 는 engine 타입을 보지 않는다(문서 4.3). */
    private static ConcentrationView view(ConcentrationCalculator.ConcentrationResult result) {
        return new ConcentrationView(
                result.topCurrency(), result.topShare(), result.threshold(),
                result.status(), result.gapPp());
    }

    /** {@code GET /fit} 응답 재료 (명세 §5.5). */
    public record FitDiagnosis(
            RiskProfileView riskProfile,
            ConcentrationView concentration,
            String relationCode) {
    }

    /**
     * 집중도 진단.
     *
     * @param topCurrencyCode 주력 통화. 외화자산이 없으면 {@code null}
     * @param share           주력 통화 비중. 외화자산이 없으면 {@code null}
     * @param threshold       성향별 기준선. 미측정이면 {@code null}
     * @param status          {@code above_threshold} / {@code within_threshold} / {@code unknown}
     * @param gapPp           비중 − 기준선. 기준선이 없으면 {@code null}
     */
    public record ConcentrationView(
            String topCurrencyCode,
            Double share,
            Double threshold,
            String status,
            Double gapPp) {
    }

    /** 환율 1퍼센트 민감도. */
    public record SensitivityView(long totalKrw, Map<String, Long> byCurrency) {
    }

    /**
     * {@code POST /fit/preview} 응답 재료 (명세 §5.6).
     *
     * @param fxAssetKrw 가정 중에도 고정되는 외화자산 총액 ({@code assumption} 문구의 재료)
     */
    public record FitPreview(
            String currencyCode,
            double deltaShare,
            long fxAssetKrw,
            Map<String, Double> exposureBefore,
            Map<String, Double> exposureAfter,
            ConcentrationView concentrationBefore,
            ConcentrationView concentrationAfter,
            Double threshold,
            SensitivityView sensitivityBefore,
            SensitivityView sensitivityAfter) {
    }
}
