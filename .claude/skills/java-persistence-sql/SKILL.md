---
name: java-persistence-sql
description: GrowerCare persistence rules without an ORM (ADR-013) - normalised Postgres schema design, Flyway migrations with owner/app roles, Spring JdbcClient with named SQL files, record row mappers, shard-qualified SQL via ShardRouter, keyset pagination, optimistic locking, idempotent inserts, scope filters as SQL, transactions, JDBC exception translation, and hot/archive ports ready for Snowflake. Use when designing tables, writing migrations, repositories or queries.
---

# Persistence without an ORM

Read: `docs/backend/01_RDS_SCHEMA.md` (the current schema and why), ADR-013, ADR-002/003/004. For sharded DDL also load `sharded-migration`.

## 1. Schema design rules
1. **3NF by default.**
   - Reference data with attributes or rules → its own table (roles, case types, statuses, transitions).
   - A closed list with no attributes → `CHECK (col IN (…))`.
2. **No repeating groups, arrays or JSON for business data.** Use child tables (`grant_geo_scope`, `case_activity_detail`, `case_comment_attachment`). `jsonb` only for `outbox.payload`.
3. **Put integrity in the database:**
   - named constraints (`<table>_<rule>`), so errors and tests can refer to them;
   - composite FKs for cross-row rules, e.g. `(grower_id, country_code) → ref.grower` means "the grower belongs to this shard's country";
   - `CHECK` constraints for states that must not exist, e.g. revocation fields all set together or all empty.
4. **Every denormalisation is listed and justified** in `01_RDS_SCHEMA.md §5`. Copies of other services' data are read-only replicas in `ref`, with the owner's IDs.
5. **Columns:**
   - `bigint` IDs: DB-generated `next_id()` in shards, identity elsewhere; replicas use the owner's ID;
   - `timestamptz` for instants;
   - `varchar(n)` with real limits;
   - `numeric(p,s)` for quantities and money;
   - `version integer` on every mutable aggregate root.
6. **Privileges:**
   - migrations run as `<db>_owner`; the service runs as `<db>_app` (DML only);
   - each migration grants USAGE on new schemas;
   - revoke what the app must never do (DELETE on case history, writes to `ref`, UPDATE/DELETE on audit);
   - default privileges cover tables created later, so a new `ref` table needs an explicit `REVOKE INSERT, UPDATE, DELETE`.
7. **Partitioned tables:**
   - the PK and UNIQUE constraints must include the partition key (`case_id`);
   - uniqueness that can't include it goes in a separate non-partitioned table (`case_idempotency`) or is guaranteed by construction (`case_no`) and monitored.

## 2. Migrations (Flyway, SQL only)
| Service | Location | Runs as |
|---|---|---|
| identity-access-service | `db/migration` (+ `db/dev-seed` with profile `local`) | Boot Flyway, `spring.flyway.user=gc_iam_owner` |
| case-service | `db/case/common` (schema `ref`), `db/case/dev-seed`, `db/case/shard` (template × every `ref.shard` row) | `ShardedFlywayMigrator` (`gc.migration.*`), owner pool |

- Placeholder `${app_role}` everywhere. Shard templates also get `${shard_schema} ${country_code} ${country_ix} ${shard_ix} ${shard_ix2} ${case_no_cc} ${time_zone}`.
- **Shard migrations must start with `SELECT pg_advisory_xact_lock(7240001);`.** Parallel shard DDL on the shared `ref` tables deadlocks otherwise. `ShardMigrationConventionsTest` enforces this.
- Dev seed lives in a separate location with versions `V1000+` and is never enabled in production.
- Never edit an applied migration; fix forward. Expand → migrate → contract for changes (see `sharded-migration`).

