package com.growercare.security;

import java.util.Optional;
import java.util.UUID;

/**
 * Port: Keycloak subject → GrowerCare principal with effective grants.
 * <ul>
 *   <li>identity-access-service implements it from its own database;</li>
 *   <li>other services call IAM ({@code GET /internal/users/{sub}/principal}) behind a Redis cache.</li>
 * </ul>
 * Returns empty when there is no <b>active</b> account for the subject.
 * Throws {@link com.growercare.common.error.DependencyUnavailableException} when the source cannot be reached
 * (callers must fail closed, never open).
 */
public interface PrincipalResolver {

    Optional<GcPrincipal> resolve(UUID subject);
}
