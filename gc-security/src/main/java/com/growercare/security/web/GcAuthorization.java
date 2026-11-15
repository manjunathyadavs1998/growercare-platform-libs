package com.growercare.security.web;

import java.time.Clock;

/**
 * Bean {@code gcAuthz}, referenced by {@link com.growercare.security.RequiresPermission}'s SpEL.
 * Checks the permission against the principal's <b>currently valid</b> grants.
 */
public final class GcAuthorization {

    private final Clock clock;

    public GcAuthorization(Clock clock) {
        this.clock = clock;
    }

    public boolean has(String permission) {
        return CurrentPrincipal.find()
                .map(p -> p.hasPermission(permission, clock.instant()))
                .orElse(false);
    }
}
