# growercare-platform-libs

Shared Java libraries for every GrowerCare service (ADR-017). Each service depends on a **released version**; it never
depends on another service's code.

| Artifact | What it gives a service |
|---|---|
| `gc-parent` | The build: Java 21, Spring Boot version, enforcer (no ORM), unit/integration test split, JaCoCo merged report, Jib image settings |
| `gc-common` | RFC 9457 errors (`GcProblemHandler`, `GcErrorController`), named SQL (`NamedSqlRegistry`), keyset `Cursor`, trace ids, **fail-fast environment check** (`RequiredEnvironment`) |
| `gc-security` | Keycloak JWT resource server, `GcPrincipal` + grants, `@RequiresPermission`, `PolicyEnforcer`/`ScopeFilter`, Redis principal cache, problem+json security handlers |
| `gc-shard-routing` | Countries, shard ids, `IdCodec`, `ShardRouter`, `ShardedFlywayMigrator` |
| `gc-outbox` | Transactional `OutboxWriter`; `InboxDeduplicator`, `EventEnvelope` for idempotent consumers |
| `gc-event-contracts` | JSON Schemas of every published event (`gc/events/<type>.schema.json`) |
| `gc-test-support` | Testcontainers (Postgres with owner/app/CDC roles, Redis, Kafka, SeaweedFS S3), test JWTs, ArchUnit rules, contract helpers |

## Use in a service
```xml
<parent>
  <groupId>com.growercare</groupId>
  <artifactId>gc-parent</artifactId>
  <version>1.0.0</version>
  <relativePath/>
</parent>
<repositories>
  <repository>
    <id>github</id>
    <url>https://maven.pkg.github.com/manjunathyadavs1998/growercare-platform-libs</url>
  </repository>
</repositories>
```
GitHub Packages requires a token even for public packages:
- **CI:** uses `GITHUB_TOKEN` (`packages: read`).
- **Locally, either:**
  - put a PAT with `read:packages` in `~/.m2/settings.xml` (`<server><id>github</id>…`); or
  - build this repository once with `mvn install -Drevision=<version>`.

## Build and release
- `mvn -B verify`: all tests; the coverage gate reads `coverage/target/site/jacoco-aggregate/jacoco.xml` (≥ 80 % lines).
- Release: merge to `main`, then tag `vX.Y.Z`. `release.yml` publishes every module at X.Y.Z (version taken from the tag).
- A breaking change bumps the major version. Services upgrade through a normal PR.

Specs and decisions live in [growercare](https://github.com/manjunathyadavs1998/growercare) (`docs/specs/platform/`, ADR-011…017).
