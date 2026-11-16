#!/usr/bin/env bash
# SPEC-PLAT-02 R22–R26: pull-request policy. Pure function of its inputs (no network), so it is unit-testable.
# Inputs (environment; never interpolated into code):
#   PR_TITLE, PR_BODY, HEAD_REF, PR_AUTHOR — from the pull_request event
#   CHANGED_FILES                 — newline-separated paths (git diff --name-only base...head)
# Exit 0 = pass, 1 = policy violation. Messages use GitHub annotations when GITHUB_ACTIONS is set.
set -uo pipefail

TYPES='feat|fix|docs|refactor|test|build|ci|chore|perf|revert'
errors=0

fail() { errors=$((errors + 1)); if [[ -n "${GITHUB_ACTIONS:-}" ]]; then echo "::error title=$1::$2"; else echo "FAIL $1: $2"; fi; }
warn() { if [[ -n "${GITHUB_ACTIONS:-}" ]]; then echo "::warning title=$1::$2"; else echo "WARN $1: $2"; fi; }

title=${PR_TITLE:-}
body=${PR_BODY:-}
branch=${HEAD_REF:-}
author=${PR_AUTHOR:-}
files=${CHANGED_FILES:-}

# R26 — branch naming (bot branches exempt)
if [[ ! "$branch" =~ ^(dependabot|renovate)/ ]] && [[ ! "$branch" =~ ^($TYPES)/[a-z0-9][a-z0-9._-]*$ ]]; then
  fail "PLAT-R26" "branch '$branch' must look like <type>/<short-name>, type one of: ${TYPES//|/, }"
fi

# R22 — Conventional Commit title (becomes the squash commit message)
if [[ ! "$title" =~ ^($TYPES)(\([a-z0-9-]+\))?!?:\ .{3,72}$ ]]; then
  fail "PLAT-R22" "PR title must be '<type>(<scope>)?: <subject>' with a 3–72 char subject, e.g. 'feat(case): add SLA timers'"
fi

# R23 — every change names its spec (spec-driven development); Dependabot's PRs are specified by R28
if [[ "$author" != 'dependabot[bot]' ]] && ! grep -qiE '^Spec:[[:space:]]*(docs/specs/[A-Za-z0-9/_.-]+\.md|n/a[[:space:]]*[-–—:][[:space:]]*.{3,})' <<<"$body"; then
  fail "PLAT-R23" "PR body needs a line 'Spec: docs/specs/<service>/<NN>-<feature>.md' (or 'Spec: n/a – <reason>')"
fi

# R24 — production code changes come with tests
main_changed=$(grep -cE '(^|/)src/main/' <<<"$files" || true)
test_changed=$(grep -cE '(^|/)src/test/' <<<"$files" || true)
if (( main_changed > 0 && test_changed == 0 )) && ! grep -qiE '^Tests:[[:space:]]*n/a[[:space:]]*[-–—:][[:space:]]*.{3,}' <<<"$body"; then
  fail "PLAT-R24" "production code changed without test changes; add parameterized tests for the spec rows (or 'Tests: n/a – <reason>')"
fi

# R25 — migrations keep the schema analysis doc honest (warning only)
if grep -qE '(^|/)db/.+\.sql$' <<<"$files" && ! grep -qx 'docs/schema.md' <<<"$files"; then
  warn "PLAT-R25" "a migration changed but docs/schema.md did not; update the schema analysis if tables/keys changed"
fi

if (( errors > 0 )); then
  echo "pr-policy: $errors violation(s)"
  exit 1
fi
echo "pr-policy: OK"
