package com.growercare.security.web;

import com.growercare.common.error.ErrorCode;
import com.growercare.common.web.GcProblemHandler.ProblemDetails;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.firewall.RequestRejectedException;
import org.springframework.security.web.firewall.RequestRejectedHandler;
import tools.jackson.databind.json.JsonMapper;

/**
 * 401 / 403 / 503 from the security filter chain, written as RFC 9457 Problem Details (the MVC
 * {@code GcProblemHandler} does not see filter-level failures). Details never echo token contents.
 * Also Spring Security's {@link RequestRejectedHandler}: firewall rejections become 400 problem+json on {@code path}
 * (SPEC-PLAT-05 D1); the rejected URL is not echoed.
 */
public final class ProblemSecurityHandlers implements AuthenticationEntryPoint, AccessDeniedHandler, RequestRejectedHandler {

    private static final Logger log = LoggerFactory.getLogger(ProblemSecurityHandlers.class);

    private final JsonMapper json;

    public ProblemSecurityHandlers(JsonMapper json) {
        this.json = json;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException e) throws IOException {
        if (e instanceof PrincipalUnavailableException) {
            log.warn("Grants unavailable for {} {}: {}", request.getMethod(), request.getRequestURI(),
                    e.getCause() == null ? e.getMessage() : e.getCause().getMessage());
            write(response, request, ErrorCode.DEPENDENCY_UNAVAILABLE, "Authorization data is temporarily unavailable");
            return;
        }
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        write(response, request, ErrorCode.UNAUTHENTICATED, "A valid access token is required");
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException e) throws IOException {
        write(response, request, ErrorCode.PERMISSION_DENIED, "Permission denied");
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, RequestRejectedException e) throws IOException {
        log.debug("request rejected by the firewall: {}", e.getMessage());
        var problem = ProblemDetails.of(ErrorCode.VALIDATION_FAILED, "The request URL is not allowed", request);
        problem.setInstance(null);
        problem.setProperty("errors", java.util.List.of(java.util.Map.of("field", "path",
                "message", "must not contain empty segments, ';', encoded slashes or traversal")));
        write(response, problem);
    }

    private void write(HttpServletResponse response, HttpServletRequest request, ErrorCode code, String detail) throws IOException {
        write(response, ProblemDetails.of(code, detail, request));
    }

    private void write(HttpServletResponse response, org.springframework.http.ProblemDetail problem) throws IOException {
        var code = problem.getStatus();
        response.setStatus(code);
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        json.writeValue(response.getOutputStream(), ProblemDetails.asMap(problem));
    }
}
