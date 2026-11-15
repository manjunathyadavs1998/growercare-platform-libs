package com.growercare.security.web;

import com.growercare.security.GcPrincipal;
import java.util.Collection;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.AbstractOAuth2TokenAuthenticationToken;

/** An end user, authenticated by Keycloak and resolved to a {@link GcPrincipal} with grants. */
public final class GcAuthenticationToken extends AbstractOAuth2TokenAuthenticationToken<Jwt> {

    private final GcPrincipal principal;

    public GcAuthenticationToken(Jwt jwt, GcPrincipal principal, Collection<? extends GrantedAuthority> authorities) {
        super(jwt, principal, jwt, authorities);
        this.principal = principal;
        setAuthenticated(true);
    }

    @Override
    public GcPrincipal getPrincipal() {
        return principal;
    }

    @Override
    public java.util.Map<String, Object> getTokenAttributes() {
        return getToken().getClaims();
    }

    @Override
    public String getName() {
        return Long.toString(principal.userId());
    }
}
