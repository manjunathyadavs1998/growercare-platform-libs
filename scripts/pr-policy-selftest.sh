#!/usr/bin/env bash
# SPEC-PLAT-02 examples PLAT-E30…E42 as table-driven cases for scripts/pr-policy.sh (runs in CI before the policy).
set -uo pipefail
cd "$(dirname "$0")"
GOOD_TITLE='feat(case): add SLA timers'
GOOD_BODY='Spec: docs/specs/case/04-sla-timers.md'
GOOD_BRANCH='feat/sla-timers'
MAIN='src/main/java/X.java'
TEST='src/test/java/XTest.java'
passed=0; failed=0

# id | expected (pass|fail) | title | body | branch | files (\n-separated) | author (default: someone)
run() {
  local id=$1 expected=$2
  PR_TITLE=$3 PR_BODY=$(printf '%b' "$4") HEAD_REF=$5 CHANGED_FILES=$(printf '%b' "$6") PR_AUTHOR=${7:-someone} GITHUB_ACTIONS= \
    bash ./pr-policy.sh >/dev/null 2>&1
  local actual=$([[ $? == 0 ]] && echo pass || echo fail)
  if [[ $actual == "$expected" ]]; then passed=$((passed + 1)); echo "ok   $id ($expected)";
  else failed=$((failed + 1)); echo "FAIL $id expected $expected got $actual"; fi
}

run PLAT-E30 pass "$GOOD_TITLE"            "$GOOD_BODY"                          "$GOOD_BRANCH" "$MAIN\n$TEST"
run PLAT-E31 fail "Added stuff"             "$GOOD_BODY"                          "$GOOD_BRANCH" "$TEST"
run PLAT-E32 fail "fix: x"                  "$GOOD_BODY"                          "$GOOD_BRANCH" "$TEST"
run PLAT-E33 pass "$GOOD_TITLE"            "Context\nSpec: docs/specs/case/04-sla-timers.md" "$GOOD_BRANCH" "$TEST"
run PLAT-E34 pass "build: bump spring boot" "Spec: n/a – dependency bump"         "build/boot-4.1.3" "pom.xml"
run PLAT-E35 fail "$GOOD_TITLE"            "No spec line here"                   "$GOOD_BRANCH" "$TEST"
run PLAT-E36 fail "$GOOD_TITLE"            "$GOOD_BODY"                          "$GOOD_BRANCH" "$MAIN"
run PLAT-E37 pass "$GOOD_TITLE"            "$GOOD_BODY"                          "$GOOD_BRANCH" "$MAIN\n$TEST"
run PLAT-E38 pass "$GOOD_TITLE"            "$GOOD_BODY\nTests: n/a – log message only" "$GOOD_BRANCH" "$MAIN"
run PLAT-E39a pass "$GOOD_TITLE"           "$GOOD_BODY"                          "feat/sla-timers" "$TEST"
run PLAT-E39b fail "$GOOD_TITLE"           "$GOOD_BODY"                          "my-branch"    "$TEST"
run PLAT-E40 pass "build(deps): bump x"     "Spec: n/a – dependency bump"         "dependabot/maven/x-1.2" "pom.xml"
run PLAT-E41 pass "build(deps): bump postgres" "Bumps postgres." "dependabot/docker_compose/postgres-17.11" "docker-compose.yml" "dependabot[bot]"
run PLAT-E42 fail "build(deps): bump postgres" "Bumps postgres." "dependabot/x"                            "docker-compose.yml" "someone"

echo "pr-policy self-test: $passed passed, $failed failed"
exit "$failed"
