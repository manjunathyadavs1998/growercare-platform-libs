package com.growercare.shard.migration;

import java.util.List;

/** Startup/deployment failure: one or more shard schemas could not be migrated. */
public class ShardMigrationException extends RuntimeException {

    private final List<String> failedSchemas;

    public ShardMigrationException(String message, List<String> failedSchemas, Throwable cause) {
        super(failedSchemas.isEmpty() ? message : message + ": " + String.join(", ", failedSchemas), cause);
        this.failedSchemas = List.copyOf(failedSchemas);
    }

    public List<String> failedSchemas() {
        return failedSchemas;
    }
}
