package com.growercare.common.sql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.params.provider.Arguments.argumentSet;

import java.nio.charset.StandardCharsets;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.ByteArrayResource;

/** ADR-013: named SQL files load fail-fast; only {@code {shard}} is templated, and only with a valid schema name. */
class NamedSqlRegistryTest {

    static final NamedSqlRegistry REGISTRY = NamedSqlRegistry.load("libtest");

    static ByteArrayResource file(String name, String content) {
        return new ByteArrayResource(content.getBytes(StandardCharsets.UTF_8)) {
            @Override
            public String getFilename() {
                return name;
            }
        };
    }

    @Test
    void loadsStatementsWithoutCommentsOrTrailingSemicolon() {
        assertThat(REGISTRY.ids()).containsExactlyInAnyOrder("demo.plain", "demo.sharded");
        assertThat(REGISTRY.get("demo.plain")).isEqualTo("SELECT 1");
        assertThat(REGISTRY.render("demo.sharded", "in_s07")).isEqualTo("SELECT * FROM in_s07.cases\n WHERE case_id = :caseId");
        assertThat(REGISTRY.dialect()).isEqualTo("libtest");
        assertThat(REGISTRY.toString()).contains("demo.plain");
    }

    @ParameterizedTest(name = "shard schema {0} is rejected")
    @ValueSource(strings = {"cases", "IN_S01", "in_s1", "in_s01; drop", ""})
    void renderRejectsAnythingButAShardSchema(String schema) {
        assertThatThrownBy(() -> REGISTRY.render("demo.sharded", schema)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void misuseIsAnErrorNotAWrongQuery() {
        assertThatThrownBy(() -> REGISTRY.get("demo.sharded")).isInstanceOf(IllegalStateException.class);   // needs render
        assertThatThrownBy(() -> REGISTRY.get("demo.nope")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> REGISTRY.render("demo.nope", "in_s01")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> NamedSqlRegistry.load("Bad-Dialect")).isInstanceOf(IllegalArgumentException.class);
    }

    /** file name, content. */
    static Stream<Arguments> brokenFiles() {
        return Stream.of(
                argumentSet("file name not kebab case", "Grants.sql", "-- name: a\nSELECT 1"),
                argumentSet("SQL before the first name line", "x.sql", "SELECT 1\n-- name: a\nSELECT 2"),
                argumentSet("empty statement", "x.sql", "-- name: a\n-- only a comment\n"),
                argumentSet("duplicate name", "x.sql", "-- name: a\nSELECT 1\n-- name: a\nSELECT 2"));
    }

    @ParameterizedTest
    @MethodSource("brokenFiles")
    void brokenFilesFailAtStartup(String name, String content) {
        assertThatThrownBy(() -> NamedSqlRegistry.parse(file(name, content))).isInstanceOf(IllegalStateException.class);
    }
}
