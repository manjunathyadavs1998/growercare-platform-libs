package com.growercare.common.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.params.provider.Arguments.argumentSet;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

/** SPEC-PLAT-06 R76: refuse to start, naming every missing environment variable (never a value). */
class RequiredEnvironmentTest {

    /** application.yml as a service writes it: values only from the environment. */
    static final Map<String, Object> YAML = Map.of(
            RequiredEnvironment.PROPERTY, "spring.datasource.url, gc.iam.base-url, spring.datasource.password",
            "spring.datasource.url", "${DB_URL}",
            "gc.iam.base-url", "${IAM_BASE_URL}",
            "spring.datasource.password", "${DB_APP_PASSWORD}");

    static StandardEnvironment environment(Map<String, Object> env) {
        var environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("test-env", env));      // stands in for OS env
        environment.getPropertySources().addLast(new MapPropertySource("application.yml", YAML));
        return environment;
    }

    /** environment variables present, expected missing entries. */
    static Stream<Arguments> rows() {
        return Stream.of(
                argumentSet("PLAT-E88 two variables unset", Map.of("DB_APP_PASSWORD", "s3cr3t"),
                        List.of("DB_URL (spring.datasource.url)", "IAM_BASE_URL (gc.iam.base-url)")),
                argumentSet("PLAT-E89 everything set", Map.of("DB_URL", "jdbc:postgresql://db/gc", "IAM_BASE_URL", "http://iam",
                        "DB_APP_PASSWORD", "s3cr3t"), List.of()),
                argumentSet("blank counts as missing", Map.of("DB_URL", " ", "IAM_BASE_URL", "http://iam", "DB_APP_PASSWORD", "x"),
                        List.of("DB_URL (spring.datasource.url)")));
    }

    @ParameterizedTest
    @MethodSource("rows")
    void reportsEveryMissingVariable(Map<String, Object> env, List<String> expected) {
        assertThat(RequiredEnvironment.missing(environment(env))).containsExactlyElementsOf(expected);
    }

    @Test
    void platE88StartupFailsWithOneMessageAndNoValues() {
        var check = new RequiredEnvironment(environment(Map.of("DB_APP_PASSWORD", "s3cr3t")));
        assertThatThrownBy(() -> check.postProcessBeanFactory(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DB_URL")
                .hasMessageContaining("IAM_BASE_URL")
                .hasMessageNotContaining("s3cr3t");
    }
}