## 3. Repositories: JdbcClient + named SQL
**Named SQL files.** Statements live in `src/main/resources/sql/postgres/<aggregate>.sql`:
```sql
-- name: findById
SELECT c.case_id, c.case_no, c.status_code, c.version, ...
  FROM {shard}.cases c
 WHERE c.case_id = :caseId
```
- `com.growercare.common.sql.NamedSqlRegistry` loads every `classpath*:sql/<dialect>/*.sql` at startup; the bean `namedSqlRegistry` = dialect `postgres`.
- Statement id = `<file>.<name>`, e.g. `grant.findUser` from `sql/postgres/grant.sql`.
- Loading fails fast on duplicate ids, empty statements, SQL before the first `-- name:`, or non-kebab file names.
- Whole-line `--` comments are dropped, so comments between statements never leak into the previous statement.
- `get(id)` for unsharded SQL (it refuses shard-templated statements); `render(id, schema)` for `{shard}` SQL.
- `{shard}` is the **only** template token. It is replaced with a schema name that passed `ShardId.isValidSchemaName`. The rendered SQL is cached per (statement, shard).
- Everything else is a **named bind parameter**. Never concatenate values; sort and filter fields come from an allow-list enum mapped to fixed SQL fragments.

**Repository shape:**
```java
@Repository
class JdbcCaseRepository implements CaseRepositoryPort {
    private final JdbcClient jdbc;
    private final NamedSqlRegistry sql;

    public Optional<CaseRecord> findById(ShardId shard, long caseId) {
        return jdbc.sql(sql.render("case.findById", shard.schemaName()))
                .param("caseId", caseId)
                .query(CaseRowMapper.INSTANCE)
                .optional();
    }
}
```

Reference implementation: `identity-access-service/.../JdbcAccessRepository` (named SQL, record mappers, `groupingBy` assembly of child rows, recursive CTE).

**Row mapping:**
- `RowMapper<Record>` written by hand. Use `rs.getObject(col, Long.class)` for nullable numbers and `rs.getObject(col, OffsetDateTime.class).toInstant()` for `timestamptz`.
- Map enums with `Enum.valueOf`. An unknown value is a programming error and must fail loudly.

