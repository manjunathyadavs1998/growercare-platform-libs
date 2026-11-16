package com.growercare.security.web;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.params.provider.Arguments.argumentSet;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.growercare.common.error.DependencyUnavailableException;
import com.growercare.security.EffectiveGrant;
import com.growercare.security.GcPrincipal;
import com.growercare.security.PiiClearance;
import com.growercare.security.PrincipalResolver;
import com.growercare.security.RequiresPermission;
import com.growercare.security.ScopeKind;
import com.growercare.security.UserType;
import com.growercare.testsupport.PostgresTestDatabase;
import com.growercare.testsupport.TestJwts;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The shared security chain every service gets (SPEC-CASE-01 R7–R10, SPEC-IAM-01, SPEC-PLAT-05): JWT → principal,
 * fail-closed on IAM outage, service tokens only on /internal, method security, problem+json, firewall, CORS.
 */
@SpringBootTest(classes = SecurityChainIT.App.class, properties = {
        "gc.security.internal-clients=case-service",
        "gc.security.cors-allowed-origins=http://localhost:5173"})
@AutoConfigureMockMvc
class SecurityChainIT {

    static final UUID CARE = TestJwts.CARE_IN;
    static final UUID IAM_DOWN = UUID.fromString("00000000-0000-4000-8000-0000000000d0");

    @DynamicPropertySource
    static void db(DynamicPropertyRegistry r) {                                      // gc-common brings JDBC
        var db = PostgresTestDatabase.forService("gc_security_lib");
        r.add("spring.datasource.url", db::jdbcUrl);
        r.add("spring.datasource.username", db::appUser);
        r.add("spring.datasource.password", db::appPassword);
    }

    @SpringBootApplication(scanBasePackages = "com.growercare")
    static class App {
        @Bean
        JwtDecoder testJwtDecoder() {
            return TestJwts.decoder(List.of("gc-api", "gc-internal"));
        }

        @Bean
        PrincipalResolver stubIam() {
            var care = new GcPrincipal(101, CARE, UserType.STAFF, "IN", null, List.of(new EffectiveGrant(1L, "CARE_AGENT", 30,
                    ScopeKind.COUNTRY, PiiClearance.MASKED, Set.of("case:read"), Set.of("IN"), Set.of(), Set.of(), null)));
            var known = Map.of(CARE, care);
            return subject -> {
                if (IAM_DOWN.equals(subject)) {
                    throw new DependencyUnavailableException("identity-access-service", null);
                }
                return Optional.ofNullable(known.get(subject));
            };
        }

        @RestController
        static class Api {
            @GetMapping("/hello")
            @RequiresPermission("case:read")
            String hello() {
                return Long.toString(CurrentPrincipal.require().userId());
            }

            @GetMapping("/admin")
            @RequiresPermission("grant:create")
            String admin() {
                return "never";
            }

            @GetMapping("/internal/ping")
            String ping() {
                return "pong";
            }
        }
    }

    @Autowired
    MockMvc mvc;

    /** request, status, problem code (null = success body asserted separately). */
    static Stream<Arguments> requests() {
        Supplier<String> care = () -> TestJwts.user(CARE).bearer();
        return Stream.of(
                argumentSet("CASE-R7 no token", get("/hello"), 401, "UNAUTHENTICATED"),
                argumentSet("CASE-R8 valid token, no account", get("/hello").header("Authorization", TestJwts.user(UUID.randomUUID()).bearer()), 401, "UNAUTHENTICATED"),
                argumentSet("CASE-R9 IAM unreachable fails closed", get("/hello").header("Authorization", TestJwts.user(IAM_DOWN).bearer()), 503, "DEPENDENCY_UNAVAILABLE"),
                argumentSet("wrong audience", get("/hello").header("Authorization", TestJwts.user(CARE).audience("other").bearer()), 401, "UNAUTHENTICATED"),
                argumentSet("missing permission → 403", get("/admin").header("Authorization", care.get()), 403, "PERMISSION_DENIED"),
                argumentSet("CASE-R10 service token not allow-listed", get("/internal/ping").header("Authorization", TestJwts.service("integration-service").bearer()), 401, "UNAUTHENTICATED"),
                argumentSet("IAM-R17 user token on /internal", get("/internal/ping").header("Authorization", care.get()), 403, "PERMISSION_DENIED"),
                argumentSet("IAM-R17 service token on user API", get("/hello").header("Authorization", TestJwts.service("case-service").bearer()), 403, "PERMISSION_DENIED"),
                argumentSet("PLAT-E79 firewall: semicolon", get("/hello;jsessionid=x").header("Authorization", care.get()), 400, "VALIDATION_FAILED"),
                argumentSet("PLAT-E78 firewall: encoded traversal", get(URI.create("/x/%2e%2e/hello")).header("Authorization", care.get()), 400, "VALIDATION_FAILED"));
    }

    @ParameterizedTest
    @MethodSource("requests")
    void rejectedRequestsAreProblems(MockHttpServletRequestBuilder request, int status, String code) throws Exception {
        mvc.perform(request)
                .andExpect(status().is(status))
                .andExpect(header().string("Content-Type", "application/problem+json"))
                .andExpect(jsonPath("$.code").value(code));
    }

    @Test
    void knownUserReachesTheApiAndServicesReachInternal() throws Exception {
        mvc.perform(get("/hello").header("Authorization", TestJwts.user(CARE).bearer()))
                .andExpect(status().isOk()).andExpect(content().string("101"));
        mvc.perform(get("/internal/ping").header("Authorization", TestJwts.service("case-service").bearer()))
                .andExpect(status().isOk()).andExpect(content().string("pong"));
    }

    @Test
    void corsOnlyForConfiguredOriginsAndSecurityHeaders() throws Exception {
        mvc.perform(options("/hello").header("Origin", "http://localhost:5173").header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isOk()).andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"));
        mvc.perform(options("/hello").header("Origin", "https://evil.example").header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/hello"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Content-Security-Policy", containsString("default-src 'none'")));
    }

    @Test
    void wildcardCorsOriginIsRefused() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new GcSecurityProperties(null, null, null, List.of("*"), null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
