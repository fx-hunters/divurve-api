package com.divurve.domain.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.divurve.common.exception.InvalidRequestException;
import com.divurve.common.exception.NotFoundException;
import com.divurve.domain.user.entity.User;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/**
 * {@link AdminUserQueryService} — 관리자 사용자 목록·단건 조회.
 */
@DisplayName("AdminUserQueryService")
class AdminUserQueryServiceTest {

    private static final UUID USER_ID = UUID.randomUUID();

    private UserRepository userRepository;
    private AdminUserQueryService service;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        service = new AdminUserQueryService(userRepository);
    }

    private static User user() {
        return User.create("user@example.com", "사용자", "hash");
    }

    private void givenPage(List<User> users, int size) {
        when(userRepository.searchForAdmin(any(), any(), any()))
                .thenReturn(new PageImpl<>(users, PageRequest.of(0, size), users.size()));
    }

    @Test
    @DisplayName("가입 역순으로 조회한다 — 방금 들어온 계정을 먼저 본다")
    void list_SortsByCreatedAtDesc() {
        givenPage(List.of(user()), AdminUserQueryService.DEFAULT_PAGE_SIZE);

        service.list(null, null, 0, null);

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(userRepository).searchForAdmin(any(), any(), pageable.capture());
        assertThat(pageable.getValue().getSort().getOrderFor("createdAt").getDirection())
                .isEqualTo(Sort.Direction.DESC);
        assertThat(pageable.getValue().getPageSize())
                .isEqualTo(AdminUserQueryService.DEFAULT_PAGE_SIZE);
    }

    @Test
    @DisplayName("password_hash 는 어떤 응답에도 담기지 않는다")
    void list_NeverExposesPasswordHash() {
        givenPage(List.of(user()), 50);

        AdminUserQueryService.UserSummary summary = service.list(null, null, 0, 50).items().get(0);

        assertThat(summary.email()).isEqualTo("user@example.com");
        assertThat(summary.role()).isEqualTo("USER");
        assertThat(summary.demo()).isFalse();
        assertThat(summary.lastLoginAt()).isNull();
        assertThat(summary.lastLoginIp()).isNull();
        assertThat(summary.toString()).doesNotContain("hash");
    }

    @Test
    @DisplayName("페이지 메타를 그대로 옮긴다")
    void list_CarriesPageMeta() {
        givenPage(List.of(user()), 50);

        AdminUserQueryService.UserPage page = service.list(null, null, 0, 50);

        assertThat(page.page()).isZero();
        assertThat(page.size()).isEqualTo(50);
        assertThat(page.totalElements()).isEqualTo(1);
        assertThat(page.totalPages()).isEqualTo(1);
    }

    @Test
    @DisplayName("공백 검색어는 조건 없음으로 본다")
    void list_BlankKeyword_BecomesNull() {
        givenPage(List.of(), 50);

        service.list("   ", null, 0, 50);

        verify(userRepository).searchForAdmin(org.mockito.ArgumentMatchers.isNull(), any(), any());
    }

    @Test
    @DisplayName("검색어는 앞뒤 공백을 떼고 넘긴다")
    void list_TrimsKeyword() {
        givenPage(List.of(), 50);

        service.list("  user  ", true, 0, 50);

        verify(userRepository).searchForAdmin(
                org.mockito.ArgumentMatchers.eq("user"),
                org.mockito.ArgumentMatchers.eq(true),
                any());
    }

    @Test
    @DisplayName("페이지 번호가 음수이거나 크기가 범위를 벗어나면 400 이다")
    void list_InvalidPaging_Throws() {
        assertThatThrownBy(() -> service.list(null, null, -1, 50))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("페이지 번호");
        assertThatThrownBy(() -> service.list(null, null, 0, 0))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("페이지 크기");
        assertThatThrownBy(() ->
                service.list(null, null, 0, AdminUserQueryService.MAX_PAGE_SIZE + 1))
                .isInstanceOf(InvalidRequestException.class);
    }

    @Test
    @DisplayName("단건 조회는 없는 사용자면 404 다")
    void get_MissingUser_Throws() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(USER_ID)).isInstanceOf(NotFoundException.class);
    }

    @Test
    @DisplayName("단건 조회는 요약을 돌려준다")
    void get_ReturnsSummary() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user()));

        assertThat(service.get(USER_ID).name()).isEqualTo("사용자");
    }

    @Test
    @DisplayName("null 인자와 의존은 거부한다")
    void nullArguments_Throw() {
        assertThatThrownBy(() -> new AdminUserQueryService(null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> service.get(null)).isInstanceOf(NullPointerException.class);
    }
}
