package com.growercare.security;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The authenticated user as GrowerCare sees it: IAM identity + effective grants. Built after JWT validation by a
 * {@link PrincipalResolver}; immutable and safe to cache briefly (grants carry their own expiry, re-checked here).
 */
public record GcPrincipal(
        long userId,
        UUID subject,
        UserType userType,
        String homeCountry,
        Long partnerOrgId,
        List<EffectiveGrant> grants) {

    public GcPrincipal {
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(userType, "userType");
        Objects.requireNonNull(homeCountry, "homeCountry");
        grants = List.copyOf(grants);
        if ((userType == UserType.PARTNER) != (partnerOrgId != null)) {
            throw new IllegalArgumentException("partnerOrgId must be set exactly for PARTNER users");
        }
    }

    /** Grants still valid at {@code now} that include {@code permission}. */
    public Stream<EffectiveGrant> grantsWith(String permission, Instant now) {
        return grants.stream().filter(g -> g.isActiveAt(now) && g.grants(permission));
    }

    public boolean hasPermission(String permission, Instant now) {
        return grantsWith(permission, now).findAny().isPresent();
    }

    public Set<String> permissions(Instant now) {
        return grants.stream()
                .filter(g -> g.isActiveAt(now))
                .flatMap(g -> g.permissions().stream())
                .collect(Collectors.toUnmodifiableSet());
    }

    /** Highest PII clearance among active grants holding {@code permission}. */
    public PiiClearance piiClearanceFor(String permission, Instant now) {
        return grantsWith(permission, now)
                .map(EffectiveGrant::piiClearance)
                .max(Comparator.naturalOrder())
                .orElse(PiiClearance.NONE);
    }

    public Optional<Long> partnerOrg() {
        return Optional.ofNullable(partnerOrgId);
    }
}
