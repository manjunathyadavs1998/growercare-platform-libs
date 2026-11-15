package com.growercare.common.error;

import java.util.Map;

/** 412: optimistic-lock failure ({@code If-Match} version no longer current). */
public final class StaleVersionException extends GcException {

    public StaleVersionException(String resourceType, long expectedVersion) {
        super(ErrorCode.STALE_VERSION, resourceType + " was modified; reload and retry",
                Map.of("resource", resourceType, "expectedVersion", expectedVersion), null);
    }
}
