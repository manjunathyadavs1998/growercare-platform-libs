package com.growercare.security.web;

import java.util.List;
import java.util.Map;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.AbstractOAuth2TokenAuthenticationToken;

/** Another GrowerCare service calling {@code /internal/**} with a client-credentials token. */
public final class ServiceAuthenticationToken extends AbstractOAuth2TokenAuthenticationToken<Jwt> {

    public static final String ROLE_SERVICE = "ROLE_SERVICE";

    private final String clientId;

    public ServiceAuthenticationToken(Jwt jwt, String clientId) {
        super(jwt, clientId, jwt, List.of(new SimpleGrantedAuthority(ROLE_SERVICE)));
        this.clientId = clientId;
        setAuthenticated(true);
    }

    @Override
    public Map<String, Object> getTokenAttributes() {
        return getToken().getClaims();
    }

    @Override
    public String getName() {
        return "service:" + clientId;
    }
}
