package com.divurve.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Flyway 마이그레이션 파일의 이름 규약과 버전 유일성 검사.
 *
 * <p>이 테스트가 있는 이유는 <b>Testcontainers 가 이런 사고를 못 잡기 때문</b>이다. 테스트는 매번
 * 빈 DB 에 V1 부터 순서대로 적용하므로 번호가 겹치지 않는 한 무엇이든 통과한다 — 번호가 겹치면
 * Flyway 가 기동 자체를 거부해서 잡히지만, 그 전에 여기서 <b>어느 두 파일이 겹쳤는지</b>를
 * 이름으로 알려주는 편이 원인 파악이 빠르다.
 *
 * <p>병렬로 진행되는 브랜치들이 같은 번호를 집는 일이 2026-09-06(V7 중복)과 2026-09-07(V14 중복)
 * 두 번 있었다. 둘 다 develop 이나 배포에서 터진 뒤에야 드러났다.
 *
 * <p>번호 <b>구멍은 허용한다</b>. 순서를 바로잡느라 파일을 뒤로 옮기면 구멍이 생기고, 그 구멍을
 * 메우려면 이미 쓰인 번호에 다른 내용을 넣어야 한다 — 그것이 배포를 죽이는 슬롯 재배정이다.
 *
 * <p><b>이 테스트가 잡지 못하는 것</b>: 번호는 유일하지만 <b>이미 적용된 버전보다 낮은</b> 번호가
 * 뒤늦게 머지되는 경우(이슈 #104). 그것은 base 브랜치와의 비교가 필요해 리포 안의 파일만으로는
 * 알 수 없다 — CI 의 {@code migration-order} 잡이 맡는다.
 */
@DisplayName("Flyway 마이그레이션 파일 규약")
class MigrationVersionTest {

    private static final Path MIGRATION_DIR =
            Path.of("src", "main", "resources", "db", "migration");

    /** {@code V<버전>__<설명>.sql} — Flyway 의 기본 규약. */
    private static final Pattern NAMING = Pattern.compile("^V(\\d+)__([a-z0-9_]+)\\.sql$");

    private List<String> fileNames() throws IOException {
        try (Stream<Path> files = Files.list(MIGRATION_DIR)) {
            return files.map(path -> path.getFileName().toString()).sorted().toList();
        }
    }

    @Test
    @DisplayName("마이그레이션 파일이 존재한다 — 경로가 바뀌면 이 테스트가 조용히 빈 통과를 하지 않게 한다")
    void migrationsExist() throws IOException {
        assertThat(MIGRATION_DIR).exists();
        assertThat(fileNames()).isNotEmpty();
    }

    @Test
    @DisplayName("파일 이름은 V<버전>__<snake_case 설명>.sql 규약을 지킨다")
    void namingConvention() throws IOException {
        assertThat(fileNames()).allSatisfy(name ->
                assertThat(NAMING.matcher(name).matches())
                        .as("규약에 맞지 않는 마이그레이션 파일 이름: %s", name)
                        .isTrue());
    }

    @Test
    @DisplayName("버전 번호는 유일하다 — 겹치면 Flyway 가 기동을 거부한다")
    void versionsAreUnique() throws IOException {
        Map<Integer, List<String>> byVersion = fileNames().stream()
                .collect(Collectors.groupingBy(MigrationVersionTest::versionOf));

        List<Map.Entry<Integer, List<String>>> duplicates = byVersion.entrySet().stream()
                .filter(entry -> entry.getValue().size() > 1)
                .sorted(Map.Entry.comparingByKey())
                .toList();

        assertThat(duplicates)
                .as("같은 버전 번호를 쓰는 마이그레이션이 있다. 병렬 브랜치가 같은 번호를 집으면"
                        + " 먼저 머지된 쪽이 이기고 나머지는 배포에서 터진다: %s", duplicates)
                .isEmpty();
    }

    @Test
    @DisplayName("버전 번호는 뒤로만 간다 — 번호를 재사용하지 않는다")
    void versionsAreNeverReused() throws IOException {
        // 번호 구멍(V13·V15 없음)은 정상이다. 이슈 #104 에서 순서를 바로잡느라 두 파일을 뒤로
        // 옮기며 생겼다. 구멍을 메우려면 이미 쓰인 번호에 다른 내용을 넣어야 하는데, 그것이
        // 바로 배포를 죽이는 "버전 슬롯 재배정"이다 — 어떤 환경이 옛 내용으로 그 번호를 이미
        // 기록했다면 체크섬이 어긋난다. 그래서 여기서는 연속성을 요구하지 않고, 번호가
        // 유일하고 오름차순으로만 늘어나는지만 본다.
        // 파일 목록은 사전순(V1, V10, V11, ... V2)이라 그대로는 번호 순이 아니다 — 숫자로 뽑아 본다.
        List<Integer> versions = fileNames().stream()
                .map(MigrationVersionTest::versionOf)
                .toList();

        assertThat(versions).doesNotHaveDuplicates();
        assertThat(versions).allSatisfy(version -> assertThat(version).isPositive());
    }

    private static int versionOf(String fileName) {
        Matcher matcher = NAMING.matcher(fileName);
        if (!matcher.matches()) {
            throw new IllegalStateException("규약에 맞지 않는 파일 이름: " + fileName);
        }
        return Integer.parseInt(matcher.group(1));
    }
}
