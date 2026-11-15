package com.growercare.security;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;

/**
 * One active role grant, already resolved by IAM into what enforcement needs (BRD §4.1, ACC-1):
 * <ul>
 *   <li>{@code permissions}: the role's permissions</li>
 *   <li>{@code countries}: countries covered completely (country scopes + every country of a region scope)</li>
 *   <li>{@code territories}: territory scopes <b>including all descendants</b> (state ⇒ its districts)</li>
 *   <li>{@code buCodes}: business units; <b>empty means all BUs</b></li>
 * </ul>
 */
public record EffectiveGrant(
        long grantId,
        String roleCode,
        int roleLevel,
        ScopeKind scopeKind,
        PiiClearance piiClearance,
        Set<String> permissions,
        Set<String> countries,
        Set<Long> territories,
        Set<String> buCodes,
        Instant validTo) {

    public EffectiveGrant {
        Objects.requireNonNull(roleCode, "roleCode");
        Objects.requireNonNull(scopeKind, "scopeKind");
        Objects.requireNonNull(piiClearance, "piiClearance");
        permissions = Set.copyOf(permissions);
        countries = Set.copyOf(countries);
        territories = Set.copyOf(territories);
        buCodes = Set.copyOf(buCodes);
    }

    public boolean grants(String permission) {
        return permissions.contains(permission);
    }

    public boolean isActiveAt(Instant now) {
        return validTo == null || validTo.isAfter(now);
    }

    /** True when the grant's geography covers a resource in {@code country} / {@code territoryId}. */
    public boolean coversGeo(String country, Long territoryId) {
        return scopeKind == ScopeKind.GLOBAL
                || (country != null && countries.contains(country))
                || (territoryId != null && territories.contains(territoryId));
    }

    public boolean coversBu(String buCode) {
        return buCodes.isEmpty() || (buCode != null && buCodes.contains(buCode));
    }
}
