#!/usr/bin/env bash
# Run an explicit seed/run matrix. It never selects or pins a baseline.
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd -P)"
HARNESS_REPO_ROOT="$ROOT"
# shellcheck source=scripts/lib/harness.sh
source "$ROOT/scripts/lib/harness.sh"

usage() {
  cat >&2 <<'EOF'
usage: scripts/evidence_batch.sh --scenario <feature> --seeds <s1,s2,...> [options]

Options:
  --runs <count>                 repetitions per seed (default: 1)
  --timeout <seconds>            per-run verify timeout (default: 900)
  --startup-timeout <seconds>    per-run startup timeout (default: 480)
  --profile <strict_survival|operator>
  --operator-capabilities <csv>  forwarded to evidence_run.sh
  --mode <deterministic|llm_story>
  --with-llm
  --fixture-log <file>           synthetic structural test; never VERIFIED
  --allow-unverified             diagnostic only; release batches reject it
EOF
}

SCENARIO=""
SEED_CSV=""
RUNS=1
TIMEOUT=900
STARTUP_TIMEOUT=480
PROFILE=strict_survival
CAPABILITIES=""
MODE=deterministic
WITH_LLM=0
FIXTURE_LOG=""
REQUIRE_VERIFIED=1

while [[ $# -gt 0 ]]; do
  case "$1" in
    --scenario) [[ $# -ge 2 ]] || { usage; exit 2; }; SCENARIO="$2"; shift 2 ;;
    --seeds) [[ $# -ge 2 ]] || { usage; exit 2; }; SEED_CSV="$2"; shift 2 ;;
    --runs) [[ $# -ge 2 ]] || { usage; exit 2; }; RUNS="$2"; shift 2 ;;
    --timeout) [[ $# -ge 2 ]] || { usage; exit 2; }; TIMEOUT="$2"; shift 2 ;;
    --startup-timeout) [[ $# -ge 2 ]] || { usage; exit 2; }; STARTUP_TIMEOUT="$2"; shift 2 ;;
    --profile) [[ $# -ge 2 ]] || { usage; exit 2; }; PROFILE="$2"; shift 2 ;;
    --operator-capabilities) [[ $# -ge 2 ]] || { usage; exit 2; }; CAPABILITIES="$2"; shift 2 ;;
    --mode) [[ $# -ge 2 ]] || { usage; exit 2; }; MODE="$2"; shift 2 ;;
    --with-llm) WITH_LLM=1; shift ;;
    --fixture-log) [[ $# -ge 2 ]] || { usage; exit 2; }; FIXTURE_LOG="$2"; shift 2 ;;
    --allow-unverified) REQUIRE_VERIFIED=0; shift ;;
    -h|--help) usage; exit 0 ;;
    *) printf 'evidence-batch: unknown argument: %s\n' "$1" >&2; usage; exit 2 ;;
  esac
done

harness_safe_id "$SCENARIO" scenario 96 || exit 2
[[ -n "$SEED_CSV" ]] || { printf 'evidence-batch: --seeds is required\n' >&2; exit 2; }
case "$PROFILE" in strict_survival|operator) ;; *) printf 'evidence-batch: invalid profile: %s\n' "$PROFILE" >&2; exit 2 ;; esac
case "$MODE" in deterministic|llm_story) ;; *) printf 'evidence-batch: invalid mode: %s\n' "$MODE" >&2; exit 2 ;; esac
if [[ -n "$FIXTURE_LOG" && $WITH_LLM -eq 1 ]]; then
  printf 'evidence-batch: fixture mode cannot use --with-llm\n' >&2
  exit 2
fi
if [[ -z "$FIXTURE_LOG" && "$MODE" == llm_story && $WITH_LLM -ne 1 ]]; then
  printf 'evidence-batch: llm_story mode requires --with-llm\n' >&2
  exit 2
fi
if [[ -z "$FIXTURE_LOG" && "$MODE" == deterministic && $WITH_LLM -eq 1 ]]; then
  printf 'evidence-batch: --with-llm requires --mode llm_story\n' >&2
  exit 2
fi
EFFECTIVE_BATCH_MODE="$MODE"
[[ -z "$FIXTURE_LOG" ]] || EFFECTIVE_BATCH_MODE=fixture
BATCH_LLM_ENABLED=no
[[ $WITH_LLM -eq 0 ]] || BATCH_LLM_ENABLED=yes
BATCH_HIDDEN_SCAN=false
BATCH_EMERGENCY_TELEPORT=false
BATCH_FORCED_PICKUP=false
BATCH_MANUAL_TELEPORT=false
if [[ "$PROFILE" == operator ]]; then
  if [[ -z "$CAPABILITIES" || "$CAPABILITIES" == all ]]; then
    BATCH_HIDDEN_SCAN=true
    BATCH_EMERGENCY_TELEPORT=true
    BATCH_FORCED_PICKUP=true
    BATCH_MANUAL_TELEPORT=true
  elif [[ "$CAPABILITIES" != none ]]; then
    old_cap_ifs="$IFS"
    IFS=','
    for capability in $CAPABILITIES; do
      case "$capability" in
        hiddenBlockScan) BATCH_HIDDEN_SCAN=true ;;
        emergencyTeleport) BATCH_EMERGENCY_TELEPORT=true ;;
        forcedPickup) BATCH_FORCED_PICKUP=true ;;
        manualTeleport) BATCH_MANUAL_TELEPORT=true ;;
        *) printf 'evidence-batch: invalid operator capability: %s\n' "$capability" >&2; exit 2 ;;
      esac
    done
    IFS="$old_cap_ifs"
  fi
