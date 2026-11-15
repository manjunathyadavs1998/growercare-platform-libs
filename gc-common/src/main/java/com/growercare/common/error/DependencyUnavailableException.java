package com.growercare.common.error;

import java.util.Map;

/** 503: a downstream dependency (IAM, Redis, …) failed; safe for clients to retry with backoff. */
public final class DependencyUnavailableException extends GcException {

    public DependencyUnavailableException(String dependency, Throwable cause) {
        super(ErrorCode.DEPENDENCY_UNAVAILABLE, dependency + " is temporarily unavailable",
                Map.of("dependency", dependency), cause);
    }
}
