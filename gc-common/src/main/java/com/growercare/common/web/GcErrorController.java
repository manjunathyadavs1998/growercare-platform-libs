package com.growercare.common.web;

import com.growercare.common.error.ErrorCode;
import com.growercare.common.web.GcProblemHandler.ProblemDetails;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.Map;
import org.springframework.boot.webmvc.error.ErrorController;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * SPEC-PLAT-05 D2 / R72: replaces Spring Boot's {@code BasicErrorController}. Whatever still reaches {@code /error}
 * (errors raised in servlet filters, container errors) is rendered as RFC 9457 problem+json with its original
 * status, and with a {@code code} that never contradicts that status. No exception details are included.
 */
@RestController
@io.swagger.v3.oas.annotations.Hidden   // infrastructure, not API: stays out of the OpenAPI contracts (SPEC-PLAT-01 R1)
public class GcErrorController implements ErrorController {

    /** Statuses with exactly one meaning in this API (R72); everything else is reported as {@code HTTP_<status>}. */
    static final Map<Integer, ErrorCode> UNAMBIGUOUS = Map.of(
            400, ErrorCode.VALIDATION_FAILED,
            401, ErrorCode.UNAUTHENTICATED,
            403, ErrorCode.PERMISSION_DENIED,
            404, ErrorCode.NOT_FOUND,
            405, ErrorCode.METHOD_NOT_ALLOWED,
            406, ErrorCode.NOT_ACCEPTABLE,
            415, ErrorCode.UNSUPPORTED_MEDIA_TYPE,
            500, ErrorCode.INTERNAL_ERROR,
            503, ErrorCode.DEPENDENCY_UNAVAILABLE);

    @RequestMapping("${server.error.path:/error}")
    ResponseEntity<ProblemDetail> error(HttpServletRequest request) {
        int status = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE) instanceof Integer code ? code : 500;
        var known = UNAMBIGUOUS.get(status);
        if (known != null) {
            return ProblemDetails.response(ProblemDetails.of(known, "The request could not be processed", request));
        }
        var resolved = HttpStatus.resolve(status);
        var problem = ProblemDetail.forStatusAndDetail(resolved == null ? HttpStatus.INTERNAL_SERVER_ERROR : resolved,
                "The request could not be processed");
        problem.setStatus(status);
        problem.setTitle(resolved == null ? "HTTP " + status : resolved.getReasonPhrase());
        problem.setType(URI.create(ErrorCode.TYPE_BASE + "http-" + status));
        problem.setProperty("code", "HTTP_" + status);
        TraceIds.current(request).ifPresent(id -> problem.setProperty("traceId", id));
        return ProblemDetails.response(problem);
    }
}
