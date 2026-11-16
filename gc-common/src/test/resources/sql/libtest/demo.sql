-- Demo statements for NamedSqlRegistryTest (dialect "libtest").

-- name: plain
SELECT 1;

-- A whole-line comment between statements is dropped.
-- name: sharded
SELECT * FROM {shard}.cases
-- comment inside a statement is dropped too
 WHERE case_id = :caseId
