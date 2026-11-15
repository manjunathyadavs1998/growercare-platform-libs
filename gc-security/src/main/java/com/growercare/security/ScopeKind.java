package com.growercare.security;

/** How far a role's permissions reach over data (iam.role.scope_kind). */
public enum ScopeKind {
    /** Own records only (grower: own cases). */
    OWN,
    /** Records of the user's partner organisation (raised via it, or shared with it). */
    ORG,
    /** Records assigned to the user, within the grant's geo/BU scope. */
    ASSIGNED,
    TERRITORY,
    COUNTRY,
    REGION,
    /** Aggregates only; never record-level detail. */
    GLOBAL;

    public boolean isGeographic() {
        return this == TERRITORY || this == COUNTRY || this == REGION;
    }
}
