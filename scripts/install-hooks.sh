#!/usr/bin/env bash
# SPEC-PLAT-02 R29: use the versioned hooks in .githooks/ for this clone.
set -euo pipefail
cd "$(dirname "$0")/.."
git config core.hooksPath .githooks
chmod +x .githooks/* 2>/dev/null || true
git update-index --chmod=+x .githooks/* scripts/*.sh 2>/dev/null || true
echo "hooks installed: $(git config core.hooksPath) (commit-msg, pre-push)"
