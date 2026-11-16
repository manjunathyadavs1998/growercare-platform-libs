---
name: java-security-rbac
description: GrowerCare authentication and authorization - Keycloak JWT resource server, our own grant model (role + permissions + geo/BU scope + validity, not Keycloak roles), GcPrincipal, @RequiresPermission (403), PolicyEnforcer object-level checks (404 when out of scope), ScopeFilter to SQL, delegation and SoD rules, Redis principal cache, hardening and the security test matrix. Use when adding endpoints, permissions or roles, writing access checks or queries over scoped data, or reviewing security.
---

# Security: RBAC + scope

Read: BRD §3–§4 (roles, ACC-1..12), `docs/backend/01_RDS_SCHEMA.md §3` (grant tables), ADR-005 (Keycloak), ADR-011 (Redis).
Code:
- `libs/gc-security` (`com.growercare.security`, `.web`, `.cache`);
- IAM: `services/identity-access-service`;
- case-service wiring: `casesvc.config.CaseSecurityConfig`.

## 1. Model
```
Keycloak (identity only) ──JWT(sub, aud, azp)──► GcSecurityConfiguration (filter chain)
                                                   │ GcJwtAuthenticationConverter
                                                   ├─ aud=gc-internal + azp ∈ gc.security.internal-clients → ServiceAuthenticationToken (ROLE_SERVICE)
                                                   └─ aud=gc-api → PrincipalResolver.resolve(sub) → GcAuthenticationToken(GcPrincipal, ROLE_USER, PERM_*)
GcPrincipal = userId, subject, userType, homeCountry, partnerOrgId, List<EffectiveGrant>
EffectiveGrant = role, roleLevel, scopeKind, piiClearance, permissions,
                 countries (country scopes + all countries of region scopes),
                 territories (scopes + ALL descendants), buCodes (empty = all), validTo
```
- **Keycloak roles are ignored.** Grants, delegation, SoD and validity live in `gc_iam.iam`.
- IAM resolves principals from its own DB (`JdbcPrincipalResolver`, recursive CTE for territories). Other services call `GET /internal/users/{sub}/principal` through `CachingPrincipalResolver` (Redis key `gc:principal:{sub}`, TTL `gc.security.principal-cache-ttl`, default 5m).
- Grant expiry is re-checked on every decision (`GcPrincipal.grantsWith(permission, now)`), so a cached principal can't outlive a grant's `validTo`.

| scope_kind | Rows visible (`PolicyEnforcer` ≡ `ScopeFilter`) |
|---|---|
| `OWN` | Owner user = me (case: `ref.grower.user_id`) |
| `ORG` | My partner org raised it **or** has an active `case_share` |
| `ASSIGNED` | assignee = me ∧ geo ∧ BU |
| `TERRITORY` / `COUNTRY` / `REGION` | geo (country ∈ countries ∨ territory ∈ territories) ∧ BU |
| `GLOBAL` | Never row-level (aggregates only) |

