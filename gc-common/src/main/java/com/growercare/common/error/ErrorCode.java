package com.growercare.common.error;

import org.springframework.http.HttpStatus;

/**
 * Stable, client-facing error catalogue. The {@link #slug()} becomes the RFC 9457 {@code type} URI suffix,
 * so slugs are part of the API contract: never rename one, only add new codes.
 */
public enum ErrorCode {
    NOT_FOUND("not-found", HttpStatus.NOT_FOUND, "Resource not found"),
    PERMISSION_DENIED("permission-denied", HttpStatus.FORBIDDEN, "Permission denied"),
    UNAUTHENTICATED("unauthenticated", HttpStatus.UNAUTHORIZED, "Authentication required"),
    VALIDATION_FAILED("validation-failed", HttpStatus.BAD_REQUEST, "Request validation failed"),
    METHOD_NOT_ALLOWED("method-not-allowed", HttpStatus.METHOD_NOT_ALLOWED, "Method not allowed for this resource"),
    NOT_ACCEPTABLE("not-acceptable", HttpStatus.NOT_ACCEPTABLE, "No acceptable representation"),
    UNSUPPORTED_MEDIA_TYPE("unsupported-media-type", HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Unsupported request content type"),
    STALE_VERSION("stale-version", HttpStatus.PRECONDITION_FAILED, "Resource was modified by someone else"),
    PRECONDITION_REQUIRED("precondition-required", HttpStatus.PRECONDITION_REQUIRED, "If-Match header is required"),
    DUPLICATE("duplicate", HttpStatus.CONFLICT, "Resource already exists"),
    IDEMPOTENCY_KEY_REUSED("idempotency-key-reused", HttpStatus.CONFLICT, "Idempotency key was used with a different request"),
    NOT_READY("not-ready", HttpStatus.CONFLICT, "Resource is not ready for this operation"),
    BUSINESS_RULE_VIOLATION("business-rule-violation", HttpStatus.UNPROCESSABLE_CONTENT, "Business rule violated"),
    ARCHIVE_NOT_ENABLED("archive-not-enabled", HttpStatus.NOT_IMPLEMENTED, "Archive queries are not enabled yet"),
    DEPENDENCY_UNAVAILABLE("dependency-unavailable", HttpStatus.SERVICE_UNAVAILABLE, "A dependency is temporarily unavailable"),
    INTERNAL_ERROR("internal-error", HttpStatus.INTERNAL_SERVER_ERROR, "Unexpected error");

    public static final String TYPE_BASE = "https://errors.growercare.example/";

    private final String slug;
    private final HttpStatus status;
    private final String title;

    ErrorCode(String slug, HttpStatus status, String title) {
        this.slug = slug;
        this.status = status;
        this.title = title;
    }

    public String slug() { return slug; }

    public HttpStatus status() { return status; }

    public String title() { return title; }

    public String typeUri() { return TYPE_BASE + slug; }
}
