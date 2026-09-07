package com.divurve.domain.user;

import com.divurve.common.architecture.UseCase;
import com.divurve.common.exception.InvalidRequestException;
import com.divurve.common.exception.NotFoundException;
import com.divurve.domain.user.entity.User;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.transaction.annotation.Transactional;

/**
 * 관리자용 사용자 목록·상세 조회 (이슈 #111).
 *
 * <p>기본 정렬은 <b>가입 역순</b>이다 — 운영 점검에서 가장 먼저 보고 싶은 것은 방금 들어온 계정이다.
 *
 * <p><b>{@code password_hash} 를 절대 밖으로 내보내지 않는다.</b> 이 조회는 전 사용자의 전 컬럼을
 * 다루므로, 응답 뷰를 엔티티에서 직접 만들지 않고 여기서 필요한 값만 골라 담는다.
 */
@UseCase
public class AdminUserQueryService {

    /** 한 페이지 최대 크기. 무제한 조회로 전 사용자 데이터를 한 번에 끌어가는 것을 막는다. */
    static final int MAX_PAGE_SIZE = 200;

    static final int DEFAULT_PAGE_SIZE = 50;

    private final UserRepository userRepository;

    public AdminUserQueryService(UserRepository userRepository) {
        this.userRepository = Objects.requireNonNull(userRepository, "userRepository");
    }

    /**
     * 사용자 목록을 페이지 단위로 조회한다.
     *
     * @param keyword 이메일·이름 부분일치. 비어 있으면 전체
     * @param demo    데모 계정만/일반 계정만. {@code null} 이면 전체
     * @param page    0부터 시작하는 페이지 번호
     * @param size    페이지 크기. {@code null} 이면 기본값
     * @throws InvalidRequestException 페이지 번호가 음수이거나 크기가 범위를 벗어난 경우 (400)
     */
    @Transactional(readOnly = true)
    public UserPage list(String keyword, Boolean demo, int page, Integer size) {
        if (page < 0) {
            throw new InvalidRequestException("페이지 번호는 0 이상이어야 합니다.", "page");
        }
        int pageSize = size == null ? DEFAULT_PAGE_SIZE : size;
        if (pageSize < 1 || pageSize > MAX_PAGE_SIZE) {
            throw new InvalidRequestException(
                    "페이지 크기는 1~" + MAX_PAGE_SIZE + " 사이여야 합니다.", "size");
        }

        Page<User> found = userRepository.searchForAdmin(
                blankToNull(keyword),
                demo,
                PageRequest.of(page, pageSize, Sort.by(Sort.Direction.DESC, "createdAt")));

        return new UserPage(
                found.getContent().stream().map(AdminUserQueryService::toSummary).toList(),
                found.getNumber(),
                found.getSize(),
                found.getTotalElements(),
                found.getTotalPages());
    }

    /**
     * 사용자 한 명의 요약을 조회한다.
     *
     * @throws NotFoundException 없는 사용자인 경우 (404)
     */
    @Transactional(readOnly = true)
    public UserSummary get(UUID userId) {
        Objects.requireNonNull(userId, "userId");
        return userRepository.findById(userId)
                .map(AdminUserQueryService::toSummary)
                .orElseThrow(() -> new NotFoundException("존재하지 않는 사용자입니다: " + userId));
    }

    private static UserSummary toSummary(User user) {
        return new UserSummary(
                user.getId(),
                user.getEmail(),
                user.getName(),
                user.getRole().name(),
                user.isDemo(),
                user.isSampleDataSeeded(),
                user.getCreatedAt(),
                user.getOnboardedAt(),
                user.getLastLoginAt(),
                user.getLastLoginIp());
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /**
     * 사용자 목록 한 페이지.
     *
     * @param items         이 페이지의 사용자
     * @param page          0부터 시작하는 페이지 번호
     * @param size          페이지 크기
     * @param totalElements 조건에 맞는 전체 건수
     * @param totalPages    전체 페이지 수
     */
    public record UserPage(
            List<UserSummary> items, int page, int size, long totalElements, int totalPages) {
    }

    /**
     * 관리자 화면이 보는 사용자 한 명.
     *
     * <p>{@code email} 이 곧 로그인 식별자다 — 이 스키마에 별도 {@code username} 은 없다.
     * {@code password_hash} 는 <b>담지 않는다</b>.
     *
     * @param id               사용자 id
     * @param email            로그인 식별자
     * @param name             표시 이름
     * @param role             {@code USER} 또는 {@code ADMIN}
     * @param demo             둘러보기 계정인가 (계정의 성격)
     * @param sampleDataSeeded 자산이 시드된 샘플인가 (자산의 출처)
     * @param createdAt        가입 시각
     * @param onboardedAt      초기 설정 완료 시각. 미완료면 {@code null}
     * @param lastLoginAt      마지막 접속 시각. 접속한 적이 없으면 {@code null}
     * @param lastLoginIp      마지막 접속 IP. 알 수 없으면 {@code null}
     */
    public record UserSummary(
            UUID id,
            String email,
            String name,
            String role,
            boolean demo,
            boolean sampleDataSeeded,
            java.time.Instant createdAt,
            java.time.Instant onboardedAt,
            java.time.Instant lastLoginAt,
            String lastLoginIp) {
    }
}
