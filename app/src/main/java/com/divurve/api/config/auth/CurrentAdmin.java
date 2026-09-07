package com.divurve.api.config.auth;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 관리자 전용 엔드포인트의 사용자 id 파라미터 (이슈 #111).
 *
 * <p>{@link CurrentUser} 와 같은 구조다 — 붙이면 인증이 필수가 되고, 붙이지 않으면 공개 경로다.
 * 다른 점은 인가까지 본다는 것 하나뿐이다: 토큰이 없으면 401, 관리자가 아니면 403.
 *
 * <p>경로 화이트리스트를 두지 않는 이유도 {@code CurrentUser} 와 같다. {@code /api/v1/admin/**} 를
 * 인터셉터로 막는 방식은 "경로 패턴을 빠뜨리면 무방비로 열린다" 는 실패 모드를 갖는다. 파라미터로
 * 받으면 <b>메서드 시그니처에 적힌 것이 곧 보안 요구사항</b>이라 누락이 눈에 보인다.
 */
@Documented
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface CurrentAdmin {
}
