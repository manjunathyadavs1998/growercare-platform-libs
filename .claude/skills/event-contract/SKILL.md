---
name: event-contract
description: Define, change or consume a GrowerCare Kafka event (JSON Schema contract in gc-event-contracts, growercare-platform-libs, per-aggregate topics, keys, headers, outbox → Debezium publishing, idempotent inbox consumption, retries/DLQ). Use whenever a service publishes or consumes a domain event or a CDC topic.
---

# Event contracts (Kafka)

Background: SPEC-PLAT-04 (`docs/specs/platform/04-event-publishing-and-consumers.md`), ADR-012 (keys/partitions), **ADR-015 (JSON + JSON Schema until the AWS stage)**, `02_DESIGN_PATTERNS.md` (outbox, inbox, sagas).

## 1. Define the contract first (`gc-event-contracts/src/main/resources/gc/events/` in growercare-platform-libs)
- One file per event type: `<eventType>.schema.json` (JSON Schema 2020-12). Example: `case.status_changed.schema.json`.
- The value is the outbox envelope `{eventId (uuid), eventType (const), occurredAt (date-time), data}`.
- `data`:
  - **ids and codes only**: ids are strings (`^[0-9]{1,19}$`), never PII (no names, phones, emails or comment text);
  - `additionalProperties: false`, so adding a field is a reviewed contract change.
- **Topic:** `<domain>.<aggregate>.events` (`case.case.events`, `iam.grant.events`). One topic per aggregate keeps per-aggregate ordering across event types. The event type travels in the `eventType` header.
- **Key:** the aggregate id as a **string** (`OutboxWriter.Event.aggregateId`). The Debezium router maps `aggregate_type` to the topic.
- **Headers** (added by the Debezium EventRouter): `id`, `eventType`, `traceId`, `occurredAt`.
- **Compatibility is BACKWARD only.** You may add an *optional* field. You may not remove or rename fields or change types. A breaking change needs a new event type, dual publishing and a consumer migration.
- The contract ships in a libs release: release the tag before the producing or consuming service bumps its libs version.
- Add the type to the README table and to the service's `*EventContractIT` rows (`EventContracts.violations(type, json)` in gc-test-support).

## 2. Publish (outbox only)
```java
tx.executeWithoutResult(status -> {
    cases.updateStatus(shard, caseId, expectedVersion, toStatus);              // business change
    outbox.append(outboxOf(shard), new OutboxWriter.Event(
            "case", Long.toString(caseId), "case.status_changed",              // aggregate type → topic, id → key
            Map.of("caseId", Long.toString(caseId), "from", from, "to", to, "version", version)),
            TraceIds.currentOrNull());                                          // same transaction
});
```
- Never call `KafkaTemplate` from business code. Debezium (`local-dev/debezium/*-outbox.json`) streams the outbox from the WAL.
- A new outbox table must be **added to publication `gc_outbox`** in its migration, with `GRANT SELECT … TO ${cdc_role}`. Never let the connector auto-create the publication (`FOR ALL TABLES` would stream PII).
- Events of one aggregate are appended in business order (e.g. `case.created` before `case.routed`).

## 3. Consume (idempotent)
```java
@KafkaListener(id = "grant-cache", groupId = GROUP, topics = "${gc.events.grant-topic}",
        autoStartup = "${gc.events.consumers-enabled:true}")
public void on(ConsumerRecord<String, String> record) {
    var event = EventEnvelope.parse(json, record.value());           // MalformedEventException → DLQ, no retry
    tx.executeWithoutResult(s -> {
        if (inbox.firstDelivery(GROUP, event.eventId())) {           // msg.inbox, same transaction as the effect
            handler.handle(event);                                    // throws → rollback (inbox row too) → retry
        }
    });
}
```
- `InboxDeduplicator`, `EventEnvelope` and `MalformedEventException` live in `gc-outbox` (`com.growercare.outbox.inbox`).
- Errors (`CaseEventsConfig`): a `DefaultErrorHandler` retries 3 times with exponential backoff (1 s, 2 s, 4 s), then a `DeadLetterPublishingRecoverer` sends the record to `dlq.<consumer-group>` with Spring's `kafka_dlt-*` headers (`kafka_dlt-exception-cause-fqcn` names the real exception). `MalformedEventException` is not retryable.
- Side effects must throw on failure (no swallowed errors), so the retry happens.
- Ignore unknown event types and unknown fields, but still record them in the inbox (forward compatible).
- Inbox retention is 7 days and outbox retention 3 days (`MaintenanceJobs`).
- Delayed retry topics (1 min / 10 min / 1 h) come with the first slow or external side effect (notification-service).

## 4. Tests (rows = spec example IDs)
- Producer: `*EventContractIT` runs the real use case and validates every outbox row of each type.
- Consumer: Testcontainers Kafka (`KafkaTestContainer`, `apache/kafka-native`) + Postgres + Redis. Cover a duplicate delivery processed once, a failure then retry, an unknown type, poison → DLQ, and the partition moving on.
- Publication and privileges: `*OutboxPublicationIT` (only outbox + heartbeat; `gc_cdc` reads nothing else).
- Connector config: `DebeziumConnectorConfigTest`. Debezium end to end: `local-dev/smoke.sh` (PLAT-E71/E72/E74).

## 5. Checklist
- [ ] Schema in `contracts/events/` + README row; contract IT row added
- [ ] Outbox table in publication `gc_outbox` + `cdc_role` grant (new service/table only)
- [ ] Topic and partitions declared in gc-infra for production (ADR-012 counts); locally Connect auto-creates them
- [ ] Consumer: inbox + retry/DLQ tests; consumer listed in the service doc's "Events" section
