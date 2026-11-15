package com.growercare.common.error;

import java.util.Map;

/** 409: duplicate resource or reused idempotency key. */
public final class ConflictException extends GcException {

    public ConflictException(ErrorCode code, String message) {
        this(code, message, null);
    }

    public ConflictException(ErrorCode code, String message, Throwable cause) {
        super(requireConflict(code), message, Map.of(), cause);
    }

    private static ErrorCode requireConflict(ErrorCode code) {
        return switch (code) {
            case DUPLICATE, IDEMPOTENCY_KEY_REUSED -> code;
            default -> throw new IllegalArgumentException("Not a conflict code: " + code);
        };
    }
}
