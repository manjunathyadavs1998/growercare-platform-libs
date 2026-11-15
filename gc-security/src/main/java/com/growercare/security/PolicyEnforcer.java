package com.growercare.security;

import com.growercare.common.error.NotFoundException;
import java.time.Clock;
import java.util.Objects;

/**
 * Object-level authorization (policy decision + enforcement point). A principal may act on a record when at least
 * one active grant holding the permission covers it:
 * <pre>
 * OWN        resource.owner == me
 * ORG        my partner org ∈ resource.partnerOrgIds
 * ASSIGNED   resource.assignee == me  ∧ geo ∧ bu
 * TERRITORY/COUNTRY/REGION            geo ∧ bu
 * GLOBAL     never (aggregates only)
 * </pre>
 * Failure is reported as {@link NotFoundException} (404), so callers cannot probe for records outside their scope.
 * The SQL twin of these rules is {@link ScopeFilter}; both are tested against the same matrix.
 */
public final class PolicyEnforcer {

    private final Clock clock;

    public PolicyEnforcer(Clock clock) {
        this.clock = Objects.requireNonNull(clock);
    }

    public boolean permits(GcPrincipal principal, String permission, ResourceScope resource) {
        var now = clock.instant();
        return principal.grantsWith(permission, now).anyMatch(grant -> covers(principal, grant, resource));
    }

    /** @throws NotFoundException when not permitted (never 403 for object access) */
    public void require(GcPrincipal principal, String permission, ResourceScope resource, String resourceType) {
        if (!permits(principal, permission, resource)) {
            throw new NotFoundException(resourceType);
        }
    }

    static boolean covers(GcPrincipal principal, EffectiveGrant grant, ResourceScope r) {
        return switch (grant.scopeKind()) {
            case OWN -> r.ownerUserId() != null && r.ownerUserId() == principal.userId();
            case ORG -> principal.partnerOrg().map(r.partnerOrgIds()::contains).orElse(false);
            case ASSIGNED -> r.assigneeUserId() != null && r.assigneeUserId() == principal.userId()
                    && grant.coversGeo(r.countryCode(), r.territoryId()) && grant.coversBu(r.buCode());
            case TERRITORY, COUNTRY, REGION -> grant.coversGeo(r.countryCode(), r.territoryId()) && grant.coversBu(r.buCode());
            case GLOBAL -> false;
        };
    }
}
