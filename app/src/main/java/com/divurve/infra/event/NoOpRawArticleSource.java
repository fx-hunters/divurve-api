package com.divurve.infra.event;

import com.divurve.common.architecture.ExternalAdapter;
import com.divurve.domain.port.EconEventExtractor.RawArticle;
import com.divurve.domain.port.RawArticleSource;
import java.util.List;

/**
 * 실 크롤러가 붙기 전까지 아무 원문도 공급하지 않는 {@link RawArticleSource} 자리표시자
 * (이슈 #184, 이슈 #74 에서 도입한 {@code MockRawArticleSource} 를 대체한다).
 *
 * <p><b>왜 예시 원문을 없앴나</b> — 이전 구현은 시연용 예시 기사 3건({@code demo://sample/...})을
 * 돌려줬다. 추출 배치가 켜진 상태에서 그것이 실제로 돌면서, LLM 이 예시에서 뽑은 일정이
 * {@code econ_events} 에 {@code AI_EXTRACTED} 로 저장됐다. {@code GET /events} 와 홈
 * {@code attention} 응답에는 {@code source_kind} 가 없어, <b>사용자에게는 공식 일정과 똑같이
 * 보였다</b> — 출처를 지어내지 않는다는 원칙(FR-CM-10)에 어긋나는 상태였다.
 *
 * <p><b>설정으로 끄는 것만으로는 부족했다.</b> {@code ANTHROPIC_EXTRACT_SCHEDULE_ENABLED=false}
 * 로 멈출 수는 있지만, 누군가 다시 켜면 같은 일이 반복된다. 실 원문 소스가 없는 동안에는
 * <b>배치를 켜도 무해해야</b> 하고, 빈 목록이 그 상태를 만든다.
 *
 * <p>추출기가 살아 있는지 확인하는 경로는 {@code POST /admin/ai/extract-preview} 에 남아 있다 —
 * 관리자가 원문을 직접 붙여넣고, 저장하지 않는다(이슈 #122).
 *
 * <p>실 크롤러·RSS·API 연동이 붙으면 이 클래스를 교체한다. 대상 매체와 수집 주기는 아직 팀
 * 결정 전이다(이슈 #74 "선행 조건").
 */
@ExternalAdapter
public class NoOpRawArticleSource implements RawArticleSource {

    /**
     * 항상 빈 목록. "실제로는 수집하지 않았다" 는 사실을 그대로 드러낸다 — 값을 지어내
     * 반환하는 것보다 안전한 기본값이다({@link NoOpEconEventExtractor} 와 같은 판단).
     */
    @Override
    public List<RawArticle> fetchRecent() {
        return List.of();
    }
}
