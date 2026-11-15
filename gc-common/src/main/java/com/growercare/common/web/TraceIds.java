package com.growercare.common.web;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Trace id for error responses and logs. Uses the W3C {@code traceparent} header when present (set by the gateway /
 * OpenTelemetry), otherwise a request-scoped random id, so every problem response can be correlated with logs.
 */
public final class TraceIds {

    public static final String ATTRIBUTE = TraceIds.class.getName() + ".traceId";
    private static final Pattern TRACEPARENT = Pattern.compile("^[0-9a-f]{2}-([0-9a-f]{32})-[0-9a-f]{16}-[0-9a-f]{2}$");

    private TraceIds() { }

    /** Trace id of the current HTTP request, or null outside a request (jobs, consumers). */
    public static String currentOrNull() {
        if (org.springframework.web.context.request.RequestContextHolder.getRequestAttributes()
                instanceof org.springframework.web.context.request.ServletRequestAttributes attrs) {
            return current(attrs.getRequest()).orElse(null);
        }
        return null;
    }

    public static Optional<String> current(HttpServletRequest request) {
        if (request.getAttribute(ATTRIBUTE) instanceof String id) {
            return Optional.of(id);
        }
        var header = request.getHeader("traceparent");
        var id = Optional.ofNullable(header)
                .map(TRACEPARENT::matcher)
                .filter(java.util.regex.Matcher::matches)
                .map(m -> m.group(1))
                .orElseGet(() -> java.util.UUID.randomUUID().toString().replace("-", ""));
        request.setAttribute(ATTRIBUTE, id);
        return Optional.of(id);
    }
}
