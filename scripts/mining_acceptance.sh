#!/usr/bin/env bash
# Explicit Mining First evidence entrypoint. Long tiers never run implicitly.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd -P)"
# shellcheck source=scripts/lib/mining_acceptance_contract.sh
source "$ROOT/scripts/lib/mining_acceptance_contract.sh"
TIER="${1:-controlled}"
TARGET="${2:-all}"
SEEDS="${AIBOT_MINING_SEEDS:-20260610,3000,777}"
RUNS="${AIBOT_MINING_RUNS:-1}"
STARTUP_TIMEOUT="${AIBOT_MINING_STARTUP_TIMEOUT:-480}"
TIMEOUT_OVERRIDE="${AIBOT_MINING_TIMEOUT:-}"
PUBLIC_SEEDS="$MINING_PUBLIC_SEEDS"
SENTINEL_SEEDS="$MINING_SENTINEL_SEEDS"

usage() {
  cat >&2 <<'EOF'
usage: scripts/mining_acceptance.sh <controlled|prepared|from_zero> <diamond|obsidian|obsidian64|all>

Environment:
  AIBOT_MINING_SEEDS             diagnostic seeds (controlled/prepared only)
  AIBOT_MINING_RUNS              diagnostic repetitions (controlled/prepared only)
  AIBOT_MINING_TIMEOUT           per-run seconds; tier/target default otherwise
  AIBOT_MINING_STARTUP_TIMEOUT   startup seconds (default: 480)

All runs are strict_survival deterministic evidence. controlled proves only
the count/persistence/postcondition contract; prepared/from_zero are opt-in
long runs and may honestly fail while the capability remains incomplete.
from_zero is the canonical multi-seed gate: fixed 20 public seeds once, followed
by three sentinel seeds repeated three times. Its seed matrix cannot be
overridden by environment variables.
EOF
}

case "$TIER" in
  controlled) DEFAULT_TIMEOUT=600 ;;
  prepared) DEFAULT_TIMEOUT=4200 ;;
  from_zero) DEFAULT_TIMEOUT= ;;
  *) usage; exit 2 ;;
esac
case "$TARGET" in diamond|obsidian|obsidian64|all) ;; *) usage; exit 2 ;; esac
[[ "$RUNS" =~ ^[1-9][0-9]*$ && "$STARTUP_TIMEOUT" =~ ^[1-9][0-9]*$ ]] || {
  printf '[mining-acceptance] runs/timeouts must be positive integers\n' >&2
  exit 2
}
if [[ -n "$TIMEOUT_OVERRIDE" && ! "$TIMEOUT_OVERRIDE" =~ ^[1-9][0-9]*$ ]]; then
  printf '[mining-acceptance] runs/timeouts must be positive integers\n' >&2
  exit 2
fi

case "$TIER" in
  controlled)
    DIAMOND_SCENARIO=diamond_stack_64_controlled
    OBSIDIAN_SCENARIO=obsidian_half_stack_32_controlled
    OBSIDIAN64_SCENARIO=obsidian_stack_64_controlled
    ;;
  prepared)
    DIAMOND_SCENARIO=diamond_stack_64_prepared
    OBSIDIAN_SCENARIO=obsidian_half_stack_32_prepared
    OBSIDIAN64_SCENARIO=obsidian_stack_64_prepared
    ;;
  from_zero)
    DIAMOND_SCENARIO=diamond_stack_64_from_zero
    OBSIDIAN_SCENARIO=obsidian_half_stack_32_from_zero
    OBSIDIAN64_SCENARIO=obsidian_stack_64_from_zero
    ;;
esac

scenarios=()
if [[ "$TARGET" == diamond || "$TARGET" == all ]]; then scenarios+=("$DIAMOND_SCENARIO"); fi
if [[ "$TARGET" == obsidian || "$TARGET" == all ]]; then scenarios+=("$OBSIDIAN_SCENARIO"); fi
# The user-mandated full-stack tier stays explicit: `all` keeps running the sealed
# 32-contract pair only, exactly as the release gate certifies today.
if [[ "$TARGET" == obsidian64 ]]; then scenarios+=("$OBSIDIAN64_SCENARIO"); fi

cd "$ROOT"
overall=0

