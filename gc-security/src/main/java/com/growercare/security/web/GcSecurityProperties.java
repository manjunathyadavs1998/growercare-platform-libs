package com.growercare.security.web;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code gc.security.*}. JWT signature/issuer/audience validation itself is configured through Boot's
 * {@code spring.security.oauth2.resourceserver.jwt.*} properties.
 *
 * @param apiAudience        audience of end-user tokens (Keycloak clients gc-web, gc-dev-cli)
 * @param internalAudience   audience of service-to-service tokens (client credentials)
 * @param internalClients    Keycloak client ids allowed to call {@code /internal/**}
 * @param corsAllowedOrigins exact browser origins; empty = no CORS
 * @param principalCacheTtl  how long other services may cache a resolved principal
 */
@ConfigurationProperties("gc.security")
public record GcSecurityProperties(
        String apiAudience,
        String internalAudience,
        List<String> internalClients,
        List<String> corsAllowedOrigins,
        Duration principalCacheTtl) {

    public GcSecurityProperties {
        apiAudience = apiAudience == null ? "gc-api" : apiAudience;
        internalAudience = internalAudience == null ? "gc-internal" : internalAudience;
        internalClients = internalClients == null ? List.of() : List.copyOf(internalClients);
        corsAllowedOrigins = corsAllowedOrigins == null ? List.of() : List.copyOf(corsAllowedOrigins);
        principalCacheTtl = principalCacheTtl == null ? Duration.ofMinutes(5) : principalCacheTtl;
        if (corsAllowedOrigins.contains("*")) {
            throw new IllegalArgumentException("gc.security.cors-allowed-origins must list exact origins, not '*'");
        }
    }
}
