---
name: java-exception-handling
description: GrowerCare exception handling - the sealed GcException hierarchy and ErrorCode catalogue (gc-common), HTTP status mapping, RFC 9457 Problem Details responses, JDBC/Spring exception translation in adapters, validation errors, what to log and what never to expose. Use when throwing, translating or handling errors, adding an error code, or writing a controller advice or error tests.
---

# Exception handling

Code:
- `libs/gc-common`: `common.error` (hierarchy, `ErrorCode`), `common.web.GcProblemHandler` (MVC advice), `common.web.TraceIds`;
- `libs/gc-security`: `security.web.ProblemSecurityHandlers` (401/403/503 from the filter chain).

Both are picked up by the `com.growercare` component scan. Increment 4 adds `If-Match`/412 for case updates.

## 1. The hierarchy (sealed, so the mapping is exhaustive)
| Exception | ErrorCode | HTTP | When |
|---|---|---|---|
| `NotFoundException(resource)` | `NOT_FOUND` | 404 | Missing **or outside the caller's scope** (never reveal existence) |
| `PermissionDeniedException(permission)` | `PERMISSION_DENIED` | 403 | Function-level permission missing |
| `ValidationException(List<FieldError>)` | `VALIDATION_FAILED` | 400 | Input invalid beyond Bean Validation (bad cursor, unknown sort field) |
| `StaleVersionException(resource, expected)` | `STALE_VERSION` | 412 | Optimistic lock lost (`If-Match` stale) |
| `ConflictException(DUPLICATE \| IDEMPOTENCY_KEY_REUSED)` | as given | 409 | Unique business key exists; idempotency key reused with a different body |
| `BusinessRuleException(rule, msg)` | `BUSINESS_RULE_VIOLATION` | 422 | Invalid transition, SoD conflict, delegation ceiling, closed case |
| `ArchiveNotEnabledException` | `ARCHIVE_NOT_ENABLED` | 501 | Query needs the archive before the Snowflake adapter exists |
| `DependencyUnavailableException(dep, cause)` | `DEPENDENCY_UNAVAILABLE` | 503 | IAM, Redis or DB unreachable (retryable) |
| anything else | `INTERNAL_ERROR` | 500 | A bug |

Also mapped by the handler (not part of the hierarchy):
- missing/invalid JWT → 401 `UNAUTHENTICATED`;
- Spring Security `AccessDeniedException` → 403;
- `MethodArgumentNotValidException` / `HandlerMethodValidationException` → 400 with field errors;
- missing `If-Match` → 428 `PRECONDITION_REQUIRED`;
- `HttpMessageNotReadableException` / type mismatch / missing parameter → 400 (`field` = parameter name or `body`);
- `jakarta.validation.ConstraintViolationException` → 400 with `field` = last path node. It comes from `@Validated` controllers/services, whose Spring method-validation interceptor throws it instead of `HandlerMethodValidationException`. It was unmapped (500) until 2026-11-15; `@Min` on a `@RequestParam` found it;
- unknown route (`NoResourceFoundException`) → 404 problem;
- filter-chain failures (`ProblemSecurityHandlers`): no/invalid token → 401 + `WWW-Authenticate: Bearer`; `PrincipalUnavailableException` (grants source down) → 503; access denied → 403.

The trace id comes from the W3C `traceparent` header, or a generated id stored on the request (`TraceIds.current/currentOrNull`); every problem carries `traceId`.

Adding a failure type means:
1. add the `ErrorCode` (the slug is a public contract and is never renamed);
2. add a `final` subclass to the `permits` list;
3. update the handler switch (the compiler forces it).

## 2. Throwing rules
- Throw **domain exceptions from application/domain code**. Controllers don't build error responses.
- `BusinessRuleException.rule` is machine-readable and dotted: `case.transition.not-allowed`, `grant.sod-conflict`, `grant.delegation-ceiling`, `comment.case-closed`.
- **Messages go to clients**, so they contain no PII, no SQL, no internal class names and no other tenant's data. Use IDs and codes only.
- Out-of-scope access is a `NotFoundException`, the same one as for a missing resource (same message, same timing path).
- Don't use exceptions for normal flow. Use `Optional` for "maybe absent" and sealed result types for expected alternatives (e.g. `CreateResult.Created | CreateResult.Replayed`).
- Wrap checked exceptions at the adapter boundary and keep the cause: `new DependencyUnavailableException("iam", e)`.

## 3. Translation at adapters
**Persistence:** see the table in `java-persistence-sql §6`. Key points:
- match on **SQLState and constraint name**, not message text. Exception: unique violations on **partitioned** tables name the partition's index, so use `ON CONFLICT` or match on the key columns instead (see `java-persistence-sql §6`);
- `42501` arrives as Spring's `BadSqlGrammarException` but is a privilege bug, so it maps to 500, never 400;
- deadlock / serialization failures (`40P01` / `40001`): retry once at the use-case level, then 503.

**HTTP clients (IAM etc.):**
- timeouts or 5xx → `DependencyUnavailableException`;
- 404 from a lookup → `Optional.empty()`;
- 4xx → a bug (500), unless documented.

**Redis:** a cache failure **must not fail the request**. Log WARN, fall through to the source of truth (ADR-011).

## 4. Problem Details response (RFC 9457)
```json
{
  "type": "https://errors.growercare.example/stale-version",
  "title": "Resource was modified by someone else",
  "status": 412,
  "detail": "Case was modified; reload and retry",
  "instance": "/cases/1234567890",
  "code": "STALE_VERSION",
  "traceId": "4bf92f3577b34da6a3ce929d0e0e4736",
  "expectedVersion": 7
}
```
- `Content-Type: application/problem+json`.
- `code` = `ErrorCode.name()`; `type` = `ErrorCode.typeUri()`. Extra members come from `GcException.properties()`, and they must be non-sensitive.
- Validation adds `"errors": [{"field": "title", "message": "must not be blank"}]`.
- The 500 response has a generic `detail` and the `traceId` only.
- `server.error.include-stacktrace=never` and `include-message=never` are already set in every `application.yml`.

## 5. Logging
| Case | Level | Content |
|---|---|---|
| 4xx from `GcException` | none (or DEBUG) | It's the client's problem; the metric `http.server.requests{status}` covers it |
| 403/404 on a protected resource | none in logs; an **audit event** for denials on sensitive resources (increment 2) | actor, resource type/ID, outcome |
| 503 dependency | WARN | dependency name, traceId |
| 500 | ERROR **once**, in the global handler, with the stack trace | traceId, route; no request bodies |

## 6. Tests
- Every use case has tests for its failure paths, asserting the **exception type and rule/code**.
- Web tests assert `status`, `type`, `code` and that no stack trace or SQL appears in the body.
- DB constraint tests assert the **constraint name** in the root cause (see `CaseSchemaMigrationIT`).
