# growercare-platform-libs: project guide for Claude

Shared libraries used by every GrowerCare service (ADR-017). Architecture, ADRs and specs live in the
`growercare` repository (`docs/specs/platform/`, `docs/architecture/adr/`). Read README.md for the module map.

## Rules
1. **Libraries are released by tag** (`vX.Y.Z` → GitHub Packages). Breaking a public API means a major version.
2. **No service-specific code.** If only one service needs it, it belongs in that service.
3. **Spec-driven:** spec rule → parameterized test rows named after spec example IDs → code → green.
4. **Tests are parameterized by default.** Database code is tested against PostgreSQL via `PostgresTestDatabase`, and Redis via `RedisTestContainer`.
5. **Coverage ≥ 80 % lines** from this repository's own tests (merged report in `coverage/`).
6. **Errors are RFC 9457 problem+json** with a stable `code`; nothing environment-specific is hard-coded (`RequiredEnvironment`).
7. **Never commit to `main`.** Use a branch `<type>/<name>`, a Conventional Commit, and a PR with `Spec:` (and `Tests: n/a – …` only without production code), then squash-merge.

Skills: `.claude/skills/` (java-backend-standards, java-security-rbac, java-exception-handling, java-persistence-sql, event-contract, spec-driven-development).
