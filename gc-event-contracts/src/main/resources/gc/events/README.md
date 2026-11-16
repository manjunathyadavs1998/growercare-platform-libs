# Event contracts (SPEC-PLAT-04)

Every event the backend publishes has a JSON Schema here (D2: JSON now; Avro + Glue Schema Registry at the AWS stage, ADR-015).
`*EventContractIT` tests run the real use cases and validate every outbox row against these files, so a payload change
without a contract change fails the build.

## Transport
- **Publisher:** the transactional outbox (`<schema>.outbox`), streamed by Debezium's Outbox Event Router (`local-dev/debezium/`).
- **Topic:** `<domain>.<aggregate>.events`. All events of one aggregate share a topic, which keeps per-aggregate ordering (D3).
- **Key:** the aggregate id as a string (case id, grant id).
- **Value:** the envelope `{eventId, eventType, occurredAt, data}`.
- **Headers:** `id` (event id), `eventType`, `traceId`, `occurredAt`.

## Rules
- **BACKWARD compatible only.** You may add an *optional* field to `data`. You may not remove or rename fields or change types; a breaking change needs a new event type plus dual publishing.
- `data` holds **ids and codes only, never PII** (names, phones, emails, comment text). `additionalProperties: false` makes adding a field a deliberate contract change.
- Consumers must ignore unknown fields and unknown event types (SPEC-PLAT-04 R59).

| Event type | Topic | Meaning |
|---|---|---|
| `case.created` | `case.case.events` | A case was created (SPEC-CASE-02). |
| `case.routed` | `case.case.events` | The case was routed to a queue (SPEC-CASE-04 R88). |
| `case.assigned` | `case.case.events` | The case was assigned to a staff user (SPEC-CASE-02). |
| `case.status_changed` | `case.case.events` | Workflow transition (SPEC-CASE-02). |
| `case.shared` | `case.case.events` | Case shared with a partner organisation (SPEC-CASE-03). |
| `case.unshared` | `case.case.events` | Partner share removed (SPEC-CASE-03). |
| `comment.posted` | `case.case.events` | A comment was posted; keyed by caseId so it is ordered with the case's other events (SPEC-CASE-03). No comment text (PII risk). |
| `case.sla.warning` | `case.case.events` | An SLA warning threshold was reached (SPEC-CASE-04 R82). |
| `case.sla.breached` | `case.case.events` | The SLA was breached (SPEC-CASE-04 R82). |
| `iam.grant.created` | `iam.grant.events` | A role grant was created (SPEC-IAM-01). Consumers evict cached principals of `subject` (SPEC-PLAT-04 R58). |
| `iam.grant.revoked` | `iam.grant.events` | A role grant was revoked (SPEC-IAM-01). |
