package com.divurve.engine.planner;

import com.divurve.engine.EngineComponent;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * 조정 선택지를 우선 조건에 맞춰 정렬한다 (플래너 명세 §15·§17).
 *
 * <p>네 선택지를 <b>모두</b> 낸다. 사용자의 우선 조건을 깨는 선택지도 빼지 않는다 — 예산을
 * 지키는 길이 목표 금액을 줄이는 것뿐인 상황이 실제로 있고, 그때 선택지를 감추면 사용자는
 * 막다른 화면을 보게 된다. 대신 <b>순서를 뒤로 민다</b>: 우선 조건은 사용자가 정한 것이므로
 * 그것을 깨는 선택이 목록 맨 앞에 오면 시스템이 권하는 것처럼 읽힌다 (§17 — 시스템은 사용자의
 * 우선 조건을 임의로 바꾸지 않는다).
 *
 * <p>{@link AdjustmentOption#PAUSE_PLAN} 은 어떤 조건도 포기하지 않지만 진행이 멈추므로 늘
 * 마지막에 둔다 — 다른 조정으로 계속할 수 있다면 그쪽이 먼저다.
 */
@EngineComponent
public class AdjustmentOptionSelector {

    /**
     * 우선 조건을 깨지 않는 선택지 → 깨는 선택지 → 일시 정지 순으로 정렬한다.
     *
     * <p>같은 순위 안에서는 열거 선언 순서를 지킨다. 정렬이 안정 정렬이라 호출할 때마다 같은
     * 목록이 나온다 — 화면의 선택지 순서가 요청마다 흔들리면 사용자가 오작동으로 읽는다.
     *
     * @param priority 사용자가 정한 우선 조건 (명세 §5.1)
     * @return 네 선택지 전부, 권장 순서대로
     */
    public List<AdjustmentOption> orderedFor(PriorityDimension priority) {
        Objects.requireNonNull(priority, "priority");
        return Stream.of(AdjustmentOption.values())
                .sorted(Comparator.comparingInt(option -> rank(option, priority)))
                .toList();
    }

    private static int rank(AdjustmentOption option, PriorityDimension priority) {
        if (option == AdjustmentOption.PAUSE_PLAN) {
            return 2;
        }
        return option.breaks(priority) ? 1 : 0;
    }
}
