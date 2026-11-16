package com.growercare.common.error;

import java.util.Map;

/** 409: duplicate resource, reused idempotency key, or a resource that is not ready yet (with a {@code rule}). */
public final class ConflictException extends GcException {

    public ConflictException(ErrorCode code, String message) {
        this(code, message, null);
    }

    public ConflictException(ErrorCode code, String message, Throwable cause) {
        super(requireConflict(code), message, Map.of(), cause);
    }

    private ConflictException(String rule, String message) {
        super(ErrorCode.NOT_READY, message, Map.of("rule", rule), null);
    }

    /** 409 NOT_READY, e.g. {@code attachment.not-available} while a file is still being scanned (SPEC-CASE-05 R110). */
    public static ConflictException notReady(String rule, String message) {
        return new ConflictException(rule, message);
    }

    private static ErrorCode requireConflict(ErrorCode code) {
        return switch (code) {
            case DUPLICATE, IDEMPOTENCY_KEY_REUSED, NOT_READY -> code;
            default -> throw new IllegalArgumentException("Not a conflict code: " + code);
        };
    }
}
