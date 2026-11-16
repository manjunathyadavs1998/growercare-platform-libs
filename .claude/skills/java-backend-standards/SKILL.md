---
name: java-backend-standards
description: GrowerCare Java 21 / Spring Boot 4 coding standards for every GrowerCare Java repository - modern Java (records, sealed types, switch patterns, streams, Optional, var, text blocks), immutability, package layout, naming, logging/PII rules, virtual threads, build and review checklist. Use whenever writing, refactoring or reviewing any GrowerCare Java code.
---

# Java backend standards

Stack:
- Java 21, Spring Boot **4.1.2** (Spring Framework 7), Maven multi-module (ADR-014);
- no ORM (ADR-013) — persistence rules are in `java-persistence-sql`;
- errors are in `java-exception-handling`;
- security is in `java-security-rbac`.

## 1. Modern Java: required idioms
| Use | For | Not |
|---|---|---|
| `record` | DTOs, commands, query results, value objects, config properties (`@ConfigurationProperties` records), events | Mutable POJOs with setters, Lombok `@Data` |
| Compact constructor validation | Invariants of records (`Objects.requireNonNull`, range checks); also make defensive copies (`List.copyOf`) | Validating later in services |
| `sealed` interfaces/classes + `switch` pattern matching | Closed hierarchies: `GcException`, domain outcomes, scope kinds | `instanceof` chains, visitor boilerplate |
| Streams + `Collectors` | Transforming and aggregating collections (`map/filter/groupingBy/toUnmodifiableMap/partitioningBy`) | Index loops for transformations |
| Plain `for` loop | Side effects with checked exceptions, early exit with complex state, hot loops measured to matter | Forcing a stream where it hurts readability |
| `Optional<T>` | **Return type only**, for "may be absent" lookups (`findById`) | Fields, parameters, collections of Optional, `Optional.get()` without a check (use `orElseThrow`) |
| `var` | Locals whose type is obvious from the right-hand side | Public APIs, when the type isn't obvious |
| Text blocks `"""` | SQL, JSON test fixtures, long messages | String concatenation for SQL |
| `String.formatted` | Messages | `+` chains |
| `List.of / Map.of / Set.copyOf` | Immutable collections; return unmodifiable collections from APIs | Returning internal mutable state |
| Virtual threads | Already on (`spring.threads.virtual.enabled=true`); use `Executors.newVirtualThreadPerTaskExecutor()` for fan-out | Thread pools for blocking I/O; `synchronized` around blocking I/O (pins the carrier, so use `ReentrantLock`) |

Examples:
```java
public record CreateCaseCommand(String countryCode, long growerId, CaseType type, String title, UUID clientRequestId) {
    public CreateCaseCommand {
        Objects.requireNonNull(type, "type");
        if (title == null || title.isBlank()) throw ValidationException.of("title", "must not be blank");
    }
}

Map<Integer, Long> perShard = growerIds.stream()
        .map(id -> router.forGrower("IN", id))
        .collect(Collectors.groupingBy(ShardId::shardNo, Collectors.counting()));

return switch (scope.kind()) {
    case OWN -> ScopePredicate.own(principal.userId());
    case ORG -> ScopePredicate.sharedWith(principal.partnerOrgId());
    case TERRITORY, COUNTRY, REGION -> ScopePredicate.geo(scope.geo());
    case GLOBAL -> ScopePredicate.none();
};
```

## 2. Package layout (hexagonal, per service)
```
com.growercare.<svc>/
├─ domain/        records, enums, sealed types, domain rules. NO Spring, NO SQL, NO HTTP
├─ application/   use cases (one class per use case or cohesive group), ports (in/out interfaces), @Transactional boundaries
├─ adapters/in/web/          controllers, request/response DTO records, mapping
├─ adapters/out/persistence/ JdbcClient repositories + RowMappers + named SQL
├─ adapters/out/<system>/    IAM client, Redis cache, archive (Snowflake) adapter
└─ config/        @Configuration, @ConfigurationProperties records
```
Shared libraries: `com.growercare.common` (errors, SQL, paging), `com.growercare.shard`, `com.growercare.security`, `com.growercare.outbox`. Dependencies point inward only, and ArchUnit enforces this from increment 4.

