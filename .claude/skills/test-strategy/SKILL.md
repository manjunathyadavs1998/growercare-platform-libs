---
name: test-strategy
description: Plan and write tests for GrowerCare following the project's test pyramid - JUnit unit tests, ArchUnit, Testcontainers integration (sharded Postgres, Kafka, Redis), authorization/isolation matrices, Pact contract tests, data-pipeline reconciliation tests, Playwright E2E, k6 load and chaos tests - with coverage gates. Use when writing or reviewing tests, defining acceptance tests for a requirement, or preparing a release gate.
---

# Test strategy

## Pyramid & gates
| Layer | Tooling | Scope | Gate |
|---|---|---|---|
| Unit | JUnit 5, AssertJ, Mockito | Domain rules, state machines, packs, ID codec, policy evaluation | ≥ 80% line coverage on `domain` + `application` (JaCoCo, per service) |
| Architecture | ArchUnit | Hexagonal rules, no country literals outside packs, no `KafkaTemplate` in business code | Must pass |
| Integration | **Testcontainers** (Postgres w/ shard schemas, Kafka, Redis), REST Assured | Repositories, outbox→event, inbox idempotency, migrations, API with security | Must pass |
| Contract | Pact (consumer-driven) + Avro compatibility | Service↔service and frontend↔backend | Provider verification green |
| Data pipeline | Testcontainers Postgres + Kafka + Debezium (connect container); Snowflake via staging account in nightly | CDC completeness, MERGE idempotency, purge-marker / partition verification | Nightly green before release |
| E2E | Playwright against a staging cell | Role journeys, isolation, offline sync | Must pass for release |
| Load | k6 (`perf/` per service repository, planned) | 2× stage peak, soak 2 h | SLOs: p95 read < 250 ms, write < 500 ms, error < 0.1% |
| Resilience | Toxiproxy (integration), chaos experiments in staging | DB failover, broker loss, Redis loss, slow ERP | SLOs held / graceful degradation |

## Must-have test suites (from the requirements)
1. **Isolation matrix** (ACC-*): for every endpoint, call it as
   - an allowed user
   - a user of another country
   - a user of another partner org
   - a user with an expired grant
   - a grower calling for another grower's case

   Expected results: 200 / 404 / 404 / 401-403 / 404. Generate the matrix from the OpenAPI spec so new endpoints are covered automatically.
2. **Optimistic locking:** two concurrent PATCHes with the same `If-Match` → one 200, one 412.
3. **IDs:**
   - `next_id()` uniqueness under 64 concurrent sessions per shard
   - decode round-trip
   - Damm detects all single-digit errors and adjacent transpositions
   - `case_no` uniqueness per (day, shard)
4. **Pagination/filters:** keyset correctness with ties on timestamps; no duplicates/skips across pages while new rows are inserted.
5. **Outbox/inbox:** event emitted iff the transaction commits; duplicate delivery processed once; DLQ after retries.
6. **Comments:**
   - posting doesn't change the case `version`
   - visibility filtering per role
   - `client_msg_id` idempotency
7. **Archive & purge:**
   - partition digest match → drop; mismatch → quarantine
   - archived case is still resolvable via stub
   - queries crossing 90 days go to the archive
8. **Compliance packs:** rule applies for the pack's country and **not** for others.
9. **E2E (Playwright), per role:**
   - grower raises a complaint
   - care agent triages
   - agronomist completes the visit (offline then sync)
   - finance maker/checker approves
   - activity stream shows each step
   - cross-country access fails

## Test-writing conventions
- **Spec-driven and parameterized by default** (skill `spec-driven-development`):
  - tests are derived from a spec's example table (`docs/specs/<service>/*.md`);
  - each row is one `@ParameterizedTest` case, named by its spec ID via `Arguments.argumentSet("IAM-E08 …", …)`;
  - use `@MethodSource` for object rows, `@CsvSource` for plain values, `@EnumSource`/`@ValueSource` for enumerations;
  - a plain `@Test` is allowed only for one-off wiring checks (context, health) or a multi-step scenario that is itself one spec example (e.g. `iamE18SegregationOfDutiesThenRevocation`).
