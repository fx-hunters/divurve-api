package com.divurve.api.config.auth;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 요청을 보낸 클라이언트의 IP 를 받는 파라미터 (이슈 #111).
 *
 * <p>도메인은 서블릿을 몰라야 하므로 IP 추출은 api 레이어에서 끝내고 값만 넘긴다. 컨트롤러가
 * {@code HttpServletRequest} 를 직접 들면 기존의 "생성자 직접 호출" 컨트롤러 테스트 패턴이 깨진다.
 */
@Documented
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface ClientIp {
}
