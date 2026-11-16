---
name: api-contract
description: Design or change a GrowerCare REST API contract-first in gc-contracts (OpenAPI 3.1): resource naming, Problem Details errors, cursor pagination, IDs as strings, idempotency and optimistic-locking headers, country/archive routing, and generated server/client code. Use before implementing or changing any HTTP endpoint.
---

# API contracts (OpenAPI, contract-first)

## Conventions
| Topic | Rule |
|---|---|
| Location | `gc-contracts/openapi/<service>.yaml`; shared components in `common.yaml` (Problem, Cursor, Money, ids) |
| Versioning | URL major version via gateway (`/api/v1/...`); additive changes only within a major |
| IDs | `type: string`, `format: int64`, `pattern: '^[0-9]{1,19}$'` for `case_id`-style IDs; `case_no` `pattern: '^[1-9][0-9]{14}$'` (accept dashes/spaces on input, normalise server-side, validate Damm digit) |
| Errors | RFC 9457 `application/problem+json` with `type`, `title`, `status`, `detail`, `instance`, plus `errors[]` for field validation. 400/401/403/404/409/412/422/429 documented per operation. Cross-country or out-of-scope resources return **404**, not 403 (no existence leak) |
| Pagination | Cursor (keyset): `?limit=…&cursor=…` → `{ items, nextCursor }`. No OFFSET, no total counts on large collections (`hasMore` only) |
| Sorting | Allow-listed fields only (`sort=updatedAt,desc`) |
| Filtering | Explicit query params; `from`/`to` dates required for archive endpoints |
| Idempotency | `Idempotency-Key` header required on POST that creates resources; 24 h replay returns the original response |
| Concurrency | `ETag` on GET, `If-Match` required on PATCH/PUT → 412 on mismatch |
| Archive routing | Hot endpoints serve the 90-day window. Archive endpoints (`/archive/...`) are on data-lifecycle-service. Responses include `X-Data-Source` and `X-Data-Freshness-Seconds` |
| Money | `{ amountMinor: integer, currency: ISO-4217 }` |
| Time | RFC 3339 UTC; country-local dates only as explicit `localDate` + `timezone` |
| PII | Masked by default in responses; unmask is a separate audited endpoint |
| Security | `bearerAuth` (JWT); document required permission in `x-permission: case:assign` per operation |

## Where the contract lives (current state, SPEC-PLAT-01)
- **Generated from the code** by springdoc, through the shared `OpenApiConfiguration` in gc-common: title = application name, global HTTP bearer scheme `bearer`.
- **Exposure:** `/v3/api-docs` and Swagger UI only with `springdoc.api-docs.enabled=true` (the `local` profile). Elsewhere the routes are secured and answer 401.
- **Committed copy:** `contracts/<service>.openapi.json` in the service repository, written by `<Svc>OpenApiContractIT` with sorted keys for stable diffs. CI runs `git diff --exit-code contracts/`, so an unreviewed API change fails the build.
- **Later:** the contract moves to `gc-contracts` (contract-first), and the export test turns into a compatibility check against it.

## Workflow
1. Edit the spec in `gc-contracts`. Run Spectral lint (`.spectral.yaml`) and openapi-diff vs `main` (CI blocks breaking changes).
2. Add **examples** for every response (they feed MSW mocks and Pact).
3. Merge → CI publishes Java server interfaces (OpenAPI Generator, `interfaceOnly=true`) and the TS client (orval → TanStack Query hooks).
4. Implement the generated interface in the service (`service-scaffold` skill).
5. Consumer-driven contracts: frontend/other services publish Pact pacts; provider verification runs in the service CI.