## 4. Query patterns
| Need | Pattern |
|---|---|
| Create, returning generated keys | `INSERT … RETURNING case_id, case_no, version` |
| Idempotent create | Insert into `case_idempotency`. On a PK conflict, compare `request_hash`: same hash → return the existing case; different → `ConflictException(IDEMPOTENCY_KEY_REUSED)` |
| Idempotent child insert | `INSERT … ON CONFLICT (case_id, client_msg_id) DO NOTHING RETURNING …`, then select the existing row if nothing was returned |
| Optimistic lock | `UPDATE … SET …, version = version + 1, updated_at = now() WHERE case_id = :id AND version = :expected RETURNING version`. 0 rows → re-read: missing → `NotFoundException`, else `StaleVersionException` (412) |
| Pagination | **Keyset** only: `WHERE (case_id) < :cursor ORDER BY case_id DESC LIMIT :limit + 1`. `case_id` is time-ordered and unique, so it needs no tiebreaker. Cursor = opaque base64 of the last ID. Max page size 100 |
| Scope filter (ABAC) | `ScopeFilter` (gc-security) gives a fixed SQL fragment plus params, e.g. `AND (c.territory_id = ANY(:territories) OR c.grower_id = :ownGrower)`. Arrays are bound as parameters (`= ANY(:ids)`) |
| Hot window only | Lists are bounded by `case_id >= IdCodec.floor(now - window)` so partition pruning applies |
| Background claim | `SELECT … FOR UPDATE OF t SKIP LOCKED LIMIT n`, then `UPDATE … WHERE fired_at IS NULL` (row count 1 = we own it) in the same transaction, as an exactly-once guard. Reference: `SlaTimerPoller` + `maintenance.claimDueTimers` |
| One runner per shard across replicas | `SELECT pg_try_advisory_xact_lock(hashtext('gc.job.<job>.<shard>'))` at the start of the shard's transaction; false ⇒ skip that shard (`MaintenanceJobs.perShard`) |
| Computed states in SQL and Java | Keep one rule set in both places (e.g. SLA state: `SlaRules.state` ≡ the CASE expression in `case.list`) and test both against the same example rows |
| Optional list filters | Keep the statement static: `(CAST(:status AS varchar) IS NULL OR c.status_code = CAST(:status AS varchar))`. The typed CAST lets Postgres infer null parameters |
| Scope in list SQL | A `{scope}` token in the named statement is replaced by the repository with `ScopeFilter` SQL built from `CaseAccessScope.columns(shard)` (code constants). The application passes `(principal, permission)` to the port and never builds SQL (reference: `JdbcCaseRepository.list`) |
| Cross-shard lists (SPEC-CASE-02 D1) | `CaseQueries`: compute target shards from the grants (OWN → own growers' shards; ORG/geo/ASSIGNED → all shards of the covered countries), query each with `limit+1` on virtual threads (semaphore 16), merge by `case_id DESC`, cut to `limit`, cursor = last id |
| Idempotent create with race | Fast path: read `case_idempotency`. Then, in one transaction, insert the case + `INSERT … ON CONFLICT DO NOTHING RETURNING` on the key; no row ⇒ `setRollbackOnly()` and re-read for replay/409 (`CaseCommands.create`) |

## 5. Transactions
- `@Transactional` is used on **application services** only, never on repositories or controllers. Use `readOnly = true` for queries.
- One shard per transaction: a request touches one case, so one shard. Cross-shard work is asynchronous (outbox events).
- Outbox rows are written in the same transaction as the state change (`gc-outbox`). Never call Kafka inside a transaction.
- Keep transactions short: no remote calls (IAM, Redis) inside them. Fetch first, then open the transaction.

## 6. Exception translation (adapter boundary)
Spring turns `SQLException`s into `DataAccessException`. The adapter turns those into domain errors:

| SQLState / Spring type | Meaning | Becomes |
|---|---|---|
| `23505` `DuplicateKeyException` | Unique violation | `ConflictException(DUPLICATE)`, or the idempotency path |
| `23503` `DataIntegrityViolationException` (FK) | Referenced row missing or in the wrong country | `BusinessRuleException("reference.invalid")`, or 404 if it was a path ID |
| `23514` check violation | Invariant breached | `BusinessRuleException` (and fix the validation gap upstream) |
| `40001` / `40P01` serialization / deadlock | Concurrency | Retry once in the use case, else 503 |
| `42501` (Spring: **`BadSqlGrammarException`**) | Missing privilege, i.e. a bug or a grant issue | **500**, log ERROR. Never map it to 400, despite the Spring class name |
| Connection failures | DB down | `DependencyUnavailableException` (503) |

Match on SQLState and the constraint name (`ConstraintViolation.of(e).name()`), not on message text.

> **Partitioned tables report the partition's index name, not the parent constraint.** A duplicate `client_msg_id` on `case_comment` fails with `case_comment_w20261109_case_id_client_msg_id_key`, not `case_comment_client_msg_uq` (verified 2026-11-15). So:
> - for idempotent inserts, **don't rely on catching 23505**: use `INSERT … ON CONFLICT (cols) DO NOTHING RETURNING …`, and select the existing row when nothing is returned;
> - where a unique violation on a partitioned table must be translated, match the SQLState plus the key columns (`ServerErrorMessage.getDetail()` → `Key (case_id, client_msg_id)=…`), never the constraint name.
>
> FK and CHECK constraints keep the parent name (`cases_grower_fk`, `case_activity_status_fields`), so name matching still works for those.

## 7. Ready for Snowflake (hot/cold, ADR-003)
- Reads go through ports: `CaseQueryPort` (Postgres hot) and `ArchiveCaseQueryPort` (Snowflake later). `CaseReadRouter` picks one using the 90-days-after-closure rule.
- Snowflake SQL goes in `sql/snowflake/*.sql` with the **same statement names**. The `NamedSqlRegistry` is keyed by dialect.
- Keep result records dialect-neutral (no Postgres types leaking: `Instant`, `long`, `String`, `BigDecimal`).
- Until the adapter exists, `ArchiveCaseQueryPort` throws `ArchiveNotEnabledException` (501).

## 8. Testing (see `test-strategy`)
- `PostgresTestDatabase.forService("gc_case")` (gc-test-support) gives one container per JVM and the **same owner/app role split** as production. Migrate with the owner, assert with the app.
- Every constraint you add gets a test that violates it and asserts the **constraint name**.
- Privilege tests assert the root cause: `.rootCause().hasMessageContaining("permission denied")`.
- SQL ↔ Java parity tests for duplicated logic (`damm_check_digit` ↔ `Damm`, `id_floor` ↔ `IdCodec.floor`).
