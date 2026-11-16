package com.growercare.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.params.provider.Arguments.argumentSet;

import com.growercare.outbox.inbox.InboxDeduplicator;
import com.growercare.testsupport.PostgresTestDatabase;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * SPEC-PLAT-04 R50/R51 (outbox) and R56/R57 (inbox) against a real PostgreSQL with the owner/app role split.
 * The tables mirror the service migrations (iam.outbox, &lt;shard&gt;.outbox, msg.inbox).
 */
class OutboxAndInboxIT {

    static DataSource app;
    static JdbcClient appJdbc;
    static JdbcClient owner;
    static TransactionTemplate tx;

    @BeforeAll
    static void schema() {
        var db = PostgresTestDatabase.forService("gc_outbox_lib");
        owner = JdbcClient.create(db.ownerDataSource());
        owner.sql("CREATE SCHEMA IF NOT EXISTS lib").update();
        owner.sql("""
                CREATE TABLE IF NOT EXISTS lib.outbox (
                    event_id uuid PRIMARY KEY, aggregate_type varchar(64) NOT NULL, aggregate_id varchar(64) NOT NULL,
                    event_type varchar(128) NOT NULL, payload jsonb NOT NULL, occurred_at timestamptz NOT NULL DEFAULT now(),
                    trace_id varchar(32))""").update();
        owner.sql("""
                CREATE TABLE IF NOT EXISTS lib.inbox (
                    consumer_group varchar(128) NOT NULL, event_id uuid NOT NULL,
                    processed_at timestamptz NOT NULL DEFAULT now(), PRIMARY KEY (consumer_group, event_id))""").update();
        owner.sql("GRANT USAGE ON SCHEMA lib TO " + db.appUser()).update();
        owner.sql("GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA lib TO " + db.appUser()).update();
        app = db.appDataSource();
        appJdbc = JdbcClient.create(app);
        tx = new TransactionTemplate(new DataSourceTransactionManager(app));
    }

    @Nested
    class Outbox {

        final OutboxWriter writer = new OutboxWriter(appJdbc, JsonMapper.builder().build());

        OutboxWriter.Event event() {
            return new OutboxWriter.Event("case", "42", "case.routed", Map.of("caseId", "42", "queueCode", "IN-QA"));
        }

        @Test
        void platE73aCommittedEnvelopeIsStoredWithTraceId() {
            var id = tx.execute(status -> writer.append("lib.outbox", event(), "4bf92f3577b34da6a3ce929d0e0e4736"));
            var row = owner.sql("SELECT aggregate_type, aggregate_id, event_type, payload::text, trace_id FROM lib.outbox WHERE event_id = ?")
                    .param(id).query().singleRow();
            assertThat(row).containsEntry("aggregate_type", "case").containsEntry("aggregate_id", "42")
                    .containsEntry("event_type", "case.routed").containsEntry("trace_id", "4bf92f3577b34da6a3ce929d0e0e4736");
            assertThat((String) row.get("payload")).contains("\"eventId\": \"" + id).contains("\"queueCode\": \"IN-QA\"");
        }

        @Test
        void platE73bRolledBackTransactionLeavesNoRow() {
            var ids = new UUID[1];
            assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
                ids[0] = writer.append("lib.outbox", event(), null);
                throw new IllegalStateException("business rule failed after the outbox insert");
            })).isInstanceOf(IllegalStateException.class);
            assertThat(owner.sql("SELECT count(*) FROM lib.outbox WHERE event_id = ?").param(ids[0]).query(Integer.class).single()).isZero();
        }

        @Test
        void appendOutsideATransactionIsRefused() {
            assertThatThrownBy(() -> writer.append("lib.outbox", event(), null))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("inside the business transaction");
        }

        @ParameterizedTest(name = "table name {0} is rejected (never from input)")
        @ValueSource(strings = {"outbox", "LIB.outbox", "lib.outbox; DROP TABLE x", "lib.events"})
        void invalidTableNamesAreRejected(String table) {
            assertThatThrownBy(() -> tx.executeWithoutResult(status -> writer.append(table, event(), null)))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        /** aggregate type, aggregate id, event type, payload. */
        static Stream<Arguments> invalidEvents() {
            return Stream.of(
                    argumentSet("event type without a dot", "case", "1", "created", Map.of()),
                    argumentSet("event type upper case", "case", "1", "Case.Created", Map.of()),
                    argumentSet("null event type", "case", "1", null, Map.of()),
                    argumentSet("null aggregate id", "case", null, "case.created", Map.of()),
                    argumentSet("null payload", "case", "1", "case.created", null));
        }

        @ParameterizedTest
        @MethodSource("invalidEvents")
        void invalidEventsAreRejected(String type, String id, String eventType, Map<String, Object> payload) {
            assertThatThrownBy(() -> new OutboxWriter.Event(type, id, eventType, payload)).isInstanceOf(RuntimeException.class);
        }
    }

    @Nested
    class Inbox {

        final InboxDeduplicator inbox = new InboxDeduplicator(appJdbc, "lib.inbox");

        Boolean first(String group, UUID eventId) {
            return tx.execute(status -> inbox.firstDelivery(group, eventId));
        }

        @Test
        void platE65FirstDeliveryOnlyOncePerGroup() {
            var eventId = UUID.randomUUID();
            assertThat(first("g1", eventId)).isTrue();
            assertThat(first("g1", eventId)).isFalse();                    // duplicate delivery
            assertThat(first("g2", eventId)).isTrue();                     // another consumer group is independent
        }

        @Test
        void platE66RolledBackSideEffectRollsBackTheInboxRow() {
            var eventId = UUID.randomUUID();
            assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
                inbox.firstDelivery("g1", eventId);
                throw new IllegalStateException("side effect failed");
            })).isInstanceOf(IllegalStateException.class);
            assertThat(first("g1", eventId)).isTrue();                     // processed again on redelivery
        }

        @Test
        void outsideATransactionIsRefused() {
            assertThatThrownBy(() -> inbox.firstDelivery("g1", UUID.randomUUID())).isInstanceOf(IllegalStateException.class);
        }

        @ParameterizedTest(name = "inbox table {0} is rejected")
        @ValueSource(strings = {"inbox", "lib.outbox", "lib.inbox;--"})
        void invalidInboxTables(String table) {
            assertThatThrownBy(() -> new InboxDeduplicator(appJdbc, table)).isInstanceOf(IllegalArgumentException.class);
        }
    }
}
