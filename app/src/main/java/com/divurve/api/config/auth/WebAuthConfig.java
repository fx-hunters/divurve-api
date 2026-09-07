package com.divurve.api.config.auth;

import com.divurve.domain.port.TokenProvider;
import com.divurve.domain.user.AdminAccessService;
import java.util.List;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 인증 미들웨어 등록 (이슈 #9). {@link AuthTokenInterceptor} 를 {@code /api/**} 경로에 건다.
 * 검증 로직 자체는 {@link TokenProvider}(infra 구현) 에 위임하므로 여기서는 배선만 담당한다.
 *
 * <p>{@link CurrentUserArgumentResolver} 도 함께 등록한다 (이슈 #50) — 인터셉터가 채운 컨텍스트를
 * 컨트롤러 파라미터로 꺼내는 반대편 절반이다.
 *
 * <p>{@link CurrentAdminArgumentResolver}(관리자 인가)와 {@link ClientIpArgumentResolver}(접속 IP)도
 * 같은 자리에서 등록한다 (이슈 #111). 셋 다 "컨트롤러 시그니처가 곧 요구사항" 이라는 같은 규약을
 * 따르므로 배선도 한곳에 모은다.
 */
@Configuration
public class WebAuthConfig implements WebMvcConfigurer {

    private final TokenProvider tokenProvider;
    private final AdminAccessService adminAccessService;

    public WebAuthConfig(TokenProvider tokenProvider, AdminAccessService adminAccessService) {
        this.tokenProvider = tokenProvider;
        this.adminAccessService = adminAccessService;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new AuthTokenInterceptor(tokenProvider))
                .addPathPatterns("/api/**");
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(new CurrentUserArgumentResolver());
        resolvers.add(new CurrentAdminArgumentResolver(adminAccessService));
        resolvers.add(new ClientIpArgumentResolver());
    }
}
