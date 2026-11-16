package com.growercare.shard.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.growercare.shard.CountryRegistryLoader;
import com.growercare.shard.ShardId;
import com.growercare.testsupport.PostgresTestDatabase;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The sharded migrator (SPEC-PLAT-01 D3, ADR-004) against PostgreSQL: common schema once, then every shard of
 * {@code ref.shard} with its placeholders; per-shard failures are collected and reported together.
 */
class ShardedFlywayMigratorIT {

    static ShardedFlywayMigrator.Settings settings(String shardLocation, java.util.function.Predicate<ShardId> include) {
        return new ShardedFlywayMigrator.Settings(List.of("classpath:db/libtest/common"), List.of(shardLocation),
                Map.of("app_role", "gc_app"), 2, include);
    }

    @Test
    void migratesEveryActiveShardWithItsPlaceholdersAndIsIdempotent() {
        var db = PostgresTestDatabase.forService("gc_shard_all");
        var migrator = new ShardedFlywayMigrator(db.ownerDataSource(),
                settings("classpath:db/libtest/shard", ShardedFlywayMigrator.Settings.ALL_SHARDS));

        var first = migrator.migrate();
        var second = migrator.migrate();

        assertThat(first).isEqualTo(new ShardedFlywayMigrator.Report(1, 4, 4));
        assertThat(second).isEqualTo(new ShardedFlywayMigrator.Report(0, 4, 0));          // nothing left to do
        var row = JdbcClient.create(db.ownerDataSource())
                .sql("INSERT INTO br_s01.thing (id) VALUES (1) RETURNING country, ix, shard_ix, shard_ix2, cc, tz, app_role")
                .query().singleRow();
        assertThat(row).containsEntry("country", "BR").containsEntry("ix", 8).containsEntry("shard_ix", 1)
                .containsEntry("shard_ix2", "01").containsEntry("cc", 31).containsEntry("tz", "America/Sao_Paulo")
                .containsEntry("app_role", "gc_app");
    }

    @Test
    void includePredicateKeepsTheTestDatabaseLight() {
        var db = PostgresTestDatabase.forService("gc_shard_some");
        var report = new ShardedFlywayMigrator(db.ownerDataSource(),
                settings("classpath:db/libtest/shard", shard -> shard.countryCode().equals("IN"))).migrate();
        assertThat(report.shardCount()).isEqualTo(2);
        assertThat(JdbcClient.create(db.ownerDataSource())
                .sql("SELECT count(*) FROM information_schema.schemata WHERE schema_name LIKE 'br_s%'").query(Integer.class).single())
                .isZero();
    }

    @Test
    void failingShardsAreReportedTogether() {
        var db = PostgresTestDatabase.forService("gc_shard_fail");
        var migrator = new ShardedFlywayMigrator(db.ownerDataSource(),
                settings("classpath:db/libtest/shard-failing", ShardedFlywayMigrator.Settings.ALL_SHARDS));

        assertThatThrownBy(migrator::migrate)
                .isInstanceOf(ShardMigrationException.class)
                .hasMessageContaining("2 schema(s)")
                .satisfies(e -> assertThat(((ShardMigrationException) e).failedSchemas()).containsExactly("br_s01", "in_s01"));
    }

    @Test
    void countryRegistryLoadsActiveCountriesOnly() {
        var db = PostgresTestDatabase.forService("gc_shard_registry");                 // own database: rows are independent
        new ShardedFlywayMigrator(db.ownerDataSource(), settings("classpath:db/libtest/shard", ShardedFlywayMigrator.Settings.ALL_SHARDS)).migrate();
        var registry = CountryRegistryLoader.load(JdbcClient.create(db.ownerDataSource()));
        assertThat(registry.byCode("IN")).isPresent();
        assertThat(registry.byCode("KE")).isEmpty();                                       // inactive
    }

    @Test
    void noActiveCountryFailsStartup() {
        var db = PostgresTestDatabase.forService("gc_shard_none");
        var owner = JdbcClient.create(db.ownerDataSource());
        owner.sql("CREATE SCHEMA ref").update();
        owner.sql("CREATE TABLE ref.country (country_code char(2), country_ix int, case_no_cc int, region_code text, time_zone text, active boolean)").update();
        assertThatThrownBy(() -> CountryRegistryLoader.load(owner)).isInstanceOf(IllegalStateException.class);
    }

    @ParameterizedTest(name = "parallelism {0} is rejected")
    @ValueSource(ints = {0, -1})
    void parallelismMustBePositive(int parallelism) {
        assertThatThrownBy(() -> new ShardedFlywayMigrator.Settings(List.of(), List.of(), Map.of(), parallelism))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void deadlocksAreRecognisedThroughTheCauseChain() {
        var deadlock = new RuntimeException("flyway failed", new SQLException("deadlock detected", "40P01"));
        assertThat(ShardedFlywayMigrator.isDeadlock(deadlock)).isTrue();
        assertThat(ShardedFlywayMigrator.isDeadlock(new RuntimeException(new SQLException("syntax", "42601")))).isFalse();
    }
}
