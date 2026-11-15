package com.growercare.common.error;

import java.util.List;
import java.util.Map;

/** 400: input failed validation outside Bean Validation (e.g. unknown sort field, malformed cursor). */
public final class ValidationException extends GcException {

    public record FieldError(String field, String message) { }

    private final transient List<FieldError> errors;

    public ValidationException(List<FieldError> errors) {
        super(ErrorCode.VALIDATION_FAILED, "Request validation failed", Map.of(), null);
        this.errors = List.copyOf(errors);
    }

    public static ValidationException of(String field, String message) {
        return new ValidationException(List.of(new FieldError(field, message)));
    }

    public List<FieldError> errors() { return errors; }
}
