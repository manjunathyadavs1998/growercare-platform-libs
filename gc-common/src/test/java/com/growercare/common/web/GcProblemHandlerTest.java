package com.growercare.common.web;

import static org.junit.jupiter.params.provider.Arguments.argumentSet;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.growercare.common.error.ArchiveNotEnabledException;
import com.growercare.common.error.BusinessRuleException;
import com.growercare.common.error.ConflictException;
import com.growercare.common.error.DependencyUnavailableException;
import com.growercare.common.error.ErrorCode;
import com.growercare.common.error.NotFoundException;
import com.growercare.common.error.PermissionDeniedException;
import com.growercare.common.error.StaleVersionException;
import com.growercare.common.error.ValidationException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.annotation.Validated;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * RFC 9457 mapping of every failure the services raise (java-exception-handling skill, SPEC-PLAT-05 R66–R69):
 * status, stable {@code code}, optional {@code rule}/{@code errors}, a trace id, and never a stack trace.
 */
class GcProblemHandlerTest {

    record Body(@NotBlank String name) { }

    @RestController
    @Validated
    static class Failing {
        static final java.util.Map<String, Supplier<RuntimeException>> THROWN = java.util.Map.of(
                "not-found", () -> new NotFoundException("Case"),
                "denied", () -> new PermissionDeniedException("case:assign"),
                "stale", () -> new StaleVersionException("Case", 3),
                "duplicate", () -> new ConflictException(ErrorCode.DUPLICATE, "already exists"),
                "not-ready", () -> ConflictException.notReady("attachment.not-available", "still scanning"),
                "rule", () -> new BusinessRuleException("case.transition.not-allowed", "nope"),
                "archive", ArchiveNotEnabledException::new,
                "dependency", () -> new DependencyUnavailableException("identity-access-service", null),
                "validation", () -> ValidationException.of("queue", "unknown queue"),
                "access", () -> new AccessDeniedException("method security"));

        @GetMapping("/fail/{kind}")
        String fail(@PathVariable String kind) {
            if ("boom".equals(kind)) {
                throw new IllegalStateException("secret internals");
            }
            throw THROWN.get(kind).get();
        }

        @PostMapping(path = "/body", consumes = MediaType.APPLICATION_JSON_VALUE)
        String body(@Valid @RequestBody Body body) {
            return body.name();
        }

        @GetMapping("/param")
        String param(@RequestParam int limit) {
            return Integer.toString(limit);
        }

        /** What method validation raises for an invalid parameter (validated service beans, @Validated controllers). */
        @GetMapping("/constraint")
        String constraint() {
            var violations = validator().getValidator().validate(new Body(" "));
            throw new jakarta.validation.ConstraintViolationException(violations);
        }

        @GetMapping("/header")
        String header(@RequestHeader("If-Match") String ifMatch, @RequestHeader("X-Other") String other) {
            return ifMatch + other;
        }

        @GetMapping(path = "/json", produces = MediaType.APPLICATION_JSON_VALUE)
        String json() {
            return "{}";
        }
    }

    static final MockMvc MVC = MockMvcBuilders.standaloneSetup(new Failing())
            .setControllerAdvice(new GcProblemHandler())
            .setValidator(validator())
            .build();

    static LocalValidatorFactoryBean validator() {
        var validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        return validator;
    }

    /** request, status, code, rule (null = absent). */
    static Stream<Arguments> failures() {
        return Stream.of(
                argumentSet("404 not found", get("/fail/not-found"), 404, "NOT_FOUND", null),
                argumentSet("403 permission", get("/fail/denied"), 403, "PERMISSION_DENIED", null),
                argumentSet("403 method security", get("/fail/access"), 403, "PERMISSION_DENIED", null),
                argumentSet("412 stale version", get("/fail/stale"), 412, "STALE_VERSION", null),
                argumentSet("409 duplicate", get("/fail/duplicate"), 409, "DUPLICATE", null),
                argumentSet("409 not ready + rule", get("/fail/not-ready"), 409, "NOT_READY", "attachment.not-available"),
                argumentSet("422 business rule", get("/fail/rule"), 422, "BUSINESS_RULE_VIOLATION", "case.transition.not-allowed"),
                argumentSet("501 archive", get("/fail/archive"), 501, "ARCHIVE_NOT_ENABLED", null),
                argumentSet("503 dependency", get("/fail/dependency"), 503, "DEPENDENCY_UNAVAILABLE", null),
                argumentSet("400 domain validation", get("/fail/validation"), 400, "VALIDATION_FAILED", null),
                argumentSet("500 unexpected (no internals)", get("/fail/boom"), 500, "INTERNAL_ERROR", null),
                argumentSet("PLAT-E83 400 malformed JSON", post("/body").contentType(MediaType.APPLICATION_JSON).content("{\"name\":"), 400, "VALIDATION_FAILED", null),
                argumentSet("400 bean validation of the body", post("/body").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\" \"}"), 400, "VALIDATION_FAILED", null),
                argumentSet("400 constraint violation", get("/constraint"), 400, "VALIDATION_FAILED", null),
                argumentSet("400 parameter type", get("/param").param("limit", "x"), 400, "VALIDATION_FAILED", null),
                argumentSet("400 missing parameter", get("/param"), 400, "VALIDATION_FAILED", null),
                argumentSet("428 missing If-Match", get("/header").header("X-Other", "1"), 428, "PRECONDITION_REQUIRED", null),
                argumentSet("400 other missing header", get("/header").header("If-Match", "\"1\""), 400, "VALIDATION_FAILED", null),
                argumentSet("PLAT-E80 405 method", post("/fail/x"), 405, "METHOD_NOT_ALLOWED", null),
                argumentSet("PLAT-E81 415 media type", post("/body").contentType(MediaType.TEXT_PLAIN).content("x"), 415, "UNSUPPORTED_MEDIA_TYPE", null),
                argumentSet("PLAT-E82 406 not acceptable", get("/json").accept(MediaType.IMAGE_PNG), 406, "NOT_ACCEPTABLE", null));
    }

    @ParameterizedTest
    @MethodSource("failures")
    void everyFailureIsAProblem(MockHttpServletRequestBuilder request, int status, String code, String rule) throws Exception {
        var result = MVC.perform(request)
                .andExpect(status().is(status))
                .andExpect(header().string("Content-Type", "application/problem+json"))
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.traceId").exists())
                .andExpect(jsonPath("$.trace").doesNotExist())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("secret internals"))));
        if (rule != null) {
            result.andExpect(jsonPath("$.rule").value(rule));
        }
    }
}
