package com.growercare.outbox.inbox;

import java.util.UUID;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * An incoming event as written by {@link com.growercare.outbox.OutboxWriter}: {@code {eventId, eventType, occurredAt, data}}.
 * Unknown fields are ignored (forward compatible, SPEC-PLAT-04 R59).
 */
public record EventEnvelope(UUID eventId, String eventType, JsonNode data) {

    /** @throws MalformedEventException when the value is not an envelope (never retried) */
    public static EventEnvelope parse(JsonMapper json, String value) {
        if (value == null || value.isBlank()) {
            throw new MalformedEventException("empty record value");
        }
        JsonNode root;
        try {
            root = json.readTree(value);
        } catch (JacksonException e) {
            throw new MalformedEventException("record value is not JSON", e);
        }
        if (!root.isObject()) {
            throw new MalformedEventException("record value is not a JSON object");
        }
        var eventType = root.path("eventType").asString("");
        if (eventType.isBlank()) {
            throw new MalformedEventException("envelope without eventType");
        }
        return new EventEnvelope(uuid(root.path("eventId").asString(""), "eventId"), eventType, root.path("data"));
    }

    /** A required UUID field of {@code data}. */
    public UUID requiredUuid(String field) {
        return uuid(data.path(field).asString(""), "data." + field);
    }

    private static UUID uuid(String value, String field) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            throw new MalformedEventException("envelope " + field + " is missing or not a UUID", e);
        }
    }
}
