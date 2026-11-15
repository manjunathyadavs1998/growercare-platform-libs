package com.growercare.security;

import java.util.Set;

/**
 * The access-relevant attributes of one record (BRD §2: every record carries country, territory, BU, partner).
 *
 * @param countryCode     record country
 * @param territoryId     record territory (may be null for country-level records)
 * @param buCode          business unit (may be null when not BU-specific)
 * @param ownerUserId     IAM user who owns the record (the grower's user id), or null
 * @param partnerOrgIds   partner orgs entitled to the record (raised via, or actively shared with)
 * @param assigneeUserId  assigned staff user, or null
 */
public record ResourceScope(
        String countryCode,
        Long territoryId,
        String buCode,
        Long ownerUserId,
        Set<Long> partnerOrgIds,
        Long assigneeUserId) {

    public ResourceScope {
        partnerOrgIds = partnerOrgIds == null ? Set.of() : Set.copyOf(partnerOrgIds);
    }
}
