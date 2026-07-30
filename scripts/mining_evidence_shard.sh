#!/usr/bin/env bash
# Run exactly one canonical Mining First from-zero shard and seal a descriptor
# that binds its matrix coordinates to one immutable evidence bundle.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd -P)"
HARNESS_REPO_ROOT="$ROOT"
# shellcheck source=scripts/lib/harness.sh
source "$ROOT/scripts/lib/harness.sh"
# shellcheck source=scripts/lib/mining_acceptance_contract.sh
source "$ROOT/scripts/lib/mining_acceptance_contract.sh"

SHARD_ROOT="$ROOT/artifacts/mining-shards"

usage() {
  cat >&2 <<'EOF'
usage: scripts/mining_evidence_shard.sh <diamond|obsidian> <primary|sentinel> <seed> <run-index>
       scripts/mining_evidence_shard.sh --matrix-json
       scripts/mining_evidence_shard.sh --self-test

The assignment must be one member of the canonical 58-shard matrix. A valid
PASS, FAIL, or ERROR scenario result is published as evidence; the script only
fails when the run cannot produce structurally VERIFIED evidence.
EOF
}

emit_matrix_json() {
  local first=1 target seed run_index old_ifs
  printf '{"include":['
  for target in diamond obsidian; do
    old_ifs="$IFS"
    IFS=','
    for seed in $MINING_PUBLIC_SEEDS; do
      [[ $first -eq 1 ]] || printf ','
      first=0
      printf '{"target":"%s","role":"primary","seed":"%s","run_index":"1"}' "$target" "$seed"
    done
    for seed in $MINING_SENTINEL_SEEDS; do
      for run_index in 1 2 3; do
        [[ $first -eq 1 ]] || printf ','
        first=0
        printf '{"target":"%s","role":"sentinel","seed":"%s","run_index":"%s"}' \
          "$target" "$seed" "$run_index"
      done
    done
    IFS="$old_ifs"
  done
  printf ']}\n'
}

validate_assignment() {
  local target="$1" role="$2" seed="$3" run_index="$4"
  case "$target" in diamond|obsidian) ;; *) return 1 ;; esac
  harness_safe_seed "$seed" >/dev/null 2>&1 || return 1
  case "$role" in
    primary)
      [[ "$run_index" == 1 ]] && mining_seed_in_csv "$seed" "$MINING_PUBLIC_SEEDS"
      ;;
    sentinel)
      [[ "$run_index" =~ ^[123]$ ]] && mining_seed_in_csv "$seed" "$MINING_SENTINEL_SEEDS"
      ;;
    *) return 1 ;;
  esac
}

