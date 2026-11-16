package com.growercare.shard.migration;

import com.growercare.shard.ShardId;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Predicate;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Runs Flyway for a sharded database (skill: {@code sharded-migration}):
 * <ol>
 *   <li>the <b>common</b> migrations (schema {@code ref}: reference data, shard map, shared SQL functions);</li>
 *   <li>the <b>shard template</b> once per row of {@code ref.shard}, each schema with its own history table and
 *       its own placeholders ({@code shard_schema}, {@code country_ix}, {@code shard_ix}, {@code case_no_cc}, …).</li>
 * </ol>
 * Must run with the schema-owner role. Shards migrate in parallel; any failure fails the whole run and names
 * every failed schema, so a partially migrated fleet is never silently accepted.
 */
public final class ShardedFlywayMigrator {

    private static final Logger log = LoggerFactory.getLogger(ShardedFlywayMigrator.class);

    private static final String SHARDS_SQL = """
            SELECT s.country_code, s.shard_no, s.schema_name, c.country_ix, c.case_no_cc, c.time_zone
              FROM ref.shard s
              JOIN ref.country c ON c.country_code = s.country_code
             WHERE c.active
             ORDER BY s.country_code, s.shard_no
            """;

    /**
     * @param include    which shards of {@code ref.shard} to migrate. Production: all ({@link #ALL_SHARDS}).
     *                   Tests pass a small subset (the shards their fixtures route to) to keep the test database light.
     * @param outOfOrder apply common migrations numbered below already-applied ones. Only for databases that carry
     *                   the dev seed (V1000+), so a later common V5 still applies locally (SPEC-PLAT-04 R63).
     *                   Production has no seed and keeps strict ordering ({@code false}).
     */
    public record Settings(
            List<String> commonLocations,
            List<String> shardLocations,
            Map<String, String> placeholders,
            int parallelism,
            Predicate<ShardId> include,
            boolean outOfOrder) {

        public static final Predicate<ShardId> ALL_SHARDS = shard -> true;

        public Settings {
            commonLocations = List.copyOf(commonLocations);
            shardLocations = List.copyOf(shardLocations);
            placeholders = Map.copyOf(placeholders);
            Objects.requireNonNull(include, "include");
            if (parallelism < 1) {
                throw new IllegalArgumentException("parallelism must be >= 1");
            }
        }

        /** Every shard of every active country (production), strict ordering. */
        public Settings(List<String> commonLocations, List<String> shardLocations, Map<String, String> placeholders, int parallelism) {
            this(commonLocations, shardLocations, placeholders, parallelism, ALL_SHARDS, false);
        }

        /** A subset of shards, strict ordering. */
        public Settings(List<String> commonLocations, List<String> shardLocations, Map<String, String> placeholders,
                        int parallelism, Predicate<ShardId> include) {
            this(commonLocations, shardLocations, placeholders, parallelism, include, false);
        }
    }

    /** One shard to migrate, as read from {@code ref.shard}. */
    public record ShardTarget(ShardId shardId, String schema, int countryIx, int caseNoCc, String timeZone) { }

    public record Report(int commonMigrations, int shardCount, int shardMigrations) { }

    private final DataSource ownerDataSource;
    private final Settings settings;

    public ShardedFlywayMigrator(DataSource ownerDataSource, Settings settings) {
        this.ownerDataSource = ownerDataSource;
        this.settings = settings;
    }

    public Report migrate() {
        int common = flyway("ref", settings.commonLocations(), settings.placeholders()).migrate().migrationsExecuted;
        var shards = loadShards();
        int executed = migrateShards(shards);
        log.info("Sharded migration finished: {} common migrations, {} shards, {} shard migrations",
                common, shards.size(), executed);
        return new Report(common, shards.size(), executed);
    }

