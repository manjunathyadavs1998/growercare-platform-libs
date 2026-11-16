---
name: spec-driven-development
description: Mandatory GrowerCare workflow for every backend feature or change - write the spec first (docs/specs/<service>/<feature>.md with numbered rules, example tables, API contract and errors), get it agreed, then derive PARAMETERIZED tests whose rows are the spec's examples (spec IDs in test names), then implement until green, then keep spec, tests and skills in sync. Use before writing any production code, when adding/changing endpoints, rules or permissions, and when reviewing whether a change is properly specified and tested.
---

# Spec-driven development (always)

**Order is fixed: Spec → Tests (parameterized, failing) → Code → Green → Sync docs.** No production code without a spec rule that demands it, and no spec rule without a test row that proves it.

## 1. Write the spec first
**Location:** `docs/specs/<service>/<NN>-<feature>.md`, e.g. `docs/specs/iam/01-grant-administration.md`.

**Template** (copy it exactly; an existing example is `docs/specs/iam/01-grant-administration.md`):
```markdown
# SPEC-<SVC>-<NN>: <Feature>
- **Requirements:** ACC-2, ACC-6 …   - **Status:** Draft | Agreed | Implemented   - **Owner:** <service>

## 1. Behaviour (rules)
| Rule | Given | When | Then |
|---|---|---|---|
| <SVC>-R1 | admin with grant:create for IN | grants CARE_AGENT in BR | 422 `grant.delegation-scope`, DENIED audit row |

## 2. Examples (each row becomes ONE parameterized test case)
| Example | Rule | Actor | Input | Expected status | Expected code / rule |
|---|---|---|---|---|---|
| <SVC>-E01 | R1 | admin.in | user 102, CARE_AGENT, BR | 422 | grant.delegation-scope |

## 3. API contract
Method, path, request JSON (IDs as strings), response JSON, every error status → Problem `code`/`rule`.

## 4. Data & events
Tables touched (link to docs/backend/01_RDS_SCHEMA.md), outbox event type + payload (no PII), audit rows.

## 5. Security
Permission (403), object scope (404), isolation matrix rows.

## 6. Traceability
| Rule / Example | Test |
|---|---|
| R1 / E01–E03 | `GrantRulesTest#rule`, `IamSecurityIT$GrantAdministration#deniedRequests` |
```

**Rules for writing specs:**
- Every rule has a stable ID, and **IDs are never reused**. A changed rule gets a new ID; the old one is marked *Superseded*.
- Examples cover:
  - the happy path;
  - **every** error status the endpoint can return;
  - boundaries (empty or maximum inputs, expiry exactly at now);
  - and, for security, the isolation matrix: own / other user / other country / other partner / missing permission / expired / no token.
- Expected results are concrete (status + `code` + `rule` + persisted state or event), never "should work".
- Status moves Draft → Agreed (user approval for new features) → Implemented (all rows green).

## 2. Derive tests from the spec: always parameterized
Spec example tables map 1:1 onto JUnit 5 parameterized tests. Single-case `@Test` methods are only allowed for one-off wiring checks (a context loads, health is UP).

| Spec shape | JUnit source |
|---|---|
| Rows of simple values | `@CsvSource` with `textBlock` (header comment = spec columns) |
| Rows with objects (principals, commands, JSON) | `@MethodSource` returning `Stream<Arguments>`, built with `Arguments.argumentSet("<SVC>-E07 …", …)` so the **spec ID is the test name** |
| Enumerations (all roles, all statuses) | `@EnumSource` / `@ValueSource` |
| Matrix (users × resources) | `@MethodSource` of the cartesian rows, with the expected outcome in the row |

```java
static Stream<Arguments> deniedGrants() {
    return Stream.of(
        argumentSet("IAM-E04 level above own",  ADMIN_IN, "102", "GLOBAL_PLATFORM_ADMIN", "IN", 422, "grant.delegation-level"),
        argumentSet("IAM-E05 outside own scope", ADMIN_IN, "102", "CARE_AGENT",            "BR", 422, "grant.delegation-scope"));
}

@ParameterizedTest
@MethodSource("deniedGrants")
void deniedGrantsAreRejectedAndAudited(UUID actor, String userId, String role, String country, int status, String rule) { … }
```
- **One assertion path per test method;** the rows vary only data. If rows need different assertions, the rule is different, so write a separate method and spec rule.
- **Rows are independent:** no row may depend on state left by another. Use distinct fixture users/IDs or restore state (`try/finally`).
- **Name rows with spec IDs** (`argumentSet("IAM-E05 …", …)` or `@ParameterizedTest(name = "{0}")`), so a red build points straight at the spec line.
- **Unit level:** pure rules (`GrantRules`, `PolicyEnforcer`, `ScopeFilter`, state machines) get the full example table. **IT level:** the same table over HTTP/SQL for statuses, persistence, events and audit.
- **Write the tests first and run them red** (compile errors count as red), then implement.

## 3. Implement
Follow `java-backend-standards`, `java-persistence-sql`, `java-security-rbac` and `java-exception-handling`. Implement only what a rule demands. If you discover a missing case, **add it to the spec first** (new rule/example ID), then the test row, then the code.

## 4. Done means
- [ ] Spec status = Implemented; every rule has at least one example row, every example row a test row, and the traceability table is filled in
- [ ] `mvn -B verify` green, with the real counts reported
- [ ] OpenAPI/contract, `docs/backend/01_RDS_SCHEMA.md`, service doc and affected skills updated in the same change
- [ ] No test asserts behaviour that is not in the spec (or the spec is updated)

## 5. Changing behaviour later
1. Update the spec: add the new rule/example IDs, mark replaced ones *Superseded (by R7)*.
2. Change or add test rows and watch them fail.
3. Change the code until green.
4. Update traceability.

Never edit a test to match new code without the spec saying so.