if [[ "${1:-}" == --matrix-json ]]; then
  [[ $# -eq 1 ]] || { usage; exit 2; }
  emit_matrix_json
  exit 0
fi

if [[ "${1:-}" == --self-test ]]; then
  [[ $# -eq 1 ]] || { usage; exit 2; }
  emit_matrix_json | python3 -c '
import json, sys
data = json.load(sys.stdin)
rows = data.get("include")
assert isinstance(rows, list) and len(rows) == 58
keys = {(r["target"], r["role"], r["seed"], r["run_index"]) for r in rows}
assert len(keys) == 58
assert sum(r["role"] == "primary" for r in rows) == 40
assert sum(r["role"] == "sentinel" for r in rows) == 18
'
  validate_assignment diamond primary 3000 1
  validate_assignment obsidian sentinel 777 3
  ! validate_assignment diamond primary 777 1
  ! validate_assignment obsidian sentinel 777 4
  [[ "$(mining_default_verify_timeout_for_target diamond)" == 172800 ]]
  [[ "$(mining_default_verify_timeout_for_target obsidian)" == 18000 ]]
  mining_verify_timeout_covers_ticks 141334 2120000
  ! mining_verify_timeout_covers_ticks 141333 2120000
  mining_verify_timeout_covers_ticks 172800 2592000
  ! mining_verify_timeout_covers_ticks 172799 2592000
  printf '[mining-evidence-shard] self-test PASS\n'
  exit 0
fi

[[ $# -eq 4 ]] || { usage; exit 2; }
TARGET="$1"
ROLE="$2"
SEED="$3"
RUN_INDEX="$4"
validate_assignment "$TARGET" "$ROLE" "$SEED" "$RUN_INDEX" || {
  printf '[mining-evidence-shard] assignment is outside the canonical matrix: %s/%s/%s/%s\n' \
    "$TARGET" "$ROLE" "$SEED" "$RUN_INDEX" >&2
  exit 2
}

SCENARIO="$(mining_scenario_for_target "$TARGET")"
SHARD_ID="$(mining_shard_id "$TARGET" "$ROLE" "$SEED" "$RUN_INDEX")"
TIMEOUT="${AIBOT_MINING_TIMEOUT:-$(mining_default_verify_timeout_for_target "$TARGET")}"
STARTUP_TIMEOUT="${AIBOT_MINING_STARTUP_TIMEOUT:-480}"
[[ "$TIMEOUT" =~ ^[1-9][0-9]*$ && "$TIMEOUT" -le "$MINING_MAX_VERIFY_TIMEOUT_SECONDS" \
    && "$STARTUP_TIMEOUT" =~ ^[1-9][0-9]*$ && "$STARTUP_TIMEOUT" -le 1800 ]] || {
  printf '[mining-evidence-shard] invalid timeout configuration\n' >&2
  exit 2
}

cd "$ROOT"
OUTPUT="$(mktemp "${TMPDIR:-/tmp}/aibot-mining-shard.XXXXXX")"
STAGING=""
PUBLISHED=0
cleanup() {
  local status=$?
  trap - EXIT HUP INT TERM
  rm -f -- "$OUTPUT"
  if [[ $PUBLISHED -eq 0 && -n "$STAGING" ]]; then
    harness_safe_remove_staging "$STAGING" "$SHARD_ROOT" || true
  fi
  exit "$status"
}
trap cleanup EXIT
trap 'exit 129' HUP
trap 'exit 130' INT
trap 'exit 143' TERM

set +e
bash "$ROOT/scripts/evidence_run.sh" \
  --scenario "$SCENARIO" \
  --seed "$SEED" \
  --timeout "$TIMEOUT" \
  --startup-timeout "$STARTUP_TIMEOUT" \
  --profile strict_survival | tee "$OUTPUT"
RUN_STATUS=${PIPESTATUS[0]}
set -e
EVIDENCE_DIR="$(sed -n 's/^EVIDENCE_DIR=//p' "$OUTPUT" | tail -1)"
[[ -n "$EVIDENCE_DIR" && -d "$EVIDENCE_DIR" && ! -L "$EVIDENCE_DIR" ]] || {
  printf '[mining-evidence-shard] run did not publish evidence (exit=%s)\n' "$RUN_STATUS" >&2
  exit 3
}
"$ROOT/scripts/evidence_validate.sh" --require-verified "$EVIDENCE_DIR" >/dev/null || {
  printf '[mining-evidence-shard] run evidence is not structurally VERIFIED\n' >&2
  exit 3
}

RUN_MANIFEST="$EVIDENCE_DIR/manifest.tsv"
RUN_RESULT="$(harness_manifest_get "$RUN_MANIFEST" result)"
RUN_COMMIT="$(harness_manifest_get "$RUN_MANIFEST" commit_sha)"
case "$RUN_RESULT:$RUN_STATUS" in
  PASS:0|FAIL:1|ERROR:1) ;;
  *)
    printf '[mining-evidence-shard] run exit/result mismatch: result=%s exit=%s\n' "$RUN_RESULT" "$RUN_STATUS" >&2
    exit 3
    ;;
esac
if [[ "$RUN_RESULT" == PASS ]] && ! mining_pass_has_physical_provenance "$TARGET" "$RUN_MANIFEST"; then
  printf '[mining-evidence-shard] PASS run lacks physical provenance\n' >&2
  exit 3
fi
[[ "$(harness_manifest_get "$RUN_MANIFEST" scenario)" == "$SCENARIO" \
    && "$(harness_manifest_get "$RUN_MANIFEST" requested_seed)" == "$SEED" \
    && "$(harness_manifest_get "$RUN_MANIFEST" actual_seed)" == "$SEED" \
    && "$(harness_manifest_get "$RUN_MANIFEST" actual_seed_verified)" == yes \
    && "$(harness_manifest_get "$RUN_MANIFEST" profile)" == strict_survival \
    && "$(harness_manifest_get "$RUN_MANIFEST" mode)" == deterministic \
    && "$(harness_manifest_get "$RUN_MANIFEST" operator_capabilities)" == "$MINING_STRICT_CAPABILITIES" ]] || {
  printf '[mining-evidence-shard] run provenance does not match its shard assignment\n' >&2
  exit 3
}
[[ "$(git rev-parse HEAD 2>/dev/null)" == "$RUN_COMMIT" \
    && -z "$(git status --porcelain=v1 --untracked-files=all 2>/dev/null)" ]] || {
  printf '[mining-evidence-shard] worktree or revision changed after the sealed run\n' >&2
  exit 3
}

harness_prepare_root "$SHARD_ROOT" || exit 3
harness_assert_not_symlink "$ROOT/artifacts" artifacts_directory || exit 3
FINAL="$SHARD_ROOT/$SHARD_ID"
[[ ! -e "$FINAL" && ! -L "$FINAL" ]] || {
  printf '[mining-evidence-shard] refusing to overwrite shard descriptor: %s\n' "$SHARD_ID" >&2
  exit 3
}
STAGING="$(mktemp -d "$SHARD_ROOT/.staging.${SHARD_ID}.XXXXXX")" || exit 3
EVIDENCE_PATH="${EVIDENCE_DIR#"$ROOT"/}"
[[ "$EVIDENCE_PATH" != "$EVIDENCE_DIR" && "$EVIDENCE_PATH" =~ ^artifacts/evidence/[A-Za-z0-9][A-Za-z0-9_.+-]*$ ]] || exit 3
EVIDENCE_HASH="$(harness_sha256 "$EVIDENCE_DIR/LOCKED")" || exit 3
{
  printf 'schema_version\t1\n'
  printf 'shard_id\t%s\n' "$SHARD_ID"
  printf 'target\t%s\n' "$TARGET"
  printf 'role\t%s\n' "$ROLE"
  printf 'scenario\t%s\n' "$SCENARIO"
  printf 'seed\t%s\n' "$SEED"
  printf 'run_index\t%s\n' "$RUN_INDEX"
  printf 'evidence_path\t%s\n' "$EVIDENCE_PATH"
  printf 'evidence_lock_sha256\t%s\n' "$EVIDENCE_HASH"
  printf 'created_at_utc\t%s\n' "$(harness_now_utc)"
} > "$STAGING/manifest.tsv"
printf '%s  manifest.tsv\n' "$(harness_sha256 "$STAGING/manifest.tsv")" > "$STAGING/checksums.sha256"
harness_write_locked_marker "$STAGING" || exit 3
harness_publish_staging "$STAGING" "$FINAL" || exit 3
PUBLISHED=1
STAGING=""

[[ "$(git rev-parse HEAD 2>/dev/null)" == "$RUN_COMMIT" \
    && -z "$(git status --porcelain=v1 --untracked-files=all 2>/dev/null)" ]] || {
  printf '[mining-evidence-shard] worktree or revision changed while publishing the descriptor\n' >&2
  exit 3
}

printf 'MINING_SHARD_DIR=%s\n' "$FINAL"
printf 'MINING_SHARD_RESULT=%s\n' "$RUN_RESULT"
printf 'MINING_SHARD_EVIDENCE=%s\n' "$EVIDENCE_DIR"
