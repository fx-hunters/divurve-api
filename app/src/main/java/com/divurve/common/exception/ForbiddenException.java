package com.divurve.common.exception;

import org.springframework.http.HttpStatus;

/**
 * 403 Forbidden 응답 — <b>인증은 됐지만 그 자원에 권한이 없다</b>. 두 경우에 쓴다:
 * <ul>
 *   <li>타인 리소스 접근 (NFR-SE-02)</li>
 *   <li>관리자 전용 경로에 일반 사용자가 접근 (이슈 #111, {@code AdminAccessService})</li>
 * </ul>
 * 둘 다 토큰을 갱신해도 해결되지 않는다는 점이 401 과 다르다.
 *
 * <p>에러코드는 명세 §1.3 의 6종 닫힌 집합을 지키기 위해 {@code FORBIDDEN} 으로 고정한다.
 * v1 의 투기 목적 게이트({@code SPECULATIVE_PURPOSE_BLOCKED})는 명세 v2 §0.1 에서 삭제됐다 —
 * {@code is_speculative} 는 ERD 에만 있고 요구사항 v2 에 근거가 없어 §8 미결정으로 이동했다.
 */
public class ForbiddenException extends ApiException {

    private static final String CODE = "FORBIDDEN";

    public ForbiddenException(String message) {
        super(HttpStatus.FORBIDDEN, CODE, message, null);
    }

    /**
     * @param field 관련 요청 필드명
     */
    public ForbiddenException(String message, String field) {
        super(HttpStatus.FORBIDDEN, CODE, message, field);
    }
}
