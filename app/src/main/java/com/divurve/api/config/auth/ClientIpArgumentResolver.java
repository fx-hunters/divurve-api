package com.divurve.api.config.auth;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * {@link ClientIp} 파라미터를 요청에서 뽑는다 (이슈 #111).
 *
 * <p>{@code X-Forwarded-For} 를 먼저 본다 — 배포 환경(Render)은 프록시 뒤에 있어
 * {@code getRemoteAddr()} 이 프록시 주소를 준다. 헤더는 {@code client, proxy1, proxy2} 형태이므로
 * <b>첫 번째 항목</b>이 원 클라이언트다.
 *
 * <p><b>이 값을 신뢰하지 않는다.</b> {@code X-Forwarded-For} 는 클라이언트가 마음대로 보낼 수 있는
 * 헤더다. 그래서 이 값은 운영 점검용 표시에만 쓰고, 인증·인가·차단 판단에는 쓰지 않는다.
 *
 * <p>알 수 없으면 {@code null} 을 준다 — 알 수 없다는 사실을 그대로 남기는 편이,
 * {@code "unknown"} 같은 문자열을 지어내 IP 컬럼에 섞는 것보다 낫다.
 */
public class ClientIpArgumentResolver implements HandlerMethodArgumentResolver {

    static final String FORWARDED_FOR_HEADER = "X-Forwarded-For";

    /** {@code users.last_login_ip varchar(45)} — IPv6 최대 표기 길이. 넘으면 저장이 실패한다. */
    static final int MAX_LENGTH = 45;

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.hasParameterAnnotation(ClientIp.class)
                && String.class.equals(parameter.getParameterType());
    }

    @Override
    public Object resolveArgument(
            MethodParameter parameter,
            ModelAndViewContainer mavContainer,
            NativeWebRequest webRequest,
            WebDataBinderFactory binderFactory) {
        HttpServletRequest request = webRequest.getNativeRequest(HttpServletRequest.class);
        if (request == null) {
            return null;
        }
        return resolve(request.getHeader(FORWARDED_FOR_HEADER), request.getRemoteAddr());
    }

    /**
     * 헤더와 소켓 주소에서 클라이언트 IP 를 고른다. 서블릿 없이 테스트할 수 있도록 분리했다.
     *
     * @param forwardedFor {@code X-Forwarded-For} 헤더 값. 없으면 {@code null}
     * @param remoteAddr   소켓 상대 주소
     * @return 클라이언트 IP. 둘 다 쓸 수 없으면 {@code null}
     */
    static String resolve(String forwardedFor, String remoteAddr) {
        String candidate = firstForwardedFor(forwardedFor);
        if (candidate == null) {
            candidate = trimToNull(remoteAddr);
        }
        if (candidate == null || candidate.length() > MAX_LENGTH) {
            // 45자를 넘는 값은 조작된 헤더다. 잘라서 저장하면 없는 주소를 만든 셈이 된다.
            return null;
        }
        return candidate;
    }

    private static String firstForwardedFor(String headerValue) {
        String header = trimToNull(headerValue);
        if (header == null) {
            return null;
        }
        int comma = header.indexOf(',');
        return trimToNull(comma < 0 ? header : header.substring(0, comma));
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
