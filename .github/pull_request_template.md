<!-- Title: Conventional Commits, e.g. "feat(case): add SLA timers" (it becomes the squash commit message). -->

## What and why
<!-- One or two sentences. Link the requirement IDs (e.g. CASE-4, ACC-6). -->

Spec: docs/specs/<service>/<NN>-<feature>.md
<!-- or: Spec: n/a – <reason> (dependency bump, typo, CI-only) -->

## Spec rules / examples covered
<!-- e.g. CASE-R20–R27 / CASE-E20–E35 -->

## How it was verified
- [ ] `mvn -B verify` green locally (paste the totals): `Tests run: …, Failures: 0`
- [ ] New/changed behaviour has **parameterized** tests whose rows are the spec examples
- [ ] growercare-local-platform `smoke.sh` passes (if endpoints or security changed)
<!-- If there is production code without test changes, add: Tests: n/a – <reason> -->

## Checklist
- [ ] Spec status and traceability table updated
- [ ] API change → `contracts/*.openapi.json` regenerated and reviewed
- [ ] Migration → expand/contract, shard-lock first line, `docs/schema.md` updated
- [ ] Security: permission (403) and scope (404) rules covered by the isolation matrix
- [ ] No secrets, PII or real customer data in code, tests, logs or fixtures
- [ ] Decision changed → superseding ADR in `docs/architecture/adr/`
- [ ] Affected skills in `.claude/skills/` updated
