package com.growercare.security.web;

import org.springframework.security.core.AuthenticationException;

/**
 * The token was valid but grants could not be loaded (IAM unreachable). Reported as 503 by the entry point,
 * never as an anonymous or default principal (fail closed).
 */
public final class PrincipalUnavailableException extends AuthenticationException {

    public PrincipalUnavailableException(Throwable cause) {
        super("Grants are temporarily unavailable", cause);
    }
}
