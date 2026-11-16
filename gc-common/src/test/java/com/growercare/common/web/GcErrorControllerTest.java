package com.growercare.common.web;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.RequestDispatcher;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mock.web.MockHttpServletRequest;

/** SPEC-PLAT-05 R72 / E87: a problem rendered at /error never contradicts its own status. */
class GcErrorControllerTest {

    @ParameterizedTest(name = "PLAT-E87 status {0} → {1}")
    @CsvSource(textBlock = """
            # status, code,           title
            404,      NOT_FOUND,      Resource not found
            413,      HTTP_413,       Content Too Large
            409,      HTTP_409,       Conflict
            502,      HTTP_502,       Bad Gateway
            500,      INTERNAL_ERROR, Unexpected error
            503,      DEPENDENCY_UNAVAILABLE, A dependency is temporarily unavailable
            """)
    void codeNeverContradictsTheStatus(int status, String code, String title) {
        var request = new MockHttpServletRequest("GET", "/error");
        request.setAttribute(RequestDispatcher.ERROR_STATUS_CODE, status);

        var response = new GcErrorController().error(request);

        assertThat(response.getStatusCode().value()).isEqualTo(status);
        var problem = response.getBody();
        assertThat(problem).isNotNull();
        assertThat(problem.getStatus()).isEqualTo(status);
        assertThat(problem.getProperties()).containsEntry("code", code);
        assertThat(problem.getTitle()).isEqualTo(title);
        assertThat(problem.getProperties()).containsKey("traceId");
    }
}
