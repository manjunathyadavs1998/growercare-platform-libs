-- Fails only on shard 1 (division by zero), so the report must name exactly the shard-1 schemas.
SELECT 1 / (${shard_ix} - 1);
