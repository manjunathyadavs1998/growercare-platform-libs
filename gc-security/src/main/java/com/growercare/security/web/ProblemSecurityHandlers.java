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
import tools.jackson.databind.json.JsonMapper;

/**
 * 401 / 403 / 503 from the security filter chain, written as RFC 9457 Problem Details (the MVC
 * {@code GcProblemHandler} does not see filter-level failures). Details never echo token contents.
 */
public final class ProblemSecurityHandlers implements AuthenticationEntryPoint, AccessDeniedHandler {

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

    private void write(HttpServletResponse response, HttpServletRequest request, ErrorCode code, String detail) throws IOException {
        var problem = ProblemDetails.of(code, detail, request);
        response.setStatus(code.status().value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        json.writeValue(response.getOutputStream(), ProblemDetails.asMap(problem));
    }
}