## 2. Enforcement points
| Layer | Code | Failure |
|---|---|---|
| Authentication | Boot resource server: `spring.security.oauth2.resourceserver.jwt.issuer-uri` + `audiences: gc-api,gc-internal` | 401 (`ProblemSecurityHandlers`, problem+json, `WWW-Authenticate: Bearer`) |
| No active account | Converter → `DisabledException` | 401 |
| Grants source down | `DependencyUnavailableException` → `PrincipalUnavailableException` | **503** (fail closed, never anonymous) |
| Route level | `/internal/**` needs `ROLE_SERVICE`; everything else `ROLE_USER`; health/info public | 403 / 401 |
| Function level | `@RequiresPermission("grant:create")` on application-service methods (`@PreAuthorize("@gcAuthz.has('{value}')")` with `AnnotationTemplateExpressionDefaults`) | **403** `PERMISSION_DENIED` |
| Object level | `PolicyEnforcer.require(principal, perm, ResourceScope, "Case")` | **404** `NOT_FOUND` |
| Lists | `ScopeFilter.forPermission(principal, perm, Columns)` gives `SqlPredicate(sql, params)`; case-service: `CaseAccessScope.predicate(filter, principal, perm, shard)` | Rows never fetched |
| Users in IAM admin APIs | `GrantRules.canAdminister(admin, now, perm, user)` (home country in the admin's scope) | 404 |

Get the caller with `CurrentPrincipal.require()` (application layer). Never read country, shard, role or partner from headers or body to decide access.

**`ScopeFilter.Columns` safety:** fragments are code constants validated against `^[a-zA-Z0-9_.(),:=<>!\s{}]+$`; `{param}` becomes a generated named parameter. Values are always bound (lists via `IN (:p)`).

## 3. Grant administration rules (`iam.domain.GrantRules`, pure and unit-tested)
Order in `checkCreate`:

| Rule | Check |
|---|---|
| `grant.self-grant` | admin ≠ target (also enforced by the DB CHECK `grant_no_self_grant`) |
| `grant.user-not-active` | The target account is active |
| `grant.user-type-mismatch` | `role.allowed_user_type` equals the target's type |
| `grant.scope-home-country` | Grower/partner grants are scoped to exactly the home country |
| `grant.delegation-level` | The admin holds a `grant:create` grant with `roleLevel` ≥ the target role's level |
| `grant.delegation-scope` | Every requested country / territory (directly or via its country) / region (all its countries) is covered by such a grant; `GLOBAL` covers everything |
| `grant.sod-conflict` | No active conflicting role (`iam.sod_conflict`, either order) |
| 409 `DUPLICATE` | The user doesn't already hold the role actively |

- Revocation (`grant.already-revoked`, delegation re-checked with `grant:revoke`) records `revoked_at/by/reason` and never deletes.
- Every success writes `audit.success` + an outbox event (`iam.grant.created` / `iam.grant.revoked`) **in the same transaction**.
- Every rule denial writes `audit.denied` in a **REQUIRES_NEW** transaction, so it survives the rollback.

**Event payloads carry IDs/codes only:** grantId, userId, subject, roleCode, actorUserId.

## 4. Adding things
| Task | How |
|---|---|
| **New permission** | IAM migration: `INSERT INTO iam.permission` + `role_permission` rows (update the expected count in `IamSecurityIT`/`IamSchemaMigrationIT`). Annotate the use case with `@RequiresPermission`. Update `DevSeedPrincipals` if dev roles change |
| **New role** | IAM migration (`role_level`, `scope_kind`, `allowed_user_type`, `pii_clearance`, `privileged`) + SoD pairs if needed |
| **New service** | Provide exactly one `PrincipalResolver` bean (copy `CaseSecurityConfig`). Add the service's Keycloak client to IAM's `gc.security.internal-clients` if it calls `/internal`. Configure `spring.security.oauth2.resourceserver.jwt.*` and the `client.registration.iam` (client credentials) |
| **New scoped table** | Define its `ScopeFilter.Columns` next to the repository (like `CaseAccessScope`) and add rows to the isolation matrix test |

## 5. Hardening (implemented in `GcSecurityConfiguration`)
- **Session and form features:** stateless, CSRF off (no cookies), no form/basic login, no request cache.
- **Response headers:** CSP `default-src 'none'; frame-ancestors 'none'`, `X-Frame-Options: DENY`, `nosniff`, `Referrer-Policy: no-referrer`, `Cache-Control: no-store`.
- **CORS:** exact origins from `gc.security.cors-allowed-origins` (`*` is rejected at startup), no credentials, allowed headers Authorization / Content-Type / If-Match / Idempotency-Key / traceparent.
- **Actuator:** health, info and prometheus only; details hidden.
- **Outbound calls:** the IAM client times out at 1s connect / 2s read; Redis at 500 ms (an outage degrades, it doesn't block).

## 6. Test matrix (mandatory for every endpoint)
**Tooling:**
- `TestJwts` (gc-test-support) mints Keycloak-shaped RS256 tokens and gives a production-equivalent `JwtDecoder` (signature, issuer, expiry, audience). Register it as a `@Primary JwtDecoder` in a `@TestConfiguration`.
- Variants: `.forgedSignature()`, `.expiresAt(past)`, `.audience("x")`, `.issuer("x")`, `TestJwts.service("case-service")`.
- `DevSeedPrincipals` (case-service tests): the dev users exactly as IAM resolves them.

| Caller | Expected |
|---|---|
| Owner / in-scope staff | 2xx |
| Other grower, same country | 404 |
| Staff of another country | 404 |
| Agronomist outside territory, BU or assignment | 404 |
| Retailer without own-raised case or active share | 404 (revoking the share removes access) |
| Valid user missing the permission | 403 |
| Expired grant | Not effective (403/404) |
| No / forged / expired / wrong-audience / wrong-issuer token | 401 |
| Unknown account | 401 · IAM down → 503 |
| Service token on a user API · user token on `/internal` | 401/403 · 403 |

Reference suites:
- `CaseIsolationIT`: 9 users × 5 cases, SQL ≡ policy, revoked share;
- `IamSecurityIT`: authentication, delegation, SoD, audit, outbox, revoke;
- `CaseSecurityIT`: fail-closed, CORS, headers;
- `CachingPrincipalResolverIT`: Redis hit/miss/outage;
- `PolicyEnforcerTest`, `ScopeFilterTest`, `GrantRulesTest`.
