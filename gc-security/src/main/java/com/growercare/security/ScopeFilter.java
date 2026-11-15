package com.growercare.security;

import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * List-level authorization: turns a principal's grants into a SQL predicate that is ANDed into list queries, so
 * rows outside the caller's scope are never read (skill: java-security-rbac). Same rules as {@link PolicyEnforcer}.
 * <p>The SQL is assembled only from {@link Columns} (fixed, code-defined fragments) and generated parameter names;
 * every value is a bind parameter. No grant with the permission ⇒ {@code FALSE}.
 */
public final class ScopeFilter {

    private static final Pattern SAFE_FRAGMENT = Pattern.compile("^[a-zA-Z0-9_.(),:=<>!\\s{}]+$");

    /**
     * Column expressions of the queried table. Fragments with a {@code {param}} token receive one bound value.
     *
     * @param country          e.g. {@code c.country_code}
     * @param territory        e.g. {@code c.territory_id}
     * @param bu               e.g. {@code c.bu_code}
     * @param ownedBy          predicate true when the row belongs to user {@code {param}}, e.g.
     *                         {@code c.grower_id IN (SELECT g.grower_id FROM ref.grower g WHERE g.user_id = {param})}
     * @param assignee         e.g. {@code c.assignee_user_id}
     * @param partnerEntitled  predicate true when partner org {@code {param}} may see the row
     */
    public record Columns(String country, String territory, String bu, String ownedBy, String assignee, String partnerEntitled) {
        public Columns {
            for (var fragment : List.of(country, territory, bu, ownedBy, assignee, partnerEntitled)) {
                if (!SAFE_FRAGMENT.matcher(Objects.requireNonNull(fragment)).matches()) {
                    throw new IllegalArgumentException("Unsafe SQL fragment: " + fragment);
                }
            }
        }
    }

    /** A predicate and its named parameters, ready for {@code JdbcClient.sql(...).params(params)}. */
    public record SqlPredicate(String sql, Map<String, Object> params) {
        public SqlPredicate {
            params = Map.copyOf(params);
        }

        public static SqlPredicate alwaysFalse() {
            return new SqlPredicate("FALSE", Map.of());
        }
    }

    private final Clock clock;

    public ScopeFilter(Clock clock) {
        this.clock = Objects.requireNonNull(clock);
    }

    public SqlPredicate forPermission(GcPrincipal principal, String permission, Columns cols) {
        var params = new LinkedHashMap<String, Object>();
        var clauses = new ArrayList<String>();
        var grants = principal.grantsWith(permission, clock.instant()).toList();
        for (int i = 0; i < grants.size(); i++) {
            clause(principal, grants.get(i), cols, "sf" + i + "_", params).ifPresent(clauses::add);
        }
        if (clauses.isEmpty()) {
            return SqlPredicate.alwaysFalse();
        }
        return new SqlPredicate(clauses.size() == 1 ? clauses.getFirst() : "(" + String.join(" OR ", clauses) + ")", params);
    }

    private static Optional<String> clause(GcPrincipal principal, EffectiveGrant grant, Columns cols,
                                                     String prefix, Map<String, Object> params) {
        return switch (grant.scopeKind()) {
            case OWN -> {
                params.put(prefix + "user", principal.userId());
                yield Optional.of("(" + cols.ownedBy().replace("{param}", ":" + prefix + "user") + ")");
            }
            case ORG -> principal.partnerOrg().map(org -> {
                params.put(prefix + "org", org);
                return "(" + cols.partnerEntitled().replace("{param}", ":" + prefix + "org") + ")";
            });
            case ASSIGNED -> geoAndBu(grant, cols, prefix, params).map(geo -> {
                params.put(prefix + "assignee", principal.userId());
                return "(" + cols.assignee() + " = :" + prefix + "assignee AND " + geo + ")";
            });
            case TERRITORY, COUNTRY, REGION -> geoAndBu(grant, cols, prefix, params);
            case GLOBAL -> Optional.empty();   // aggregates only, never rows
        };
    }

    private static Optional<String> geoAndBu(EffectiveGrant grant, Columns cols, String prefix, Map<String, Object> params) {
        var geo = new ArrayList<String>();
        if (!grant.countries().isEmpty()) {
            params.put(prefix + "countries", List.copyOf(grant.countries()));
            geo.add(cols.country() + " IN (:" + prefix + "countries)");
        }
        if (!grant.territories().isEmpty()) {
            params.put(prefix + "territories", List.copyOf(grant.territories()));
            geo.add(cols.territory() + " IN (:" + prefix + "territories)");
        }
        if (geo.isEmpty()) {
            return Optional.empty();
        }
        var sql = geo.size() == 1 ? geo.getFirst() : "(" + String.join(" OR ", geo) + ")";
        if (!grant.buCodes().isEmpty()) {
            params.put(prefix + "bus", List.copyOf(grant.buCodes()));
            sql = "(" + sql + " AND " + cols.bu() + " IN (:" + prefix + "bus))";
        }
        return Optional.of(sql);
    }
}
