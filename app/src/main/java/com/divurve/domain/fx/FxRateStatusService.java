package com.divurve.domain.fx;

import com.divurve.common.architecture.UseCase;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;
import org.springframework.transaction.annotation.Transactional;

/**
 * 환율 적재 현황 — "마지막으로 언제 갱신됐는가" (이슈 #128).
 *
 * <p><b>왜 필요한가</b> — 관리자 콘솔의 수동 갱신 카드는 버튼을 한 번 눌러 봐야만 갱신 시각을
 * 알 수 있었다. 갱신 응답의 {@code refreshed_at} 은 그 호출의 시각일 뿐 어디에도 남지 않고,
 * 갱신 서비스는 무상태라 값을 들고 있지도 않다. 매일 09:30 KST 에 도는 스케줄러가 돌린 갱신은
 * 그래서 화면에 전혀 드러나지 않았다.
 *
 * <p><b>실행 이력 테이블을 만들지 않는다.</b> 서버에 이미 남아 있는 진실값이 있다 —
 * {@code fx_rates.fetched_at} 이다. 스케줄러가 넣든 관리자가 손으로 넣든 같은 upsert 를 지나므로
 * 그 최댓값이 곧 "마지막 갱신" 이다. 별도 테이블은 같은 사실을 두 곳에 적어 어긋날 자리를
 * 만들 뿐이다. 그래서 이 이슈에는 스키마 변경이 없다.
 *
 * <p><b>거시지표는 답할 수 없다.</b> {@link com.divurve.domain.macro.MacroRefreshService} 는
 * 받아온 값을 의도적으로 저장하지 않는다(ALFRED vintage 문제 — 그 판단의 근거는 해당 클래스
 * javadoc 에 있다). 저장이 없으니 서버 기준 마지막 갱신도 없다. 없는 값을 지어내는 대신
 * {@code null} 로 비워 내보내고, 화면은 그 자리를 {@code -} 로 둔다.
 *
 * <p>읽기 전용이다 — 갱신을 트리거하지 않는다. 화면 진입만으로 외부 API 를 때리면 관리자가
 * 화면에 들어올 때마다 ECOS 호출이 늘어난다.
 */
@UseCase
public class FxRateStatusService {

    private final FxRateRepository fxRateRepository;
    private final Clock clock;

    public FxRateStatusService(FxRateRepository fxRateRepository, Clock clock) {
        this.fxRateRepository = Objects.requireNonNull(fxRateRepository, "fxRateRepository");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * 적재 현황을 읽는다.
     *
     * <p>전체 값은 쌍별 값에서 접는다 — 같은 질문을 DB 에 두 번 묻지 않는다. 쌍이 하나도 없으면
     * (적재를 한 번도 돌리지 않은 새 환경) 전체도 {@code null} 이다.
     *
     * @return 전체·쌍별 마지막 적재 시각과 마지막 고시일
     */
    @Transactional(readOnly = true)
    public StatusResult status() {
        List<PairStatus> pairs = fxRateRepository.freshnessByPair().stream()
                .map(freshness -> new PairStatus(
                        freshness.getPairCode(),
                        freshness.getLastFetchedAt(),
                        freshness.getLastQuoteDate()))
                .toList();
        return new StatusResult(
                max(pairs.stream().map(PairStatus::lastFetchedAt)),
                max(pairs.stream().map(PairStatus::lastQuoteDate)),
                pairs,
                clock.instant());
    }

    /**
     * 값이 있는 것들 중 가장 큰 것. 하나도 없으면 {@code null}.
     *
     * <p>{@code null} 을 걸러 내는 이유 — 두 컬럼 모두 {@code not null} 이라 실제로는 나오지
     * 않지만, 그 사실에 기대면 스키마가 느슨해지는 날 조용히 {@code NullPointerException} 이
     * 된다. 비교 전에 거르는 편이 싸다.
     */
    private static <T extends Comparable<? super T>> T max(Stream<T> values) {
        return values.filter(Objects::nonNull).max(Comparator.naturalOrder()).orElse(null);
    }

    /**
     * 환율 적재 현황.
     *
     * @param lastFetchedAt 전체 통화쌍 중 마지막 적재 시각. 적재가 없으면 {@code null}
     * @param lastQuoteDate 전체 통화쌍 중 가장 최근 고시일. 적재가 없으면 {@code null}
     * @param pairs         통화쌍별 현황. 행이 있는 쌍만 나온다
     * @param checkedAt     조회 시각
     */
    public record StatusResult(
            Instant lastFetchedAt, LocalDate lastQuoteDate,
            List<PairStatus> pairs, Instant checkedAt) {

        /** 방어적 복사 — 호출자가 목록을 바꿔도 결과가 흔들리지 않는다. */
        public StatusResult {
            pairs = List.copyOf(pairs);
        }
    }

    /**
     * 통화쌍 하나의 현황.
     *
     * @param pairCode      통화쌍 코드
     * @param lastFetchedAt 마지막 적재 시각 — 우리가 언제 가져왔는가
     * @param lastQuoteDate 마지막 고시일 — 어느 날짜까지 채워져 있는가
     */
    public record PairStatus(String pairCode, Instant lastFetchedAt, LocalDate lastQuoteDate) {
    }
}
