package com.growercare.security;

import static com.growercare.security.PolicyEnforcerTest.NOW;
import static com.growercare.security.PolicyEnforcerTest.grant;
import static com.growercare.security.PolicyEnforcerTest.staff;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.params.provider.Arguments.argumentSet;

import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/** SPEC-CASE-01 R5/R6: the SQL twin of the policy produces bound, predictable predicates. */
class ScopeFilterTest {

    static final ScopeFilter.Columns CASES = new ScopeFilter.Columns(
            "c.country_code", "c.territory_id", "c.bu_code",
            "c.grower_id IN (SELECT g.grower_id FROM ref.grower g WHERE g.user_id = {param})",
            "c.assignee_user_id",
            "c.raised_via_partner_org_id = {param}");

    final ScopeFilter filter = new ScopeFilter(Clock.fixed(NOW, ZoneOffset.UTC));

    /** Columns: principal, expected SQL, expected parameters. */
    static Stream<Arguments> predicates() {
        return Stream.of(
                argumentSet("CASE-R6 no grant with the permission",
                        staff(101, grant("CARE_AGENT", ScopeKind.COUNTRY, Set.of("IN"), Set.of(), Set.of(), null, "case:create")),
                        "FALSE", Map.of()),
                argumentSet("CASE-R6 expired and global grants contribute nothing",
                        staff(1, grant("X", ScopeKind.COUNTRY, Set.of("IN"), Set.of(), Set.of(), NOW.minusSeconds(1), "case:read"),
                                grant("GLOBAL_EXECUTIVE", ScopeKind.GLOBAL, Set.of(), Set.of(), Set.of(), null, "case:read")),
                        "FALSE", Map.of()),
                argumentSet("CASE-R4 country + BU",
                        staff(101, grant("CARE_AGENT", ScopeKind.COUNTRY, Set.of("IN"), Set.of(), Set.of("CP"), null, "case:read")),
                        "(c.country_code IN (:sf0_countries) AND c.bu_code IN (:sf0_bus))",
                        Map.of("sf0_countries", List.of("IN"), "sf0_bus", List.of("CP"))),
                argumentSet("CASE-R3 assigned within territory",
                        staff(102, grant("FIELD_AGRONOMIST", ScopeKind.ASSIGNED, Set.of(), Set.of(1002L), Set.of(), null, "case:read")),
                        "(c.assignee_user_id = :sf0_assignee AND c.territory_id IN (:sf0_territories))",
                        Map.of("sf0_assignee", 102L, "sf0_territories", List.of(1002L))),
                argumentSet("CASE-R1+R2 several grants are ORed, values bound",
                        new GcPrincipal(201, UUID.randomUUID(), UserType.PARTNER, "IN", 501L, List.of(
                                grant("RETAILER_USER", ScopeKind.ORG, Set.of("IN"), Set.of(), Set.of(), null, "case:read"),
                                grant("GROWER", ScopeKind.OWN, Set.of("IN"), Set.of(), Set.of(), null, "case:read"))),
                        "((c.raised_via_partner_org_id = :sf0_org) OR (c.grower_id IN (SELECT g.grower_id FROM ref.grower g WHERE g.user_id = :sf1_user)))",
                        Map.of("sf0_org", 501L, "sf1_user", 201L)));
    }

    @ParameterizedTest
    @MethodSource("predicates")
    void predicateFor(GcPrincipal principal, String expectedSql, Map<String, Object> expectedParams) {
        var predicate = filter.forPermission(principal, "case:read", CASES);
        assertThat(predicate.sql()).isEqualTo(expectedSql);
        assertThat(predicate.params()).isEqualTo(expectedParams);
    }

    @ParameterizedTest
    @ValueSource(strings = {"c.country_code; DROP TABLE x", "c.bu_code -- comment", "c.x = 'literal'", "c.x /* y */"})
    void unsafeColumnFragmentsAreRejected(String fragment) {
        assertThatThrownBy(() -> new ScopeFilter.Columns(fragment, "t", "b", "o", "a", "p"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
