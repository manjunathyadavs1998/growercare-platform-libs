package com.growercare.common.error;

import java.util.Map;

/**
 * 404. Also used when a resource exists but is outside the caller's scope, so its existence is not revealed.
 */
public final class NotFoundException extends GcException {

    public NotFoundException(String resourceType) {
        super(ErrorCode.NOT_FOUND, resourceType + " not found", Map.of("resource", resourceType), null);
    }
}
