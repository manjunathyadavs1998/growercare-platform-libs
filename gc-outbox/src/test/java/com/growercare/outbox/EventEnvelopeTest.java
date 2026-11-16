package com.growercare.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.growercare.outbox.inbox.EventEnvelope;
import com.growercare.outbox.inbox.MalformedEventException;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

/** SPEC-PLAT-04 R59/R60: envelope parsing for consumers; malformed records are never retried. */
class EventEnvelopeTest {

    static final JsonMapper JSON = JsonMapper.builder().build();
    static final UUID ID = UUID.fromString("0f8fad5b-d9cb-469f-a165-70867728950e");
    static final UUID SUBJECT = UUID.fromString("6f1c0a10-0101-4a00-8000-000000000101");

    @Test
    void validEnvelopeIgnoresUnknownFields() {
        var event = EventEnvelope.parse(JSON, """
                {"eventId":"%s","eventType":"iam.grant.revoked","occurredAt":"2026-11-16T10:00:00Z","futureField":1,
                 "data":{"subject":"%s","extra":"ignored"}}""".formatted(ID, SUBJECT));
        assertThat(event.eventId()).isEqualTo(ID);
        assertThat(event.eventType()).isEqualTo("iam.grant.revoked");
        assertThat(event.requiredUuid("subject")).isEqualTo(SUBJECT);
    }

    @ParameterizedTest(name = "PLAT-E69 malformed: {0}")
    @NullSource
    @ValueSource(strings = {"", "   ", "not-json", "[1,2]", "{\"eventId\":\"" + "0f8fad5b-d9cb-469f-a165-70867728950e" + "\"}",
            "{\"eventType\":\"iam.grant.revoked\"}", "{\"eventType\":\"iam.grant.revoked\",\"eventId\":\"nope\"}"})
    void malformedRecordsAreRejected(String value) {
        assertThatThrownBy(() -> EventEnvelope.parse(JSON, value)).isInstanceOf(MalformedEventException.class);
    }

    @Test
    void platE69cMissingRequiredDataFieldIsMalformed() {
        var event = EventEnvelope.parse(JSON, "{\"eventId\":\"%s\",\"eventType\":\"iam.grant.revoked\",\"data\":{}}".formatted(ID));
        assertThatThrownBy(() -> event.requiredUuid("subject"))
                .isInstanceOf(MalformedEventException.class).hasMessageContaining("data.subject");
    }
}
