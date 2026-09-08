package com.divurve.api.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 회귀 방지 — springdoc-openapi 는 {@code components.schemas} 의 키로 자바 클래스의
 * <b>단순명(simple name)</b> 을 쓴다. 구조가 다른 중첩 record 가 같은 단순명을 공유하면 나중
 * 것이 앞의 것을 덮어써, Swagger 로 뽑은 타입이 실제 응답과 달라진다(이슈 #88).
 *
 * <p>{@code com.divurve.api.dto} 아래 모든 record 를 훑어 단순명이 겹치는 그룹을 찾고,
 * 그 그룹의 모든 구성원이 {@link Schema#name()} 으로 고유한 스키마명을 스스로 고정했는지
 * 검증한다. {@code @Schema(name=...)} 이 없거나, 있어도 최종적으로 겹치는 이름을 고르면
 * 실패한다.
 */
class DtoSchemaNameUniquenessTest {

    @Test
    void dto_단순명이_겹치는_record는_Schema_name으로_고유한_스키마명을_고정한다() {
        JavaClasses classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.divurve.api.dto");

        List<Class<?>> records = collectRecords(classes);
        Map<String, List<Class<?>>> bySimpleName = groupBySimpleName(records);

        List<String> missingSchemaName = findMissingSchemaName(bySimpleName);
        assertThat(missingSchemaName)
                .as("""
                        아래 record 들은 단순명이 겹치는데 @Schema(name=...) 이 없거나 비어있다. \
                        springdoc 이 components.schemas 를 단순명으로 키를 잡으므로 나중 것이 \
                        앞의 것을 덮어쓴다. 각 클래스에 고유한 @Schema(name=...) 을 붙여라(이슈 #88).""")
                .isEmpty();

        Map<String, List<Class<?>>> byResolvedName = groupByResolvedSchemaName(records);
        List<String> collidingResolvedNames = findCollisions(byResolvedName);
        assertThat(collidingResolvedNames)
                .as("""
                        아래 스키마명은 서로 다른 record 가 같은 @Schema(name=...) 값을 골라 \
                        여전히 components.schemas 에서 충돌한다(이슈 #88).""")
                .isEmpty();
    }

    private List<Class<?>> collectRecords(JavaClasses classes) {
        List<Class<?>> records = new ArrayList<>();
        for (JavaClass javaClass : classes) {
            Class<?> reflected = javaClass.reflect();
            if (reflected.isRecord()) {
                records.add(reflected);
            }
        }
        return records;
    }

    private Map<String, List<Class<?>>> groupBySimpleName(List<Class<?>> records) {
        Map<String, List<Class<?>>> bySimpleName = new LinkedHashMap<>();
        for (Class<?> record : records) {
            bySimpleName.computeIfAbsent(record.getSimpleName(), key -> new ArrayList<>()).add(record);
        }
        return bySimpleName;
    }

    private Map<String, List<Class<?>>> groupByResolvedSchemaName(List<Class<?>> records) {
        Map<String, List<Class<?>>> byResolvedName = new LinkedHashMap<>();
        for (Class<?> record : records) {
            byResolvedName.computeIfAbsent(resolvedSchemaName(record), key -> new ArrayList<>()).add(record);
        }
        return byResolvedName;
    }

    private String resolvedSchemaName(Class<?> record) {
        Schema schema = record.getAnnotation(Schema.class);
        if (schema != null && !schema.name().isBlank()) {
            return schema.name();
        }
        return record.getSimpleName();
    }

    private List<String> findMissingSchemaName(Map<String, List<Class<?>>> bySimpleName) {
        List<String> violations = new ArrayList<>();
        for (Map.Entry<String, List<Class<?>>> entry : bySimpleName.entrySet()) {
            List<Class<?>> sharing = entry.getValue();
            if (sharing.size() < 2) {
                continue;
            }
            for (Class<?> record : sharing) {
                if (!hasExplicitSchemaName(record)) {
                    violations.add(entry.getKey() + " → " + record.getName());
                }
            }
        }
        return violations;
    }

    private boolean hasExplicitSchemaName(Class<?> record) {
        Schema schema = record.getAnnotation(Schema.class);
        return schema != null && !schema.name().isBlank();
    }

    private List<String> findCollisions(Map<String, List<Class<?>>> byResolvedName) {
        List<String> violations = new ArrayList<>();
        for (Map.Entry<String, List<Class<?>>> entry : byResolvedName.entrySet()) {
            if (entry.getValue().size() < 2) {
                continue;
            }
            violations.add(entry.getKey() + " → " + entry.getValue().stream()
                    .map(Class::getName)
                    .toList());
        }
        return violations;
    }
}
