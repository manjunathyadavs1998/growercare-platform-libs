package com.growercare.security.web;

import com.growercare.security.GcPrincipal;
import java.util.Optional;
import org.springframework.security.core.context.SecurityContextHolder;

/** Access to the authenticated end user from application services. */
public final class CurrentPrincipal {

    private CurrentPrincipal() { }

    public static Optional<GcPrincipal> find() {
        return SecurityContextHolder.getContext().getAuthentication() instanceof GcAuthenticationToken token
                ? Optional.of(token.getPrincipal())
                : Optional.empty();
    }

    /** @throws IllegalStateException when called outside an end-user request (a programming error) */
    public static GcPrincipal require() {
        return find().orElseThrow(() -> new IllegalStateException("No end-user principal in the security context"));
    }
}
