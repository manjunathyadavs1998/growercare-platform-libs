package com.growercare.common.web;

import com.growercare.common.error.ErrorCode;
import com.growercare.common.error.GcException;
import com.growercare.common.error.ValidationException;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Single place that turns exceptions into RFC 9457 Problem Details (skill: java-exception-handling).
 * <ul>
 *   <li>{@link GcException}: mapped from its {@link ErrorCode}; 4xx are not logged (metrics cover them).</li>
 *   <li>Framework validation/binding errors: 400 with field errors.</li>
 *   <li>Anything else: 500 with a generic message, logged once at ERROR with the trace id.</li>
 * </ul>
 * Responses never contain stack traces, SQL or exception class names.
 */
@RestControllerAdvice
public class GcProblemHandler {

    private static final Logger log = LoggerFactory.getLogger(GcProblemHandler.class);

    @ExceptionHandler(GcException.class)
    ResponseEntity<ProblemDetail> handle(GcException e, HttpServletRequest request) {
        var problem = ProblemDetails.of(e.code(), e.getMessage(), request);
        e.properties().forEach(problem::setProperty);
        if (e instanceof ValidationException v) {
            problem.setProperty("errors", v.errors());
        }
        if (e.code().status().is5xxServerError()) {
            log.warn("{} on {} {}: {}", e.code(), request.getMethod(), request.getRequestURI(), e.getMessage());
        }
        return ProblemDetails.response(problem);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ProblemDetail> handle(MethodArgumentNotValidException e, HttpServletRequest request) {
        var errors = e.getBindingResult().getFieldErrors().stream()
                .map(f -> new ValidationException.FieldError(f.getField(), f.getDefaultMessage()))
                .toList();
        return validation(errors, request);
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    ResponseEntity<ProblemDetail> handle(HandlerMethodValidationException e, HttpServletRequest request) {
        var errors = e.getParameterValidationResults().stream()
                .flatMap(r -> r.getResolvableErrors().stream()
                        .map(err -> new ValidationException.FieldError(
                                r.getMethodParameter().getParameterName(), err.getDefaultMessage())))
                .toList();
        return validation(errors, request);
    }

    /**
     * Constraint violations raised by Spring's method-validation interceptor ({@code @Validated} beans, e.g. a
     * {@code @Min} on a {@code @RequestParam}). The field is the last path node ({@code list.limit → limit}).
     */
    @ExceptionHandler(jakarta.validation.ConstraintViolationException.class)
    ResponseEntity<ProblemDetail> handle(jakarta.validation.ConstraintViolationException e, HttpServletRequest request) {
        var errors = e.getConstraintViolations().stream()
                .map(v -> {
                    String field = null;
                    for (var node : v.getPropertyPath()) {
                        field = node.getName();
                    }
                    return new ValidationException.FieldError(field, v.getMessage());
                })
                .sorted(java.util.Comparator.comparing(ValidationException.FieldError::field, java.util.Comparator.nullsLast(String::compareTo)))
                .toList();
        return validation(errors, request);
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class,
            MissingServletRequestParameterException.class})
    ResponseEntity<ProblemDetail> handleUnreadable(Exception e, HttpServletRequest request) {
        var field = switch (e) {
            case MethodArgumentTypeMismatchException m -> m.getName();
            case MissingServletRequestParameterException m -> m.getParameterName();
            default -> "body";
        };
        return validation(List.of(new ValidationException.FieldError(field, "is missing or malformed")), request);
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    ResponseEntity<ProblemDetail> handle(MissingRequestHeaderException e, HttpServletRequest request) {
        var code = "If-Match".equalsIgnoreCase(e.getHeaderName()) ? ErrorCode.PRECONDITION_REQUIRED : ErrorCode.VALIDATION_FAILED;
        var problem = ProblemDetails.of(code, "Header " + e.getHeaderName() + " is required", request);
        return ProblemDetails.response(problem);
    }

    /** SPEC-PLAT-05 R68: protocol errors keep their real status (never the 500 catch-all). */
    @ExceptionHandler(org.springframework.web.HttpRequestMethodNotSupportedException.class)
    ResponseEntity<ProblemDetail> handle(org.springframework.web.HttpRequestMethodNotSupportedException e, HttpServletRequest request) {
        var problem = ProblemDetails.of(ErrorCode.METHOD_NOT_ALLOWED, "Method " + e.getMethod() + " is not allowed here", request);
        var builder = ResponseEntity.status(problem.getStatus()).contentType(org.springframework.http.MediaType.APPLICATION_PROBLEM_JSON);
        if (e.getSupportedHttpMethods() != null) {
            builder.allow(e.getSupportedHttpMethods().toArray(org.springframework.http.HttpMethod[]::new));
        }
        return builder.body(problem);
    }

    @ExceptionHandler(org.springframework.web.HttpMediaTypeNotSupportedException.class)
    ResponseEntity<ProblemDetail> handle(org.springframework.web.HttpMediaTypeNotSupportedException e, HttpServletRequest request) {
        return ProblemDetails.response(ProblemDetails.of(ErrorCode.UNSUPPORTED_MEDIA_TYPE,
                "Content type " + e.getContentType() + " is not supported", request));
    }

    @ExceptionHandler(org.springframework.web.HttpMediaTypeNotAcceptableException.class)
    ResponseEntity<ProblemDetail> handle(org.springframework.web.HttpMediaTypeNotAcceptableException e, HttpServletRequest request) {
        return ProblemDetails.response(ProblemDetails.of(ErrorCode.NOT_ACCEPTABLE, "No acceptable representation", request));
    }

    /** Method security (@RequiresPermission) denials. */
    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ProblemDetail> handle(AccessDeniedException e, HttpServletRequest request) {
        return ProblemDetails.response(ProblemDetails.of(ErrorCode.PERMISSION_DENIED, "Permission denied", request));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ProblemDetail> handle(NoResourceFoundException e, HttpServletRequest request) {
        return ProblemDetails.response(ProblemDetails.of(ErrorCode.NOT_FOUND, "Resource not found", request));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> handleUnexpected(Exception e, HttpServletRequest request) {
        var problem = ProblemDetails.of(ErrorCode.INTERNAL_ERROR, "An unexpected error occurred", request);
        log.error("Unhandled error on {} {} (traceId={})", request.getMethod(), request.getRequestURI(),
                problem.getProperties() == null ? null : problem.getProperties().get("traceId"), e);
        return ProblemDetails.response(problem);
    }

    private static ResponseEntity<ProblemDetail> validation(List<ValidationException.FieldError> errors, HttpServletRequest request) {
        var problem = ProblemDetails.of(ErrorCode.VALIDATION_FAILED, "Request validation failed", request);
        problem.setProperty("errors", errors);
        return ProblemDetails.response(problem);
    }

    /** Builds problems; also used by the security entry points (401/403) which run outside MVC. */
    public static final class ProblemDetails {

        private ProblemDetails() { }

        public static ProblemDetail of(ErrorCode code, String detail, HttpServletRequest request) {
            var problem = ProblemDetail.forStatusAndDetail(HttpStatus.valueOf(code.status().value()), detail);
            problem.setType(URI.create(code.typeUri()));
            problem.setTitle(code.title());
            problem.setInstance(safeUri(request.getRequestURI()));
            problem.setProperty("code", code.name());
            TraceIds.current(request).ifPresent(id -> problem.setProperty("traceId", id));
            return problem;
        }

        /** Rejected requests may carry URIs that are not valid {@link URI}s; the problem must still render. */
        private static URI safeUri(String path) {
            try {
                return path == null ? null : URI.create(path);
            } catch (IllegalArgumentException e) {
                return null;
            }
        }

        public static ResponseEntity<ProblemDetail> response(ProblemDetail problem) {
            return ResponseEntity.status(problem.getStatus())
                    .contentType(org.springframework.http.MediaType.APPLICATION_PROBLEM_JSON)
                    .body(problem);
        }

        public static Map<String, Object> asMap(ProblemDetail problem) {
            var map = new java.util.LinkedHashMap<String, Object>();
            map.put("type", problem.getType().toString());
            map.put("title", problem.getTitle());
            map.put("status", problem.getStatus());
            map.put("detail", problem.getDetail());
            map.put("instance", problem.getInstance() == null ? null : problem.getInstance().toString());
            if (problem.getProperties() != null) {
                map.putAll(problem.getProperties());
            }
            return map;
        }
    }
}
