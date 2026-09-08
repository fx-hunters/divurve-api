package com.divurve.infra.event;

import com.divurve.common.architecture.ExternalAdapter;
import com.divurve.domain.port.EconEventExtractor;
import java.util.List;
import java.util.Objects;

/**
 * {@code app.external.anthropic.extract-enabled} 가 꺼져 있을 때(기본값) 항상 존재하는
 * {@link EconEventExtractor} 자리표시자 (이슈 #74).
 *
 * <p><b>왜 필요한가</b> — {@code EconEventIngestionService} 는 생성자로 {@link EconEventExtractor}
 * 빈 하나를 요구한다. 실 추출기({@link com.divurve.infra.ai.ClaudeEconEventExtractor})는
 * {@code extract-enabled=true} 일 때만 만들어지므로, 꺼진 기본 설정에서도 컨텍스트가 뜨려면
 * 항상 등록되는 구현체가 하나 있어야 한다 — {@code ClaudeAiProvider}/{@code MockAiProvider} 가
 * 공존하는 패턴과 같다(이슈 #73). 둘 다 등록되면 실 추출기 쪽 {@code @Primary} 로 주입된다.
 *
 * <p>이 구현은 아무것도 추출하지 않는다 — 크롤러도 대상 뉴스 소스도 아직 팀 결정 전이라
 * (이슈 #74 "선행 조건"), "실제로는 추출하지 않았다"는 사실을 빈 배열로 그대로 드러낸다.
 * 값을 지어내 반환하는 것(FR-CM-10 출처 날조 금지)보다 안전한 기본값이다.
 */
@ExternalAdapter
public class NoOpEconEventExtractor implements EconEventExtractor {

    @Override
    public ExtractOutcome extract(RawArticle article) {
        Objects.requireNonNull(article, "article");
        return ExtractOutcome.withoutLlm(List.of());
    }
}