## 3. Naming
- Use cases: verb + noun (`CreateCase`, `TransitionCase`). Ports: `…Port` (out), `…UseCase` (in). Adapters: `Jdbc…Repository`, `Http…Client`, `Redis…Cache`.
- Tests: `*Test` = unit (Surefire), `*IT` = needs Docker/Testcontainers (Failsafe). Method names describe the behaviour (`growerMustBelongToTheShardCountry`).
- Constants `UPPER_SNAKE`; no abbreviations except domain ones (`bu`, `sla`, `pii`).

## 4. Rules that are easy to get wrong
1. **IDs are `long` internally and strings in JSON** (64-bit values exceed JS precision). Decode with `IdCodec`, route with `ShardRouter`, and never build IDs in Java for production rows (the DB generates them).
2. **Never trust client-supplied country, shard or tenant.** The country comes from the decoded ID or from the principal's grant.
3. **Time:** `Instant` everywhere (UTC); `ZoneId` only for presentation and `case_no` day numbers. Inject `java.time.Clock` into anything time-dependent so tests can control it.
4. **Money** (later, claims): `BigDecimal` + ISO currency, never `double`.
5. **Equality:** records give value equality. Don't put records with mutable components into sets or maps.
6. **Nulls:** validate at the boundary (Bean Validation on web DTOs, compact constructors on commands). Inside the domain, absent means `Optional` returns or explicit sealed variants.
7. **Country differences** go into data (`ref.case_transition`, country profiles) or compliance packs, never `if ("IN".equals(country))`.

## 5. Logging and PII
- SLF4J with parameterised messages: `log.info("Case {} moved to {}", caseId, status)`.
- **Never log:** phone, name, email, free-text bodies (descriptions, comments), tokens, `Authorization` headers, SQL parameter values.
- IDs (`caseId`, `userId`) and codes are fine.
- Log an exception **once**, where it is handled (the global handler logs unexpected errors with the traceId). Don't log-and-rethrow.
- Levels:
  - `ERROR` for unexpected failures needing action;
  - `WARN` for degraded but handled conditions;
  - `INFO` for lifecycle and business milestones (low volume);
  - `DEBUG` for diagnostics.

## 6. Configuration
- 12-factor: every environment-specific value comes from env vars with local defaults in `application-local.yml` (dev only, never prod secrets).
- Typed `@ConfigurationProperties` records with validation, never `@Value` scattered through services.
- Profiles: `local` (docker-compose stack, dev seed), default (prod-like; requires env vars).

## 7. Workflow: spec first, parameterized tests
Every feature or change follows `spec-driven-development`:
1. Spec in `docs/specs/<service>/` (rules + examples + API + errors).
2. Parameterized tests from the example table (spec IDs as test names), run red.
3. Implement until green.
4. Sync the spec status, traceability, docs and skills.

No code without a spec rule; no rule without a test row.

## 8. Build and verify
```
mvn -B verify          # in the service or libs repository: unit + integration (Docker must be running)
```
- Each service is its own repository (ADR-017) and depends on released `growercare-platform-libs` versions. To try a library change in a service, run `mvn install -Drevision=<x.y.z>` in the libs repository, point the service at that version, and release the libs tag before merging the service.
- In the libs repository, **never resume with `-rf :<module>` after changing a library.** Resumed builds take upstream modules from `~/.m2` (stale jars), not the reactor.
- The enforcer bans Hibernate/JPA. Don't add dependencies without managing their version in the parent POM.
- `-Xlint:all` is on: fix warnings rather than suppressing them; any `@SuppressWarnings` needs a reason.
- Report real test counts. Never claim a pass without running the build.

## 9. Review checklist
- [ ] A spec exists in `docs/specs/` and its traceability table lists the tests
- [ ] Tests are parameterized from the spec's example rows (spec IDs in names)
- [ ] Records, sealed types and streams are used where they fit, and no mutable DTOs
- [ ] No Spring/SQL/HTTP imports in `domain`
- [ ] Inputs validated at the boundary; commands validate themselves
- [ ] Authorization through `policy-sdk` (see `java-security-rbac`); out-of-scope gives 404
- [ ] SQL is hand-written, named and parameterised (see `java-persistence-sql`)
- [ ] Errors use the `GcException` hierarchy (see `java-exception-handling`)
- [ ] No PII in logs or exception messages
- [ ] Tests: unit for rules, IT for SQL/migrations/security; real results reported