elif [[ -n "$CAPABILITIES" && "$CAPABILITIES" != none ]]; then
  printf 'evidence-batch: strict_survival cannot enable operator capabilities\n' >&2
  exit 2
fi
BATCH_CAPABILITIES="hiddenBlockScan=$BATCH_HIDDEN_SCAN,emergencyTeleport=$BATCH_EMERGENCY_TELEPORT,forcedPickup=$BATCH_FORCED_PICKUP,manualTeleport=$BATCH_MANUAL_TELEPORT"
[[ "$RUNS" =~ ^[1-9][0-9]*$ && "$TIMEOUT" =~ ^[1-9][0-9]*$ && "$STARTUP_TIMEOUT" =~ ^[1-9][0-9]*$ ]] || {
  printf 'evidence-batch: runs and timeouts must be positive integers\n' >&2
  exit 2
}
[[ "$RUNS" -le 100 && "$TIMEOUT" -le 86400 && "$STARTUP_TIMEOUT" -le 1800 ]] || {
  printf 'evidence-batch: safety limit exceeded (runs=100, verify=86400, startup=1800)\n' >&2
  exit 2
}

old_ifs="$IFS"
IFS=',' read -r -a SEEDS <<< "$SEED_CSV"
IFS="$old_ifs"
[[ ${#SEEDS[@]} -gt 0 ]] || { printf 'evidence-batch: no seeds supplied\n' >&2; exit 2; }
[[ ${#SEEDS[@]} -le 100 ]] || { printf 'evidence-batch: at most 100 seeds are allowed\n' >&2; exit 2; }
for seed in "${SEEDS[@]}"; do harness_safe_seed "$seed" || exit 2; done
if ! printf '%s\n' "${SEEDS[@]}" | awk 'seen[$0]++ { exit 2 }'; then
  printf 'evidence-batch: duplicate seeds are not allowed\n' >&2
  exit 2
fi

cd "$ROOT" || exit 1
if ! REVISION="$(git rev-parse HEAD 2>/dev/null)"; then REVISION=unknown; fi
if ! BATCH_GIT_STATUS_START="$(git status --porcelain=v1 --untracked-files=all 2>/dev/null)"; then
  BATCH_WORKTREE_START=unknown
elif [[ -n "$BATCH_GIT_STATUS_START" ]]; then BATCH_WORKTREE_START=dirty
else BATCH_WORKTREE_START=clean
fi
BATCH_ID="$(harness_run_id "batch-$SCENARIO" "$REVISION")"
harness_prepare_root "$HARNESS_BATCH_ROOT" || exit 3
STAGING="$(mktemp -d "$HARNESS_BATCH_ROOT/.staging.${BATCH_ID}.XXXXXX")" || exit 3
FINAL="$HARNESS_BATCH_ROOT/$BATCH_ID"
TMP_OUTPUT="$(mktemp "${TMPDIR:-/tmp}/aibot-evidence-batch.XXXXXX")" || exit 3
PUBLISHED=0
BATCH_CHILD_PID=""

cleanup() {
  local status=$?
  trap - EXIT HUP INT TERM
  if [[ -n "$BATCH_CHILD_PID" ]] && kill -0 "$BATCH_CHILD_PID" 2>/dev/null; then
    kill -TERM "$BATCH_CHILD_PID" 2>/dev/null || true
    for ((_child_wait = 0; _child_wait < 25; _child_wait++)); do
      kill -0 "$BATCH_CHILD_PID" 2>/dev/null || break
      sleep 1
    done
    if kill -0 "$BATCH_CHILD_PID" 2>/dev/null; then harness_kill_tree "$BATCH_CHILD_PID" KILL; fi
    wait "$BATCH_CHILD_PID" 2>/dev/null || true
  fi
  rm -f -- "$TMP_OUTPUT"
  if [[ $PUBLISHED -eq 0 ]]; then harness_safe_remove_staging "$STAGING" "$HARNESS_BATCH_ROOT" || true; fi
  exit "$status"
}
trap cleanup EXIT
trap 'exit 129' HUP
trap 'exit 130' INT
trap 'exit 143' TERM

STARTED="$(harness_now_utc)"
printf 'seed\trun_index\texit_code\tevidence_state\tresult\tevidence_path\tevidence_lock_sha256\n' > "$STAGING/result.tsv" || exit 3
overall=0
batch_state=VERIFIED
batch_reason=complete
for seed in "${SEEDS[@]}"; do
  for ((run_index = 1; run_index <= RUNS; run_index++)); do
    command=("$ROOT/scripts/evidence_run.sh" --scenario "$SCENARIO" --seed "$seed" \
      --timeout "$TIMEOUT" --startup-timeout "$STARTUP_TIMEOUT" --profile "$PROFILE" --mode "$MODE")
    [[ -z "$CAPABILITIES" ]] || command+=(--operator-capabilities "$CAPABILITIES")
    [[ $WITH_LLM -eq 0 ]] || command+=(--with-llm)
    [[ -z "$FIXTURE_LOG" ]] || command+=(--fixture-log "$FIXTURE_LOG")

    : > "$TMP_OUTPUT"
    "${command[@]}" > "$TMP_OUTPUT" 2>&1 &
    BATCH_CHILD_PID=$!
    if wait "$BATCH_CHILD_PID"; then run_exit=0; else run_exit=$?; fi
    BATCH_CHILD_PID=""
    sed -n '1,240p' "$TMP_OUTPUT"
    evidence_dir="$(sed -n 's/^EVIDENCE_DIR=//p' "$TMP_OUTPUT" | tail -1)"
    evidence_state=UNVERIFIED
    run_result=ERROR
    evidence_path=-
    evidence_hash=-
    structural_valid=0
    policy_valid=0
    if [[ -n "$evidence_dir" && -d "$evidence_dir" && ! -L "$evidence_dir" ]]; then
      "$ROOT/scripts/evidence_validate.sh" "$evidence_dir" >/dev/null 2>&1 && structural_valid=1
      if [[ $structural_valid -eq 1 ]]; then
        evidence_state="$(harness_manifest_get "$evidence_dir/manifest.tsv" evidence_state)"
        run_result="$(harness_manifest_get "$evidence_dir/manifest.tsv" result)"
        evidence_path="${evidence_dir#"$ROOT"/}"
        evidence_hash="$(harness_sha256 "$evidence_dir/LOCKED")"
        if [[ $REQUIRE_VERIFIED -eq 0 || "$evidence_state" == VERIFIED ]]; then policy_valid=1; fi
      fi
    fi
    if [[ $run_exit -ne 0 || $policy_valid -ne 1 || "$run_result" != PASS ]]; then overall=1; fi
    if [[ "$evidence_state" != VERIFIED ]]; then
      batch_state=UNVERIFIED
      batch_reason=one_or_more_runs_unverified
    fi
    printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\n' "$seed" "$run_index" "$run_exit" \
      "$evidence_state" "$run_result" "$evidence_path" "$evidence_hash" >> "$STAGING/result.tsv" || exit 3
  done
done

if ! REVISION_END="$(git rev-parse HEAD 2>/dev/null)"; then REVISION_END=unknown; fi
if ! BATCH_GIT_STATUS_END="$(git status --porcelain=v1 --untracked-files=all 2>/dev/null)"; then
  BATCH_WORKTREE_END=unknown
elif [[ -n "$BATCH_GIT_STATUS_END" ]]; then BATCH_WORKTREE_END=dirty
else BATCH_WORKTREE_END=clean
fi
if [[ "$BATCH_WORKTREE_START" != clean || "$BATCH_WORKTREE_END" != clean || "$REVISION" != "$REVISION_END" || "$REVISION" == unknown ]]; then
  batch_state=UNVERIFIED
  batch_reason=batch_worktree_or_revision_not_stable
  [[ $REQUIRE_VERIFIED -eq 0 ]] || overall=1
fi

{
  printf 'schema_version\t1\n'
  printf 'batch_id\t%s\n' "$BATCH_ID"
  printf 'evidence_state\t%s\n' "$batch_state"
  printf 'verification_reason\t%s\n' "$batch_reason"
  printf 'commit_sha\t%s\n' "$REVISION"
  printf 'finished_commit_sha\t%s\n' "$REVISION_END"
  printf 'working_tree_state\t%s\n' "$BATCH_WORKTREE_START"
  printf 'working_tree_state_end\t%s\n' "$BATCH_WORKTREE_END"
  printf 'timestamp_utc\t%s\n' "$STARTED"
  printf 'finished_at_utc\t%s\n' "$(harness_now_utc)"
  printf 'scenario\t%s\n' "$SCENARIO"
  printf 'seeds\t%s\n' "$SEED_CSV"
  printf 'runs_per_seed\t%s\n' "$RUNS"
  printf 'profile\t%s\n' "$PROFILE"
  printf 'operator_capabilities\t%s\n' "$BATCH_CAPABILITIES"
  printf 'mode\t%s\n' "$EFFECTIVE_BATCH_MODE"
  printf 'llm_enabled\t%s\n' "$BATCH_LLM_ENABLED"
  printf 'requires_verified\t%s\n' "$([[ $REQUIRE_VERIFIED -eq 1 ]] && printf yes || printf no)"
  printf 'dependency_root\tartifacts/evidence\n'
  printf 'result\t%s\n' "$([[ $overall -eq 0 ]] && printf PASS || printf FAIL)"
} > "$STAGING/manifest.tsv" || exit 3

{
  printf '%s  manifest.tsv\n' "$(harness_sha256 "$STAGING/manifest.tsv")"
  printf '%s  result.tsv\n' "$(harness_sha256 "$STAGING/result.tsv")"
} > "$STAGING/checksums.sha256" || exit 3
{
  printf 'schema_version\t1\n'
  printf 'sealed_at_utc\t%s\n' "$(harness_now_utc)"
  printf 'checksums_sha256\t%s\n' "$(harness_sha256 "$STAGING/checksums.sha256")"
} > "$STAGING/LOCKED" || exit 3
harness_assert_no_tree_symlinks "$STAGING" || exit 3
harness_publish_staging "$STAGING" "$FINAL" || exit 3
PUBLISHED=1
if ! "$ROOT/scripts/evidence_validate.sh" "$FINAL" >/dev/null; then
  printf 'evidence-batch: published batch failed full validation\n' >&2
  printf 'BATCH_EVIDENCE_DIR=%s\n' "$FINAL"
  exit 3
fi
printf 'BATCH_EVIDENCE_DIR=%s\n' "$FINAL"
exit "$overall"
