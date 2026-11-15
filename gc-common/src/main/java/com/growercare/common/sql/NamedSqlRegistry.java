package com.growercare.common.sql;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/**
 * Registry of hand-written, named SQL statements (ADR-013). Statements live in resource files:
 * <pre>
 * -- name: grant.findActiveBySubject
 * SELECT ... FROM iam.role_grant g WHERE ...
 * </pre>
 * The statement id is {@code <file name without .sql>.<name>}, e.g. {@code grant.findActiveBySubject} from
 * {@code sql/postgres/grant.sql}. Loading fails fast on duplicate ids, empty statements or an unknown file layout,
 * so a typo is a startup error, not a runtime 500.
 * <p>The only template token is {@code {shard}}, replaced by {@link #render(String, String)} with a schema name
 * that matches {@code ^[a-z]{2}_s\d{2}$}. Values are always bind parameters.
 */
public final class NamedSqlRegistry {

    private static final Pattern NAME_LINE = Pattern.compile("^--\\s*name:\\s*([a-zA-Z][a-zA-Z0-9]*)\\s*$");
    private static final Pattern SHARD_SCHEMA = Pattern.compile("^[a-z]{2}_s\\d{2}$");
    private static final String SHARD_TOKEN = "{shard}";

    private final String dialect;
    private final Map<String, String> statements;
    private final Map<String, String> rendered = new ConcurrentHashMap<>();

    private NamedSqlRegistry(String dialect, Map<String, String> statements) {
        this.dialect = dialect;
        this.statements = Map.copyOf(statements);
    }

    /** Loads every {@code classpath*:sql/<dialect>/*.sql}. */
    public static NamedSqlRegistry load(String dialect) {
        if (!dialect.matches("^[a-z]+$")) {
            throw new IllegalArgumentException("Invalid SQL dialect " + dialect);
        }
        try {
            var resources = new PathMatchingResourcePatternResolver().getResources("classpath*:sql/" + dialect + "/*.sql");
            var all = new HashMap<String, String>();
            for (Resource resource : resources) {
                parse(resource).forEach((id, sql) -> {
                    if (all.putIfAbsent(id, sql) != null) {
                        throw new IllegalStateException("Duplicate SQL statement id " + id);
                    }
                });
            }
            return new NamedSqlRegistry(dialect, all);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot load SQL for dialect " + dialect, e);
        }
    }

    static Map<String, String> parse(Resource resource) throws IOException {
        var file = resource.getFilename();
        if (file == null || !file.matches("^[a-z][a-z0-9-]*\\.sql$")) {
            throw new IllegalStateException("SQL file names must be lower-case kebab: " + file);
        }
        var prefix = file.substring(0, file.length() - ".sql".length());
        var result = new HashMap<String, String>();
        String current = null;
        var body = new StringBuilder();
        for (String line : resource.getContentAsString(StandardCharsets.UTF_8).split("\\R")) {
            var name = NAME_LINE.matcher(line.strip());
            if (name.matches()) {
                put(result, prefix, current, body);
                current = name.group(1);
                body.setLength(0);
            } else if (line.strip().startsWith("--")) {
                continue;   // whole-line comments document the file; they never become part of a statement
            } else if (current != null) {
                body.append(line).append('\n');
            } else if (!line.isBlank() && !line.strip().startsWith("--")) {
                throw new IllegalStateException(file + ": SQL before the first '-- name:' line");
            }
        }
        put(result, prefix, current, body);
        return result;
    }

    private static void put(Map<String, String> result, String prefix, String name, StringBuilder body) {
        if (name == null) {
            return;
        }
        var sql = body.toString().strip();
        if (sql.isEmpty()) {
            throw new IllegalStateException("Empty SQL statement " + prefix + "." + name);
        }
        if (sql.endsWith(";")) {
            sql = sql.substring(0, sql.length() - 1);
        }
        if (result.putIfAbsent(prefix + "." + name, sql) != null) {
            throw new IllegalStateException("Duplicate SQL statement id " + prefix + "." + name);
        }
    }

    /** Statement without shard templating. */
    public String get(String id) {
        var sql = statements.get(id);
        if (sql == null) {
            throw new IllegalArgumentException("Unknown SQL statement " + dialect + ":" + id);
        }
        if (sql.contains(SHARD_TOKEN)) {
            throw new IllegalStateException("Statement " + id + " is shard-templated; use render(id, schema)");
        }
        return sql;
    }

    /** Statement with {@code {shard}} replaced by a validated shard schema name; cached per (id, schema). */
    public String render(String id, String shardSchema) {
        if (shardSchema == null || !SHARD_SCHEMA.matcher(shardSchema).matches()) {
            throw new IllegalArgumentException("Invalid shard schema");
        }
        return rendered.computeIfAbsent(id + '@' + shardSchema, key -> {
            var sql = statements.get(id);
            if (sql == null) {
                throw new IllegalArgumentException("Unknown SQL statement " + dialect + ":" + id);
            }
            return sql.replace(SHARD_TOKEN, shardSchema);
        });
    }

    public Set<String> ids() {
        return statements.keySet();
    }

    public String dialect() {
        return dialect;
    }

    @Override
    public String toString() {
        return "NamedSqlRegistry[" + dialect + ", " + statements.size() + " statements: "
                + Arrays.toString(statements.keySet().stream().sorted().toArray()) + "]";
    }
}
