package com.growercare.outbox;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

/**
 * Transactional outbox (ADR-012): appends an event row in the <b>caller's</b> transaction, so the event exists
 * if and only if the business change commits. Debezium's outbox router publishes the rows to Kafka.
 * Business code never talks to Kafka directly.
 */
public final class OutboxWriter {

    private static final Pattern TABLE = Pattern.compile("^[a-z][a-z0-9_]*\\.outbox$");
    private static final Pattern EVENT_TYPE = Pattern.compile("^[a-z]+(\\.[a-z_]+)+$");

    /** An event to publish. {@code aggregateId} becomes the Kafka key (ordering per aggregate). */
    public record Event(String aggregateType, String aggregateId, String eventType, Map<String, Object> payload) {
        public Event {
            Objects.requireNonNull(aggregateType, "aggregateType");
            Objects.requireNonNull(aggregateId, "aggregateId");
            Objects.requireNonNull(payload, "payload");
            if (eventType == null || !EVENT_TYPE.matcher(eventType).matches()) {
                throw new IllegalArgumentException("eventType must look like 'iam.grant.created'");
            }
            payload = Map.copyOf(payload);
        }
    }

    private final JdbcClient jdbc;
    private final JsonMapper json;

    public OutboxWriter(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /**
     * @param outboxTable schema-qualified table, e.g. {@code iam.outbox} or {@code in_s27.outbox}
     *                    (validated; never from user input)
     * @return the event id
     */
    public UUID append(String outboxTable, Event event, String traceId) {
        if (!TABLE.matcher(outboxTable).matches()) {
            throw new IllegalArgumentException("Invalid outbox table " + outboxTable);
        }
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("OutboxWriter.append must run inside the business transaction");
        }
        var eventId = UUID.randomUUID();
        var envelope = Map.of(
                "eventId", eventId.toString(),
                "eventType", event.eventType(),
                "occurredAt", Instant.now().toString(),
                "data", event.payload());
        jdbc.sql("INSERT INTO " + outboxTable + " (event_id, aggregate_type, aggregate_id, event_type, payload, trace_id) "
                        + "VALUES (:id, :aggType, :aggId, :type, CAST(:payload AS jsonb), :traceId)")
                .param("id", eventId)
                .param("aggType", event.aggregateType())
                .param("aggId", event.aggregateId())
                .param("type", event.eventType())
                .param("payload", json.writeValueAsString(envelope))
                .param("traceId", traceId)
                .update();
        return eventId;
    }
}