    List<ShardTarget> loadShards() {
        return JdbcClient.create(ownerDataSource).sql(SHARDS_SQL)
                .query((rs, n) -> {
                    var shardId = new ShardId(rs.getString("country_code"), rs.getInt("shard_no"));
                    var schema = rs.getString("schema_name");
                    if (!schema.equals(shardId.schemaName())) {
                        throw new IllegalStateException("ref.shard schema_name mismatch for " + shardId);
                    }
                    return new ShardTarget(shardId, schema, rs.getInt("country_ix"), rs.getInt("case_no_cc"),
                            rs.getString("time_zone"));
                })
                .list()
                .stream()
                .filter(target -> settings.include().test(target.shardId()))
                .toList();
    }

    private int migrateShards(List<ShardTarget> shards) {
        try (var pool = Executors.newFixedThreadPool(settings.parallelism(), Thread.ofVirtual().name("shard-migrate-", 0).factory())) {
            var futures = shards.stream()
                    .map(target -> Map.entry(target, pool.submit(() -> migrateShard(target))))
                    .toList();

            var failures = new ArrayList<String>();
            int executed = 0;
            for (Map.Entry<ShardTarget, Future<Integer>> entry : futures) {
                try {
                    executed += entry.getValue().get();
                } catch (ExecutionException e) {
                    log.error("Migration failed for shard {}", entry.getKey().schema(), e.getCause());
                    failures.add(entry.getKey().schema());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new ShardMigrationException("Interrupted while migrating shards", List.of(), e);
                }
            }
            if (!failures.isEmpty()) {
                failures.sort(Comparator.naturalOrder());
                throw new ShardMigrationException("Shard migration failed for " + failures.size() + " schema(s)", failures, null);
            }
            return executed;
        }
    }

    private int migrateShard(ShardTarget target) {
        var placeholders = new HashMap<>(settings.placeholders());
        placeholders.put("shard_schema", target.schema());
        placeholders.put("country_code", target.shardId().countryCode());
        placeholders.put("country_ix", Integer.toString(target.countryIx()));
        placeholders.put("shard_ix", Integer.toString(target.shardId().shardNo()));
        placeholders.put("shard_ix2", "%02d".formatted(target.shardId().shardNo()));
        placeholders.put("case_no_cc", Integer.toString(target.caseNoCc()));
        placeholders.put("time_zone", target.timeZone());
        var flyway = flyway(target.schema(), settings.shardLocations(), placeholders);

        // Every shard's DDL takes locks on the shared ref.* tables (FKs, partition triggers). Parallel shards can
        // deadlock (SQLState 40P01). PostgreSQL DDL is transactional, so the victim's migration rolled back
        // completely and is safe to retry. Anything else fails immediately.
        for (int attempt = 1; ; attempt++) {
            try {
                return flyway.migrate().migrationsExecuted;
            } catch (RuntimeException e) {
                if (!isDeadlock(e) || attempt >= MAX_DEADLOCK_ATTEMPTS) {
                    throw e;
                }
                long backoffMs = 50L * attempt + ThreadLocalRandom.current().nextLong(100);
                log.debug("Deadlock migrating {} (attempt {}), retrying in {} ms", target.schema(), attempt, backoffMs);
                sleep(backoffMs);
            }
        }
    }

    private static final int MAX_DEADLOCK_ATTEMPTS = 10;
    private static final String DEADLOCK_SQLSTATE = "40P01";

    static boolean isDeadlock(Throwable error) {
        return Stream.iterate(error, Objects::nonNull, Throwable::getCause)
                .limit(20)
                .anyMatch(t -> t instanceof SQLException sql && DEADLOCK_SQLSTATE.equals(sql.getSQLState()));
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ShardMigrationException("Interrupted during deadlock backoff", List.of(), e);
        }
    }

    private Flyway flyway(String schema, List<String> locations, Map<String, String> placeholders) {
        return Flyway.configure()
                .dataSource(ownerDataSource)
                .schemas(schema)
                .defaultSchema(schema)
                .createSchemas(true)
                .locations(locations.toArray(String[]::new))
                .placeholders(placeholders)
                .failOnMissingLocations(true)
                .validateMigrationNaming(true)
                .cleanDisabled(true)
                .outOfOrder(settings.outOfOrder())
                .load();
    }
}
