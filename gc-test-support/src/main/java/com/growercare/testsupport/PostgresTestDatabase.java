package com.growercare.testsupport;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * One PostgreSQL 17 container per test JVM, with a database per service created exactly like
 * {@code local-dev/postgres/01-init.sh}: an <b>owner</b> role (DDL, migrations) and an <b>app</b> role (DML only).
 * Tests therefore run under the same privilege split as production and catch missing GRANTs.
 *
 * <pre>{@code
 * var db = PostgresTestDatabase.forService("gc_case");
 * migrate(db.ownerDataSource()); query(db.appDataSource());
 * }</pre>
 */
public final class PostgresTestDatabase {

    public static final String IMAGE = "postgres:17.6-alpine";

    private static final PostgreSQLContainer CONTAINER = new PostgreSQLContainer(IMAGE)
            .withCommand("postgres", "-c", "max_connections=100", "-c", "shared_buffers=128MB", "-c", "fsync=off",
                    "-c", "synchronous_commit=off", "-c", "full_page_writes=off");
    private static final Map<String, PostgresTestDatabase> DATABASES = new ConcurrentHashMap<>();

    private final String database;

    private PostgresTestDatabase(String database) {
        this.database = database;
    }

    /** @param database e.g. {@code gc_case}; roles {@code gc_case_owner} / {@code gc_case_app} are created. */
    public static PostgresTestDatabase forService(String database) {
        if (!database.matches("^[a-z][a-z_]{1,30}$")) {
            throw new IllegalArgumentException("Invalid database name " + database);
        }
        synchronized (CONTAINER) {
            if (!CONTAINER.isRunning()) {
                CONTAINER.start();
            }
        }
        return DATABASES.computeIfAbsent(database, PostgresTestDatabase::create);
    }

    private static PostgresTestDatabase create(String db) {
        var owner = db + "_owner";
        var app = db + "_app";
        try (var con = DriverManager.getConnection(CONTAINER.getJdbcUrl(), CONTAINER.getUsername(), CONTAINER.getPassword());
             var st = con.createStatement()) {
            st.execute("CREATE ROLE " + owner + " LOGIN PASSWORD '" + owner + "'");
            st.execute("CREATE ROLE " + app + " LOGIN PASSWORD '" + app + "'");
            createCdcRoleOnce(st);
            st.execute("CREATE DATABASE " + db + " OWNER " + owner);
            st.execute("REVOKE ALL ON DATABASE " + db + " FROM PUBLIC");
            st.execute("GRANT CONNECT ON DATABASE " + db + " TO " + app);
            st.execute("GRANT CONNECT ON DATABASE " + db + " TO " + CDC_ROLE);
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot create test database " + db, e);
        }
        try (var con = DriverManager.getConnection(jdbcUrl(db), CONTAINER.getUsername(), CONTAINER.getPassword());
             var st = con.createStatement()) {
            st.execute("REVOKE CREATE ON SCHEMA public FROM PUBLIC");
            st.execute("ALTER DEFAULT PRIVILEGES FOR ROLE " + owner + " GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO " + app);
            st.execute("ALTER DEFAULT PRIVILEGES FOR ROLE " + owner + " GRANT USAGE, SELECT ON SEQUENCES TO " + app);
            st.execute("ALTER DEFAULT PRIVILEGES FOR ROLE " + owner + " GRANT EXECUTE ON FUNCTIONS TO " + app);
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot configure test database " + db, e);
        }
        return new PostgresTestDatabase(db);
    }

    /**
     * SPEC-PLAT-04 D8: the cluster-wide Debezium login role, as in {@code local-dev/postgres/02-cdc-role.sh}.
     * Migrations grant it SELECT on outbox tables only (placeholder {@code cdc_role}).
     */
    public static final String CDC_ROLE = "gc_cdc";

    private static void createCdcRoleOnce(java.sql.Statement st) throws SQLException {
        // Several service databases may be created concurrently; the role is cluster-wide, so tolerate "exists".
        st.execute("DO $$ BEGIN CREATE ROLE " + CDC_ROLE + " LOGIN REPLICATION PASSWORD '" + CDC_ROLE + "'; "
                + "EXCEPTION WHEN duplicate_object OR unique_violation THEN NULL; END $$");
    }

    /** Connection as the CDC role (privilege tests). */
    public DataSource cdcDataSource() {
        return new SimpleDriverDataSource(new org.postgresql.Driver(), jdbcUrl(), CDC_ROLE, CDC_ROLE);
    }

    private static String jdbcUrl(String db) {
        return "jdbc:postgresql://%s:%d/%s".formatted(CONTAINER.getHost(), CONTAINER.getMappedPort(5432), db);
    }

    public String jdbcUrl() { return jdbcUrl(database); }

    public String ownerUser() { return database + "_owner"; }

    public String ownerPassword() { return database + "_owner"; }

    public String appUser() { return database + "_app"; }

    public String appPassword() { return database + "_app"; }

    public DataSource ownerDataSource() {
        return new SimpleDriverDataSource(new org.postgresql.Driver(), jdbcUrl(), ownerUser(), ownerPassword());
    }

    public DataSource appDataSource() {
        return new SimpleDriverDataSource(new org.postgresql.Driver(), jdbcUrl(), appUser(), appPassword());
    }

    /** Spring properties pointing a service at this database (runtime = app role, migrations = owner role). */
    public Map<String, String> springProperties() {
        return Map.of(
                "spring.datasource.url", jdbcUrl(),
                "spring.datasource.username", appUser(),
                "spring.datasource.password", appPassword(),
                "DB_OWNER_USER", ownerUser(),
                "DB_OWNER_PASSWORD", ownerPassword());
    }
}
