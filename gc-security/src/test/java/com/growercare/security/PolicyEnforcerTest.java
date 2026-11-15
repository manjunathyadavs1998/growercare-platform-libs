package com.growercare.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.params.provider.Arguments.argumentSet;

import com.growercare.common.error.NotFoundException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** SPEC-CASE-01 rules R1–R6 at unit level ({@code docs/specs/case/01-case-access-scope.md}). */
class PolicyEnforcerTest {

    static final Instant NOW = Instant.parse("2026-11-15T10:00:00Z");
    final PolicyEnforcer policy = new PolicyEnforcer(Clock.fixed(NOW, ZoneOffset.UTC));

    // An IN case in Pune (1002, child of Maharashtra 1001), crop protection, owned by user 1, via retailer 501, assigned to 102.
    static final ResourceScope PUNE_CP = new ResourceScope("IN", 1002L, "CP", 1L, Set.of(501L), 102L);
    static final ResourceScope NASHIK_SEEDS = new ResourceScope("IN", 1003L, "SEEDS", 2L, Set.of(), null);
    static final ResourceScope KARNATAKA_CP = new ResourceScope("IN", 1005L, "CP", 9L, Set.of(), null);
    static final ResourceScope BRAZIL_CP = new ResourceScope("BR", 2002L, "CP", 3L, Set.of(), null);
    static final ResourceScope PUNE_CP_REASSIGNED = new ResourceScope("IN", 1002L, "CP", 1L, Set.of(), 999L);

    static final GcPrincipal CARE_IN = staff(101, grant("CARE_AGENT", ScopeKind.COUNTRY, Set.of("IN"), Set.of(), Set.of(), null, "case:read"));
    static final GcPrincipal CARE_IN_CP_ONLY = staff(101, grant("CARE_AGENT", ScopeKind.COUNTRY, Set.of("IN"), Set.of(), Set.of("CP"), null, "case:read"));
    static final GcPrincipal TM_MAHARASHTRA = staff(103, grant("TERRITORY_MANAGER", ScopeKind.TERRITORY, Set.of(), Set.of(1001L, 1002L, 1003L), Set.of(), null, "case:read"));
    static final GcPrincipal AGRO_PUNE_CP = staff(102, grant("FIELD_AGRONOMIST", ScopeKind.ASSIGNED, Set.of(), Set.of(1002L), Set.of("CP"), null, "case:read"));
    static final GcPrincipal GROWER_1 = new GcPrincipal(1, UUID.randomUUID(), UserType.GROWER, "IN", null,
            List.of(grant("GROWER", ScopeKind.OWN, Set.of("IN"), Set.of(), Set.of(), null, "case:read")));
    static final GcPrincipal RETAILER_501 = partner(201, 501L);
    static final GcPrincipal RETAILER_502 = partner(202, 502L);
    static final GcPrincipal EXPIRED_CARE = staff(101, grant("CARE_AGENT", ScopeKind.COUNTRY, Set.of("IN"), Set.of(), Set.of(), NOW.minusSeconds(1), "case:read"));
    static final GcPrincipal CARE_WITHOUT_READ = staff(101, grant("CARE_AGENT", ScopeKind.COUNTRY, Set.of("IN"), Set.of(), Set.of(), null, "case:create"));
    static final GcPrincipal GLOBAL_EXEC = staff(900, grant("GLOBAL_EXECUTIVE", ScopeKind.GLOBAL, Set.of(), Set.of(), Set.of(), null, "case:read"));

    /** Columns: principal, resource, expected permit. */
    static Stream<Arguments> matrix() {
        return Stream.of(
                argumentSet("CASE-R4 country grant, own country", CARE_IN, PUNE_CP, true),
                argumentSet("CASE-R4 country grant, other BU same country", CARE_IN, NASHIK_SEEDS, true),
                argumentSet("CASE-R4 country grant, other country", CARE_IN, BRAZIL_CP, false),
                argumentSet("CASE-R4 BU scope matches", CARE_IN_CP_ONLY, PUNE_CP, true),
                argumentSet("CASE-R4 BU scope excludes", CARE_IN_CP_ONLY, NASHIK_SEEDS, false),
                argumentSet("CASE-R4 territory incl. children", TM_MAHARASHTRA, PUNE_CP, true),
                argumentSet("CASE-R4 territory outside", TM_MAHARASHTRA, KARNATAKA_CP, false),
                argumentSet("CASE-R3 assigned in scope", AGRO_PUNE_CP, PUNE_CP, true),
                argumentSet("CASE-R3 assigned to someone else", AGRO_PUNE_CP, PUNE_CP_REASSIGNED, false),
                argumentSet("CASE-R1 own case", GROWER_1, PUNE_CP, true),
                argumentSet("CASE-R1 other grower's case", GROWER_1, NASHIK_SEEDS, false),
                argumentSet("CASE-R2 raised via own org", RETAILER_501, PUNE_CP, true),
                argumentSet("CASE-R2 other org", RETAILER_502, PUNE_CP, false),
                argumentSet("CASE-R6 expired grant", EXPIRED_CARE, PUNE_CP, false),
                argumentSet("CASE-R6 permission missing", CARE_WITHOUT_READ, PUNE_CP, false),
                argumentSet("CASE-R6 global role never row-level", GLOBAL_EXEC, PUNE_CP, false));
    }

    @ParameterizedTest
    @MethodSource("matrix")
    void permits(GcPrincipal principal, ResourceScope resource, boolean expected) {
        assertThat(policy.permits(principal, "case:read", resource)).isEqualTo(expected);
    }

    @ParameterizedTest
    @MethodSource("matrix")
    void requireAnswersNotFoundExactlyWhenNotPermitted(GcPrincipal principal, ResourceScope resource, boolean expected) {
        if (expected) {
            policy.require(principal, "case:read", resource, "Case");
        } else {
            assertThatThrownBy(() -> policy.require(principal, "case:read", resource, "Case"))
                    .isInstanceOf(NotFoundException.class).hasMessage("Case not found");
        }
    }

    // ---------------------------------------------------------------------------------------------- fixtures

    static EffectiveGrant grant(String role, ScopeKind kind, Set<String> countries, Set<Long> territories, Set<String> bus,
                                Instant validTo, String... permissions) {
        return new EffectiveGrant(1, role, 30, kind, PiiClearance.MASKED, Set.of(permissions), countries, territories, bus, validTo);
    }

    static GcPrincipal staff(long userId, EffectiveGrant... grants) {
        return new GcPrincipal(userId, UUID.randomUUID(), UserType.STAFF, "IN", null, List.of(grants));
    }

    static GcPrincipal partner(long userId, long org) {
        return new GcPrincipal(userId, UUID.randomUUID(), UserType.PARTNER, "IN", org,
                List.of(grant("RETAILER_USER", ScopeKind.ORG, Set.of("IN"), Set.of(), Set.of(), null, "case:read")));
    }
}
