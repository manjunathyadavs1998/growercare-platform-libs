package com.growercare.security;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.security.access.prepost.PreAuthorize;

/**
 * Function-level authorization: the caller must hold {@code value} in at least one active grant, otherwise 403.
 * Put it on application-service (use case) methods; object-level scope is checked separately with
 * {@link PolicyEnforcer} / {@link ScopeFilter} (404 when out of scope).
 * <pre>{@code @RequiresPermission("grant:create")}</pre>
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@PreAuthorize("@gcAuthz.has('{value}')")
public @interface RequiresPermission {

    /** Permission code, e.g. {@code case:transition}. */
    String value();
}
