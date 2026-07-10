#!/usr/bin/env bash
# Backward-compatible entrypoint. The implementation is the isolated immutable evidence harness.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd -P)"
FEATURE="${1:-food}"
MAXWAIT="${2:-900}"
SEED_VALUE="${SEED:-${AIBOT_TEST_SEED:-20260610}}"
PROFILE_VALUE="${AIBOT_PROFILE:-strict_survival}"

[[ "$MAXWAIT" =~ ^[1-9][0-9]*$ ]] || {
  printf '[foodtest] maxwait_seconds must be a positive integer\n' >&2
  exit 2
}

args=(--scenario "$FEATURE" --seed "$SEED_VALUE" --timeout "$MAXWAIT" --profile "$PROFILE_VALUE")
if [[ "$PROFILE_VALUE" == operator ]]; then
  args+=(--operator-capabilities "${AIBOT_OPERATOR_CAPABILITIES:-all}")
fi
if [[ "${WITH_LLM:-0}" == 1 ]]; then
  args+=(--mode llm_story --with-llm)
else
  args+=(--mode deterministic)
fi

output="$(mktemp "${TMPDIR:-/tmp}/aibot-foodtest.XXXXXX")"
trap 'rm -f -- "$output"' EXIT
set +e
"$ROOT/scripts/evidence_run.sh" "${args[@]}" | tee "$output"
status=${PIPESTATUS[0]}
set -e

evidence_dir="$(sed -n 's/^EVIDENCE_DIR=//p' "$output" | tail -1)"
if [[ -n "$evidence_dir" && -f "$evidence_dir/result.tsv" ]]; then
  printf '%s\n' '================= TEST RESULT ================='
  sed -n '1,2p' "$evidence_dir/result.tsv"
  printf '[foodtest] immutable evidence: %s\n' "$evidence_dir"
fi
exit "$status"