- **One assertion path per parameterized method;** rows differ only in data, and are independent of each other.
- Test names describe the behaviour in camelCase (`growerMustBelongToTheShardCountry`, `appRoleCannotRunDdl`).
- Builders/fixtures in gc-test-support; no shared mutable fixtures between tests.
- Testcontainers: use `com.growercare.testsupport.PostgresTestDatabase.forService("<db>")`. It's one container per JVM and recreates the production **owner/app role split**: migrate with `ownerDataSource()`, assert with `appDataSource()`. Sharded services migrate all shard schemas through `ShardedFlywayMigrator`.
- Naming: `*Test` = unit (Surefire, no Docker); `*IT` = integration (Failsafe, needs Docker). Run with `mvn -B verify` in the repository.
- DB assertions: assert the **constraint name** for integrity violations. For privilege denials assert `.rootCause().hasMessageContaining("permission denied")`, because Spring wraps SQLState 42501 as `BadSqlGrammarException`.
- **The case DB is light and shared per JVM:** `CaseTestDatabase.migrated()` migrates the common schema plus **only** the shards in `TEST_SHARDS`, about 10 instead of 128:
  - the shards the dev-seed growers route to, via `ShardRouter`;
  - the fixed shards that schema tests name.

  It runs once per JVM through `ShardedFlywayMigrator.Settings.include`. A test that writes to a new shard adds it to `FIXED_SHARDS`, or adds the grower to `SEED_GROWERS`. Never run the full 128 in tests; that's for startup and local runs (memory-heavy on laptops).
- **Security fixtures:**
  - `TestJwts` (gc-test-support): RS256 tokens shaped like Keycloak's, with the dev-seed subjects as constants; `TestJwts.decoder(audiences)` gives a production-equivalent `JwtDecoder`. Register it as `@Primary` in a `@TestConfiguration`.
  - `DevSeedPrincipals` (case-service): the dev users as IAM resolves them; use it to stub `PrincipalResolver`.
  - `RedisTestContainer` (gc-test-support): password-protected Redis; `pause()`/`unpause()` simulate an outage.
- **Stop locally running services before `mvn verify`** (`docker compose down` in the service repository). On Windows they lock `target/*.jar`, and the build hangs silently with an empty log (seen 2026-11-15).
- **Counting partitions:** `pg_inherits` also links index partitions. Count only `pg_class.relkind = 'r'`.
- **`@Nested` gotcha (Spring Framework 7):** a nested static `@TestConfiguration` is **not** picked up for `@Nested` test classes. Always add `@Import(MyIT.Stubs.class)` on the outer class. Otherwise the real beans load silently; e.g. the Keycloak `JwtDecoder` rejected every test token with 401 (seen 2026-11-15).
- **Spring Boot 4 test packages:** `org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc`, `org.springframework.test.context.bean.override.mockito.MockitoBean`.
- **Isolation matrix pattern:** insert fixtures into real shards, then assert each user's visible set through **both** the SQL `ScopeFilter` and `PolicyEnforcer` (they must agree). See `CaseIsolationIT`.
- **Coverage gate (SPEC-PLAT-03):**
  - **Measurement:** the merged JaCoCo report (unit + IT data, `*Application` excluded; services: `target/site/jacoco-merged/jacoco.xml`, libs: `coverage/target/site/jacoco-aggregate/jacoco.xml`) must show **overall line coverage ≥ 80%**. `scripts/coverage_gate.py` fails the required `verify` check below that.
  - **Local check:** after `mvn -B verify`, run `python scripts/coverage_gate.py <report above>`.
  - **SonarQube Cloud** also requires ≥ 80% on new code. Write the spec's parameterized rows; don't chase coverage with assertion-free tests.
- **Claude review gate (SPEC-PLAT-03):** every PR gets a structured review. `scripts/review_gate.py` fails the required `claude-review` check on Critical/High findings.
- **Quality gates (SPEC-PLAT-01):**
  - `ArchitectureTest`: every service, parameterized over `HexagonalRules.rows()` (domain free of frameworks; application ↛ adapters; adapters.in ↛ adapters.out; no ORM/Kafka; no field injection, `System.out` or JUL; controllers in `adapters.in.web` without SQL). It caught a real violation on its first run: an IAM controller using the JDBC adapter.
  - `*OpenApiContractIT`: every spec endpoint is documented; exports the contract.
  - `local-dev/smoke.sh`: the real stack end to end, with Keycloak tokens.
- **Reference suites:**
  - `CaseSchemaMigrationIT`, `IamSchemaMigrationIT`, `ShardMigrationConventionsTest`;
  - `IamSecurityIT`, `CaseSecurityIT`, `CaseIsolationIT`, `CachingPrincipalResolverIT`;
  - unit tests: `GrantRulesTest`, `PolicyEnforcerTest`, `ScopeFilterTest`, `IamPrincipalClientTest`, and those in `gc-shard-routing`.
- No sleeps; use Awaitility for async (Kafka/outbox).
- Assert on behaviour and outputs (HTTP status + Problem Details `type`, persisted state, emitted event), not on internal calls, unless testing an adapter boundary.
- Every bug fix starts with a failing test.

## Reporting
CI uploads JUnit XML + JaCoCo reports as artifacts and posts a coverage summary on the PR. Playwright traces are uploaded on failure.
