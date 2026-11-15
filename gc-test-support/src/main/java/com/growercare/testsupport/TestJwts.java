package com.growercare.testsupport;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/**
 * Mints RS256 tokens shaped like Keycloak's, signed by an in-memory key, and a matching {@link JwtDecoder} that applies
 * the same validations as production (signature, issuer, expiry, audience). Tests never need a running Keycloak.
 * <pre>{@code
 * String token = TestJwts.user(SUBJECT).sign();               // aud=gc-api
 * String bad   = TestJwts.user(SUBJECT).audience("other").sign();
 * String svc   = TestJwts.service("case-service").sign();     // aud=gc-internal, azp=case-service
 * }</pre>
 */
public final class TestJwts {

    public static final String ISSUER = "https://keycloak.test/realms/growercare";
    private static final KeyPair KEYS = generate();

    private TestJwts() { }

    /** Dev-seed subjects (local-dev/keycloak/growercare-realm.json). */
    public static final UUID GROWER_IN1 = UUID.fromString("6f1c0a10-0001-4a00-8000-000000000001");
    public static final UUID GROWER_IN2 = UUID.fromString("6f1c0a10-0002-4a00-8000-000000000002");
    public static final UUID GROWER_BR1 = UUID.fromString("6f1c0a10-0003-4a00-8000-000000000003");
    public static final UUID CARE_IN = UUID.fromString("6f1c0a10-0101-4a00-8000-000000000101");
    public static final UUID AGRO_IN = UUID.fromString("6f1c0a10-0102-4a00-8000-000000000102");
    public static final UUID TM_IN = UUID.fromString("6f1c0a10-0103-4a00-8000-000000000103");
    public static final UUID ADMIN_IN = UUID.fromString("6f1c0a10-0104-4a00-8000-000000000104");
    public static final UUID CARE_BR = UUID.fromString("6f1c0a10-0105-4a00-8000-000000000105");
    public static final UUID RETAILER_IN = UUID.fromString("6f1c0a10-0201-4a00-8000-000000000201");
    public static final UUID RETAILER2_IN = UUID.fromString("6f1c0a10-0202-4a00-8000-000000000202");

    public static Builder user(UUID subject) {
        return new Builder(subject.toString(), "gc-dev-cli", List.of("gc-api"));
    }

    public static Builder service(String clientId) {
        return new Builder(UUID.randomUUID().toString(), clientId, List.of("gc-internal"));
    }

    /** Decoder with production-equivalent validation for {@code acceptedAudiences}. */
    public static JwtDecoder decoder(List<String> acceptedAudiences) {
        var decoder = NimbusJwtDecoder.withPublicKey((RSAPublicKey) KEYS.getPublic()).build();
        List<OAuth2TokenValidator<Jwt>> validators = List.of(
                new JwtTimestampValidator(Duration.ofSeconds(30)),
                new JwtIssuerValidator(ISSUER),
                new JwtClaimValidator<List<String>>("aud", aud -> aud != null && aud.stream().anyMatch(acceptedAudiences::contains)));
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(validators));
        return decoder;
    }

    public static final class Builder {
        private final String subject;
        private final String clientId;
        private List<String> audiences;
        private String issuer = ISSUER;
        private Instant expiresAt = Instant.now().plus(Duration.ofMinutes(5));
        private KeyPair signingKeys = KEYS;

        private Builder(String subject, String clientId, List<String> audiences) {
            this.subject = subject;
            this.clientId = clientId;
            this.audiences = audiences;
        }

        public Builder audience(String... aud) { this.audiences = List.of(aud); return this; }

        public Builder issuer(String iss) { this.issuer = iss; return this; }

        public Builder expiresAt(Instant at) { this.expiresAt = at; return this; }

        /** Signs with an unrelated key (signature must be rejected). */
        public Builder forgedSignature() { this.signingKeys = generate(); return this; }

        public String sign() {
            var claims = new JWTClaimsSet.Builder()
                    .issuer(issuer)
                    .subject(subject)
                    .audience(new ArrayList<>(audiences))
                    .claim("azp", clientId)
                    .issueTime(Date.from(expiresAt.minus(Duration.ofMinutes(5))))
                    .expirationTime(Date.from(expiresAt))
                    .jwtID(UUID.randomUUID().toString())
                    .build();
            try {
                var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("test").build(), claims);
                jwt.sign(new RSASSASigner(signingKeys.getPrivate()));
                return jwt.serialize();
            } catch (JOSEException e) {
                throw new IllegalStateException(e);
            }
        }

        public String bearer() {
            return "Bearer " + sign();
        }
    }

    private static KeyPair generate() {
        try {
            var generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
