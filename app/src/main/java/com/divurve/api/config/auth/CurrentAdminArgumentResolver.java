package com.divurve.api.config.auth;

import com.divurve.common.exception.UnauthorizedException;
import com.divurve.domain.port.AuthPrincipal;
import com.divurve.domain.user.AdminAccessService;
import java.util.Objects;
import java.util.UUID;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * {@link CurrentAdmin} 파라미터를 해석하면서 관리자 인가까지 강제한다 (이슈 #111).
 *
 * <p>{@link CurrentUserArgumentResolver} 가 "로그인했는가" 를 보는 자리라면, 여기는 그 뒤에
 * "관리자인가" 를 붙인 자리다. 두 판정을 한 곳에 모아 두면 컨트롤러가 인가 검사를 잊을 방법이 없다.
 *
 * <p>순서가 중요하다 — 토큰이 없으면 {@code AdminAccessService} 를 부르기 전에 401 로 끝낸다.
 * 반대로 하면 로그인하지 않은 요청에 대해 DB 를 치게 되고, 응답도 403 이 되어
 * "권한이 없는 것" 과 "로그인하지 않은 것" 이 구분되지 않는다.
 */
public class CurrentAdminArgumentResolver implements HandlerMethodArgumentResolver {

    private final AdminAccessService adminAccessService;

    public CurrentAdminArgumentResolver(AdminAccessService adminAccessService) {
        this.adminAccessService = Objects.requireNonNull(adminAccessService, "adminAccessService");
    }

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.hasParameterAnnotation(CurrentAdmin.class)
                && UUID.class.equals(parameter.getParameterType());
    }

    @Override
    public Object resolveArgument(
            MethodParameter parameter,
            ModelAndViewContainer mavContainer,
            NativeWebRequest webRequest,
            WebDataBinderFactory binderFactory) {
        AuthPrincipal principal = CurrentUserContext.get().orElseThrow(UnauthorizedException::new);
        adminAccessService.requireAdmin(principal.userId());
        return principal.userId();
    }
}
