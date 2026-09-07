package com.divurve.domain.user;

import com.divurve.common.architecture.UseCase;
import com.divurve.common.exception.ForbiddenException;
import com.divurve.domain.user.entity.User;
import java.util.Objects;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

/**
 * 관리자 인가 유스케이스 (이슈 #111). {@code /api/v1/admin/**} 에 들어갈 수 있는 사용자인지 판정한다.
 *
 * <p><b>토큰 클레임이 아니라 DB 를 읽는 이유</b> — 권한을 거두는 즉시 효력이 있어야 한다. JWT 에
 * role 을 실으면 액세스 토큰 수명(30분) 동안 강등된 계정이 계속 관리자로 남는다. 관리자 API 는
 * 호출 빈도가 낮아 조회 한 번의 비용이 문제되지 않는다.
 *
 * <p>실패를 401 이 아니라 <b>403</b> 으로 내는 이유 — 인증 자체는 성공한 상태다. 401 로 내면
 * 클라이언트가 토큰 갱신을 시도하게 되는데, 갱신해도 권한은 생기지 않는다.
 */
@UseCase
public class AdminAccessService {

    private static final String NOT_ADMIN_MESSAGE = "관리자 권한이 필요합니다.";

    private final UserRepository userRepository;

    public AdminAccessService(UserRepository userRepository) {
        this.userRepository = Objects.requireNonNull(userRepository, "userRepository");
    }

    /**
     * 관리자인지 확인하고, 아니면 거부한다.
     *
     * <p>사용자가 DB 에 없어도 같은 403 을 낸다 — 토큰은 유효하지만 계정이 지워진 경우이고,
     * "없는 계정" 과 "권한 없는 계정" 을 응답으로 구분해 주면 계정 존재 여부가 새어 나간다.
     *
     * @param userId 인증된 사용자 id
     * @throws ForbiddenException 관리자가 아니거나 계정이 존재하지 않는 경우 (403)
     */
    @Transactional(readOnly = true)
    public void requireAdmin(UUID userId) {
        Objects.requireNonNull(userId, "userId");

        boolean admin = userRepository.findById(userId)
                .map(User::isAdmin)
                .orElse(false);

        if (!admin) {
            throw new ForbiddenException(NOT_ADMIN_MESSAGE);
        }
    }
}