timeout_for_scenario() {
  local scenario="$1" target
  if [[ -n "$TIMEOUT_OVERRIDE" ]]; then
    printf '%s\n' "$TIMEOUT_OVERRIDE"
  elif [[ "$TIER" != from_zero ]]; then
    printf '%s\n' "$DEFAULT_TIMEOUT"
  else
    target="$(mining_target_for_scenario "$scenario")" || return 1
    mining_default_verify_timeout_for_target "$target"
  fi
}

run_evidence_batch() {
  local output_variable="$1" scenario="$2" seeds="$3" runs="$4" timeout="$5"
  local output status evidence_dir
  output="$(mktemp "${TMPDIR:-/tmp}/aibot-mining-acceptance.XXXXXX")"
  set +e
  bash scripts/evidence_batch.sh \
    --scenario "$scenario" \
    --seeds "$seeds" \
    --runs "$runs" \
    --timeout "$timeout" \
    --startup-timeout "$STARTUP_TIMEOUT" \
    --profile strict_survival | tee "$output"
  status=${PIPESTATUS[0]}
  set -e
  evidence_dir="$(sed -n 's/^BATCH_EVIDENCE_DIR=//p' "$output" | tail -1)"
  rm -f -- "$output"
  [[ -n "$evidence_dir" && -d "$evidence_dir" ]] || {
    printf '[mining-acceptance] evidence batch was not published (exit=%s)\n' "$status" >&2
    return 1
  }
  printf -v "$output_variable" '%s' "$evidence_dir"
  return "$status"
}

if [[ "$TIER" == from_zero ]]; then
  if [[ -n "${AIBOT_MINING_SEEDS+x}" || ( -n "${AIBOT_MINING_RUNS+x}" && "$RUNS" != 1 ) ]]; then
    printf '[mining-acceptance] from_zero release seeds/runs are fixed; use prepared for custom diagnostics\n' >&2
    exit 2
  fi
  for scenario in "${scenarios[@]}"; do
    TIMEOUT="$(timeout_for_scenario "$scenario")" || exit 2
    target=diamond
    [[ "$scenario" != obsidian_half_stack_32_from_zero ]] || target=obsidian
    [[ "$scenario" != obsidian_stack_64_from_zero ]] || target=obsidian64
    printf '[mining-acceptance] tier=from_zero scenario=%s primary_seeds=%s sentinel_seeds=%s profile=strict_survival verify_timeout_seconds=%s\n' \
      "$scenario" "$PUBLIC_SEEDS" "$SENTINEL_SEEDS" "$TIMEOUT"
    primary_batch=""
    sentinel_batch=""
    # evidence_batch intentionally exits non-zero when even one run FAILs.
    # The multi-seed gate, not that all-pass diagnostic exit code, owns the 18/20
    # acceptance policy, so retain every sealed batch for an honest verdict.
    run_evidence_batch primary_batch "$scenario" "$PUBLIC_SEEDS" 1 "$TIMEOUT" || true
    run_evidence_batch sentinel_batch "$scenario" "$SENTINEL_SEEDS" 3 "$TIMEOUT" || true
    if [[ -z "$primary_batch" || -z "$sentinel_batch" ]] || \
       ! bash scripts/mining_release_gate.sh --target "$target" \
          --primary-batch "$primary_batch" --sentinel-batch "$sentinel_batch"; then
      overall=1
    fi
  done
  exit "$overall"
fi

for scenario in "${scenarios[@]}"; do
  TIMEOUT="$(timeout_for_scenario "$scenario")" || exit 2
  printf '[mining-acceptance] tier=%s scenario=%s seeds=%s runs=%s profile=strict_survival verify_timeout_seconds=%s\n' \
    "$TIER" "$scenario" "$SEEDS" "$RUNS" "$TIMEOUT"
  set +e
  bash scripts/evidence_batch.sh \
    --scenario "$scenario" \
    --seeds "$SEEDS" \
    --runs "$RUNS" \
    --timeout "$TIMEOUT" \
    --startup-timeout "$STARTUP_TIMEOUT" \
    --profile strict_survival
  status=$?
  set -e
  [[ $status -eq 0 ]] || overall=1
done

exit "$overall"
