package com.growercare.outbox.inbox;

import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Idempotent consumer (SPEC-PLAT-04 D5, R56, R57). Call {@link #firstDelivery} <b>inside the transaction of the side
 * effect</b>: it records the event and returns {@code true} once per (consumer group, event id). If the side effect
 * fails, the transaction rolls back the inbox row too, so the next delivery processes the event again.
 */
public final class InboxDeduplicator {

    private static final Pattern TABLE = Pattern.compile("^[a-z][a-z0-9_]*\\.inbox$");

    private final JdbcClient jdbc;
    private final String insert;

    /** @param inboxTable schema-qualified table, e.g. {@code msg.inbox} (validated; never from input) */
    public InboxDeduplicator(JdbcClient jdbc, String inboxTable) {
        if (!TABLE.matcher(inboxTable).matches()) {
            throw new IllegalArgumentException("Invalid inbox table " + inboxTable);
        }
        this.jdbc = jdbc;
        this.insert = "INSERT INTO " + inboxTable + " (consumer_group, event_id) VALUES (:group, :eventId) "
                + "ON CONFLICT (consumer_group, event_id) DO NOTHING";
    }

    /** @return true the first time this group sees the event; false for a duplicate delivery */
    public boolean firstDelivery(String consumerGroup, UUID eventId) {
        Objects.requireNonNull(consumerGroup, "consumerGroup");
        Objects.requireNonNull(eventId, "eventId");
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("InboxDeduplicator.firstDelivery must run inside the side effect's transaction");
        }
        return jdbc.sql(insert).param("group", consumerGroup).param("eventId", eventId).update() == 1;
    }
}
