package com.divurve.domain.user;

import com.divurve.common.architecture.UseCase;
import com.divurve.domain.user.entity.User;
import java.util.Objects;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.annotation.Transactional;

/**
 * 설정된 이메일의 계정을 관리자로 만든다 (이슈 #111).
 *
 * <p>두 갈래다.
 * <ul>
 *   <li><b>계정이 없다</b> → 비밀번호를 BCrypt 로 해시해 새로 만들고 ADMIN 으로 둔다.
 *       비밀번호가 비어 있으면 만들지 않는다 — 비밀번호 없는 계정은 로그인할 수 없으므로
 *       만들어 봐야 쓸 수 없고, 나중에 같은 이메일로 가입하려 할 때 409 만 유발한다.</li>
 *   <li><b>계정이 있다</b> → role 만 ADMIN 으로 올린다. <b>비밀번호는 덮어쓰지 않는다</b> —
 *       기동할 때마다 덮어쓰면 운영자가 바꾼 비밀번호가 재배포 때 조용히 되돌아간다.</li>
 * </ul>
 *
 * <p>관리자 이름은 이메일 로컬파트를 쓴다. 이름을 따로 받을 만큼의 값이 없고,
 * 관리자 화면에서 계정을 알아보는 데는 이메일이면 충분하다.
 */
@UseCase
public class AdminBootstrapService {

    private final UserRepository userRepository;
    private final String bootstrapEmail;
    private final String bootstrapPassword;
    private final BCryptPasswordEncoder passwordEncoder;

    public AdminBootstrapService(
            UserRepository userRepository,
            @Value("${app.admin.bootstrap-email:}") String bootstrapEmail,
            @Value("${app.admin.bootstrap-password:}") String bootstrapPassword) {
        this.userRepository = Objects.requireNonNull(userRepository, "userRepository");
        this.bootstrapEmail = bootstrapEmail;
        this.bootstrapPassword = bootstrapPassword;
        this.passwordEncoder = new BCryptPasswordEncoder();
    }

    /**
     * 관리자 계정을 준비한다. 여러 번 불러도 결과가 같다.
     *
     * @return 무엇을 했는지 — 로그로 남겨 "설정했는데 왜 관리자가 아닌가" 를 추적할 수 있게 한다
     */
    @Transactional
    public BootstrapOutcome bootstrap() {
        if (isBlank(bootstrapEmail)) {
            return BootstrapOutcome.SKIPPED_NO_EMAIL;
        }

        Optional<User> existing = userRepository.findByEmail(bootstrapEmail);
        if (existing.isPresent()) {
            User user = existing.get();
            if (user.isAdmin()) {
                return BootstrapOutcome.ALREADY_ADMIN;
            }
            user.promoteToAdmin();
            return BootstrapOutcome.PROMOTED;
        }

        if (isBlank(bootstrapPassword)) {
            return BootstrapOutcome.SKIPPED_NO_PASSWORD;
        }

        User admin = User.create(
                bootstrapEmail, localPart(bootstrapEmail), passwordEncoder.encode(bootstrapPassword));
        admin.promoteToAdmin();
        userRepository.save(admin);
        return BootstrapOutcome.CREATED;
    }

    private static String localPart(String email) {
        int at = email.indexOf('@');
        return at > 0 ? email.substring(0, at) : email;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /** 부트스트랩이 실제로 한 일. */
    public enum BootstrapOutcome {

        /** 이메일 설정이 없어 아무것도 하지 않았다. */
        SKIPPED_NO_EMAIL,

        /** 계정이 없는데 비밀번호 설정도 없어 만들지 않았다. */
        SKIPPED_NO_PASSWORD,

        /** 관리자 계정을 새로 만들었다. */
        CREATED,

        /** 기존 계정을 관리자로 올렸다. */
        PROMOTED,

        /** 이미 관리자였다. */
        ALREADY_ADMIN
    }
}
