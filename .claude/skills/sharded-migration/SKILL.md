---
name: sharded-migration
description: Write and roll out Flyway database migrations for GrowerCare's sharded PostgreSQL schemas (64 logical shards per country, ~1,280 schemas) using expand/migrate/contract, per-shard placeholders and the migration orchestrator. Use for any schema change to case-service or grower-service tables, partitions, or the per-shard ID functions.
---

# Sharded migrations

Background: `docs/syngenta/04_ID_GENERATION_DESIGN.md` (per-shard `next_id()`, `next_case_no()`), `05_CASE_COMMENTS_DESIGN.md §6` (weekly partitions, carryover), ADR-002, ADR-004.

## Layout (as implemented in growercare-case-service)
```
src/main/resources/db/case/
├─ common/    V<n>__*.sql   # schema `ref`: replicas, workflow tables, shard map (ref.shard), damm_check_digit, id_floor
├─ dev-seed/  V1000+__*.sql # local/test only (gc.migration.dev-seed=true)
└─ shard/     V<n>__*.sql   # template applied to EVERY row of ref.shard (in_s00..in_s63, br_s00..br_s63)
```
Runner: `com.growercare.shard.migration.ShardedFlywayMigrator` (gc-shard-routing).
1. It migrates `common` first.
2. It then reads `ref.shard JOIN ref.country WHERE active` and migrates each shard schema with its own `flyway_schema_history`, in parallel (`gc.migration.parallelism`).
3. Any failure fails the whole run and names every failed schema.

It runs as the **owner role** (`gc.migration.owner-user`), never the app role. In production, set `GC_MIGRATE=false` on the service and run migrations in a pre-deploy Job.

Placeholders in `shard/` migrations:

| Placeholder | Example |
|---|---|
| `${shard_schema}` | `in_s27` |
| `${country_code}` | `IN` |
| `${country_ix}` | `46` |
| `${shard_ix}` | `27` |
| `${shard_ix2}` | `'27'`, zero-padded |
| `${case_no_cc}` | `71` |
| `${time_zone}` | `Asia/Kolkata` |
| `${app_role}` | — |

Never hard-code a schema name; `ShardMigrationConventionsTest` fails the build if you do.

**Mandatory first statement of every shard migration:** `SELECT pg_advisory_xact_lock(7240001);`
- Every shard's DDL locks the shared `ref.*` tables (FKs, partition triggers). 128 parallel migrations deadlocked (40P01) without this lock; seen and fixed on 2026-11-15.
- The lock is transaction-scoped and released at commit. The migrator also retries on deadlock as a safety net.

**Activating a new country:** add a row to `ref.country` and its 64 `ref.shard` rows in a `common` migration (the shard map seed uses `generate_series`). The next run builds the 64 schemas.

## Rules
1. **Expand → migrate → contract.** Each step is a separate release. The app must work with both old and new schema during rollout (mixed versions across 1,280 schemas for hours).
   - Add a column: nullable or with a constant default (no table rewrite).
   - Rename: add new column → dual-write → backfill in batches → switch reads → drop old (later release).
   - Never `ALTER TYPE` on large tables in place; never add `NOT NULL` without a prior `CHECK … NOT VALID` + `VALIDATE`.
2. **Indexes:** `CREATE INDEX CONCURRENTLY` on the partitioned parent's partitions individually (Flyway: separate migration, `-- flyway:executeInTransaction=false`).
3. **Partitions:**
   - Weekly partitions (Monday 00:00 UTC, bounds `ref.id_floor(week)`) are created by `${shard_schema}.ensure_week_partitions(weeks_back, weeks_ahead)`.
   - That function is `SECURITY DEFINER`, so a daily job may call it as the app role. Migration V3 covers -1…+8 weeks.
   - **A new case-keyed child table must be added to the function's `parents` array** (`CREATE OR REPLACE FUNCTION` in the same migration, as in shard V4 for `case_timer`), partitioned `BY RANGE (case_id)`, with a `_default` partition. Then call `ensure_week_partitions(1, 8)` in that migration so the existing weeks get the new table too.
   - Changing a CHECK on a partitioned table: `DROP CONSTRAINT` + `ADD CONSTRAINT` on the parent propagates to every partition (shard V4 `case_activity_type`).
   - `*_default` partitions must stay empty: a non-empty default blocks creating the matching week.
4. **ID functions:** changing `next_id()` / `next_case_no()` requires an ADR. Bit layout and constants are frozen.
5. **CDC impact:** any column add/rename must also be added to `gc-contracts` (Avro, BACKWARD compatible) and to Snowflake CURATED via `gc-data-platform` *before* the contract step drops anything.
6. **Lock safety:** set `lock_timeout = '3s'` and `statement_timeout` at the top of every shard migration; on timeout the orchestrator retries that shard later.
7. **Backfills:** in batches of ≤ 5,000 rows by primary-key range, throttled, outside migrations (application job), and idempotent.

## Rollout (orchestrator)
1. Run on `staging` shards, then verify schema checksums are identical across all shards (`flyway info` per schema).
2. Production waves: canary shard (`*_s00` of the smallest country) → 10% shards → 100%, per cell.
3. Halt on first error; the orchestrator reports per-shard state. Fix forward with a new migration, never edit applied ones.
4. CI gate: all shard schemas must report the same latest version before the app's contract-step release is allowed.

## Test
- `PostgresTestDatabase.forService("gc_case")` (gc-test-support) gives Postgres 17 with the owner/app role split. Run `ShardedFlywayMigrator` against it with `db/case/common` + `db/case/dev-seed` + `db/case/shard` (all 128 schemas).
- `CaseSchemaMigrationIT` is the reference. It covers:
  - ID decode;
  - Damm/id_floor SQL<->Java parity;
  - the current-week partition;
  - composite-FK integrity (with constraint names);
  - least privilege;
  - workflow graph reachability;
  - an idempotent re-run.
- Static rules: `ShardMigrationConventionsTest` (lock first; no concrete schema names).
- For expand/contract steps: run the *previous* app version's repository tests against the new schema (backward-compatibility test).

## Lessons (SPEC-PLAT-04)
- **Owner pool size:** each shard's Flyway run holds about 2 connections (schema history + migration) while it waits on the shard advisory lock 7240001. A pool of `parallelism + 1` **deadlocks**: the lock holder sits "idle in transaction" waiting for a connection held by the lock waiters. `CaseSchemaConfig` uses `parallelism * 3 + 1` and a 60 s connection timeout. Tests don't catch this: `PostgresTestDatabase` data sources are unpooled.
- **Dev seed vs later common migrations:** the dev seed is numbered V1000+. With the seed on (local profile), the migrator runs Flyway with `outOfOrder(true)` (`Settings.outOfOrder`, IAM `spring.flyway.out-of-order` in `application-local.yml`), so a common V5 added later still applies. Production never has the seed and keeps strict ordering.
- **New outbox-like tables that Debezium must stream:** `ALTER PUBLICATION gc_outbox ADD TABLE …` plus `GRANT SELECT … TO ${cdc_role}` in the same migration. Every migrator caller passes the `cdc_role` placeholder.
