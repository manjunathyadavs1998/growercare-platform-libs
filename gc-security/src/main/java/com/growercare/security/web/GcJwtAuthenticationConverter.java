package com.growercare.security.web;

import com.growercare.common.error.DependencyUnavailableException;
import com.growercare.security.PrincipalResolver;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;

/**
 * Turns a validated JWT (signature, issuer, expiry, audience already checked by the decoder) into:
 * <ul>
 *   <li>{@link ServiceAuthenticationToken} — audience {@code gc-internal} from an allow-listed client;</li>
 *   <li>{@link GcAuthenticationToken} — audience {@code gc-api}; subject resolved to grants via {@link PrincipalResolver}.</li>
 * </ul>
 * Authorities: {@code ROLE_USER} + {@code PERM_<permission>} (informational; decisions use the grants).
 * Keycloak realm roles in the token are deliberately ignored.
 */
public final class GcJwtAuthenticationConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    public static final String ROLE_USER = "ROLE_USER";

    private final PrincipalResolver resolver;
    private final GcSecurityProperties props;
    private final Clock clock;

    public GcJwtAuthenticationConverter(PrincipalResolver resolver, GcSecurityProperties props, Clock clock) {
        this.resolver = resolver;
        this.props = props;
        this.clock = clock;
    }

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        var audiences = Optional.ofNullable(jwt.getAudience()).orElse(List.of());
        if (audiences.contains(props.internalAudience())) {
            var clientId = Optional.ofNullable(jwt.getClaimAsString("azp")).orElse(jwt.getClaimAsString("client_id"));
            if (clientId != null && props.internalClients().contains(clientId)) {
                return new ServiceAuthenticationToken(jwt, clientId);
            }
            if (!audiences.contains(props.apiAudience())) {
                throw new InvalidBearerTokenException("Client not allowed to call internal APIs");
            }
        }
        if (!audiences.contains(props.apiAudience())) {
            throw new InvalidBearerTokenException("Token audience not accepted");
        }
        var subject = parseSubject(jwt.getSubject());
        try {
            var principal = resolver.resolve(subject)
                    .orElseThrow(() -> new DisabledException("No active GrowerCare account"));
            var authorities = new ArrayList<GrantedAuthority>();
            authorities.add(new SimpleGrantedAuthority(ROLE_USER));
            principal.permissions(clock.instant()).stream()
                    .sorted()
                    .map(p -> new SimpleGrantedAuthority("PERM_" + p))
                    .forEach(authorities::add);
            return new GcAuthenticationToken(jwt, principal, authorities);
        } catch (DependencyUnavailableException e) {
            throw new PrincipalUnavailableException(e);
        }
    }

    private static UUID parseSubject(String subject) {
        try {
            return UUID.fromString(subject);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new InvalidBearerTokenException("Token subject is not a valid user id");
        }
    }
}
