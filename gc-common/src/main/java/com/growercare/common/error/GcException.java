package com.growercare.common.error;

import java.util.Map;

/**
 * Root of all expected (business/client) failures. Sealed so the HTTP mapping is exhaustive and reviewed:
 * adding a new failure type is a deliberate change to the error catalogue.
 * <p>Messages must never contain PII — they are returned to clients as the Problem {@code detail}.
 */
public abstract sealed class GcException extends RuntimeException
        permits NotFoundException, PermissionDeniedException, StaleVersionException, ConflictException,
                BusinessRuleException, ValidationException, ArchiveNotEnabledException, DependencyUnavailableException {

    private final ErrorCode code;
    private final transient Map<String, Object> properties;

    protected GcException(ErrorCode code, String message, Map<String, Object> properties, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.properties = Map.copyOf(properties);
    }

    public ErrorCode code() { return code; }

    /** Extra, non-sensitive Problem Details members (e.g. {@code currentVersion}). */
    public Map<String, Object> properties() { return properties; }
}
