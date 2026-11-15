package com.growercare.shard;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * A logical shard: one Postgres schema such as {@code in_s27}. The schema name is derived, never taken from
 * user input, and is re-validated so it is always safe to splice into SQL as an identifier.
 */
public record ShardId(String countryCode, int shardNo) {

    public static final int SHARDS_PER_COUNTRY = 64;
    private static final Pattern SCHEMA = Pattern.compile("^[a-z]{2}_s\\d{2}$");

    public ShardId {
        if (countryCode == null || !countryCode.matches("^[A-Z]{2}$")) {
            throw new IllegalArgumentException("Invalid country code: " + countryCode);
        }
        if (shardNo < 0 || shardNo >= SHARDS_PER_COUNTRY) {
            throw new IllegalArgumentException("shardNo out of range 0..63: " + shardNo);
        }
    }

    /** e.g. {@code in_s07}. Guaranteed to match {@code ^[a-z]{2}_s\d{2}$}. */
    public String schemaName() {
        var name = "%s_s%02d".formatted(countryCode.toLowerCase(Locale.ROOT), shardNo);
        if (!SCHEMA.matcher(name).matches()) {
            throw new IllegalStateException("Unsafe schema name " + name);
        }
        return name;
    }

    public static boolean isValidSchemaName(String schema) {
        return schema != null && SCHEMA.matcher(schema).matches();
    }
}
