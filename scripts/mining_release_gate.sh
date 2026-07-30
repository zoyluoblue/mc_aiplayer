#!/usr/bin/env bash
# Validate the multi-seed component of the Mining First release evidence. This script only
# derives a verdict from two already-sealed batches; it never selects a batch
# by scanning directories and never pins a capability baseline.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd -P)"
# shellcheck source=scripts/lib/mining_acceptance_contract.sh
source "$ROOT/scripts/lib/mining_acceptance_contract.sh"
PUBLIC_SEEDS="$MINING_PUBLIC_SEEDS"
SENTINEL_SEEDS="$MINING_SENTINEL_SEEDS"
STRICT_CAPABILITIES="$MINING_STRICT_CAPABILITIES"

usage() {
  cat >&2 <<'EOF'
usage: scripts/mining_release_gate.sh --target <diamond|obsidian> \
  --primary-batch <artifacts/evidence-batches/batch-id> \
  --sentinel-batch <artifacts/evidence-batches/batch-id>
       scripts/mining_release_gate.sh --self-test

The primary batch must contain the canonical 20 public seeds once each. The
sentinel batch must contain 3000,20260610,777 three times each. Both batches
must be VERIFIED strict_survival deterministic evidence from one commit and
one effective config. A release verdict requires at least 18/20 primary PASS
and 9/9 sentinel PASS.
EOF
}

manifest_get() {
  local file="$1" key="$2"
  awk -F '\t' -v wanted="$key" '$1 == wanted { print substr($0, index($0, "\t") + 1); exit }' "$file"
}

meets_release_counts() {
  local primary_pass="$1" primary_total="$2" sentinel_pass="$3" sentinel_total="$4"
  [[ "$primary_pass" =~ ^[0-9]+$ && "$primary_total" == 20 \
      && "$primary_pass" -ge 18 && "$sentinel_pass" == 9 && "$sentinel_total" == 9 ]]
}

if [[ "${1:-}" == --self-test ]]; then
  [[ $# -eq 1 ]] || { usage; exit 2; }
  meets_release_counts 18 20 9 9
  meets_release_counts 20 20 9 9
  ! meets_release_counts 17 20 9 9
  ! meets_release_counts 18 19 9 9
  ! meets_release_counts 18 20 8 9
  provenance_fixture="$(mktemp "${TMPDIR:-/tmp}/aibot-mining-gate-self-test.XXXXXX")"
  trap 'rm -f -- "$provenance_fixture"' EXIT
  {
    printf 'schema_version\t2\nresult\tPASS\nmining_provenance_schema\t2\n'
    printf 'verify_timeout_seconds\t172800\nscenario_timeout_ticks\t2120000\n'
    printf 'mining_provenance_verdict\tPASS\nsurvival_observed_ticks\t100\n'
    printf 'game_mode_violations\t0\nprivileged_allowed_count\t0\ndeath_delta\t0\n'
    printf 'mining_final_inventory\t64\n'
    printf 'diamond_natural_ore_breaks\t64\ndiamond_native_drops\t64\ndiamond_physical_pickups\t64\n'
  } > "$provenance_fixture"
  mining_pass_has_physical_provenance diamond "$provenance_fixture"
  sed -i.bak $'s/^verify_timeout_seconds\t172800$/verify_timeout_seconds\t141333/' "$provenance_fixture"
  rm -f -- "$provenance_fixture.bak"
  ! mining_pass_has_physical_provenance diamond "$provenance_fixture"
  sed -i.bak $'s/^verify_timeout_seconds\t141333$/verify_timeout_seconds\t172800/' "$provenance_fixture"
  rm -f -- "$provenance_fixture.bak"
  sed -i.bak $'s/^mining_provenance_schema\t2$/mining_provenance_schema\t1/' "$provenance_fixture"
  rm -f -- "$provenance_fixture.bak"
  ! mining_pass_has_physical_provenance diamond "$provenance_fixture"
  sed -i.bak $'s/^mining_provenance_schema\t1$/mining_provenance_schema\t2/' "$provenance_fixture"
  rm -f -- "$provenance_fixture.bak"
  sed -i.bak $'s/^death_delta\t0$/death_delta\t1/' "$provenance_fixture"
  rm -f -- "$provenance_fixture.bak"
  ! mining_pass_has_physical_provenance diamond "$provenance_fixture"
  sed -i.bak $'s/^death_delta\t1$/death_delta\t0/' "$provenance_fixture"
  rm -f -- "$provenance_fixture.bak"
  sed -i.bak $'s/^privileged_allowed_count\t0$/privileged_allowed_count\t1/' "$provenance_fixture"
  rm -f -- "$provenance_fixture.bak"
  ! mining_pass_has_physical_provenance diamond "$provenance_fixture"
  {
    printf 'schema_version\t2\nresult\tPASS\nmining_provenance_schema\t2\n'
    printf 'verify_timeout_seconds\t18000\nscenario_timeout_ticks\t240000\n'
    printf 'mining_provenance_verdict\tPASS\nsurvival_observed_ticks\t100\n'
    printf 'game_mode_violations\t0\nprivileged_allowed_count\t0\ndeath_delta\t0\n'
    printf 'mining_final_inventory\t32\n'
    printf 'water_placements\t1\nlava_conversions\t32\nobsidian_breaks\t32\n'
    printf 'vanilla_obsidian_breaks\t32\nobsidian_physical_pickups\t32\n'
  } > "$provenance_fixture"
  mining_pass_has_physical_provenance obsidian "$provenance_fixture"
  sed -i.bak $'s/^lava_conversions\t32$/lava_conversions\t31/' "$provenance_fixture"
  rm -f -- "$provenance_fixture.bak"
  ! mining_pass_has_physical_provenance obsidian "$provenance_fixture"
  printf '[mining-release-gate] self-test PASS\n'
  exit 0
fi

TARGET=""
PRIMARY_BATCH=""
SENTINEL_BATCH=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --target) [[ $# -ge 2 ]] || { usage; exit 2; }; TARGET="$2"; shift 2 ;;
    --primary-batch) [[ $# -ge 2 ]] || { usage; exit 2; }; PRIMARY_BATCH="$2"; shift 2 ;;
    --sentinel-batch) [[ $# -ge 2 ]] || { usage; exit 2; }; SENTINEL_BATCH="$2"; shift 2 ;;
    -h|--help) usage; exit 0 ;;
    *) printf '[mining-release-gate] unknown argument: %s\n' "$1" >&2; usage; exit 2 ;;
  esac
done

case "$TARGET" in
  diamond)
    CAPABILITY_ID=diamond_stack_64
    SCENARIO=diamond_stack_64_from_zero
    ;;
  obsidian)
    CAPABILITY_ID=obsidian_half_stack_32
    SCENARIO=obsidian_half_stack_32_from_zero
    ;;
  *) usage; exit 2 ;;
esac
[[ -n "$PRIMARY_BATCH" && -n "$SENTINEL_BATCH" ]] || { usage; exit 2; }
[[ "$PRIMARY_BATCH" != "$SENTINEL_BATCH" ]] || {
  printf '[mining-release-gate] primary and sentinel batches must be distinct\n' >&2
  exit 1
}

validate_batch_contract() {
  local label="$1" batch="$2" expected_seeds="$3" expected_runs="$4"
  local manifest="$batch/manifest.tsv"
  "$ROOT/scripts/evidence_validate.sh" "$batch" >/dev/null || {
    printf '[mining-release-gate] %s batch failed immutable evidence validation: %s\n' "$label" "$batch" >&2
    return 1
  }
  [[ "$(manifest_get "$manifest" evidence_state)" == VERIFIED \
      && "$(manifest_get "$manifest" verification_reason)" == complete \
      && "$(manifest_get "$manifest" scenario)" == "$SCENARIO" \
      && "$(manifest_get "$manifest" seeds)" == "$expected_seeds" \
      && "$(manifest_get "$manifest" runs_per_seed)" == "$expected_runs" \
      && "$(manifest_get "$manifest" profile)" == strict_survival \
      && "$(manifest_get "$manifest" mode)" == deterministic \
      && "$(manifest_get "$manifest" llm_enabled)" == no \
      && "$(manifest_get "$manifest" requires_verified)" == yes \
      && "$(manifest_get "$manifest" operator_capabilities)" == "$STRICT_CAPABILITIES" ]] || {
    printf '[mining-release-gate] %s batch does not match the canonical release contract\n' "$label" >&2
    return 1
  }
}

validate_batch_contract primary "$PRIMARY_BATCH" "$PUBLIC_SEEDS" 1
validate_batch_contract sentinel "$SENTINEL_BATCH" "$SENTINEL_SEEDS" 3

PRIMARY_COMMIT="$(manifest_get "$PRIMARY_BATCH/manifest.tsv" commit_sha)"
SENTINEL_COMMIT="$(manifest_get "$SENTINEL_BATCH/manifest.tsv" commit_sha)"
[[ "$PRIMARY_COMMIT" == "$SENTINEL_COMMIT" ]] || {
  printf '[mining-release-gate] primary and sentinel batches were not run from one commit\n' >&2
  exit 1
}

PRIMARY_PASS="$(awk -F '\t' 'NR > 1 && $5 == "PASS" { pass++ } END { print pass + 0 }' "$PRIMARY_BATCH/result.tsv")"
PRIMARY_TOTAL="$(awk 'END { print NR - 1 }' "$PRIMARY_BATCH/result.tsv")"
SENTINEL_PASS="$(awk -F '\t' 'NR > 1 && $5 == "PASS" { pass++ } END { print pass + 0 }' "$SENTINEL_BATCH/result.tsv")"
SENTINEL_TOTAL="$(awk 'END { print NR - 1 }' "$SENTINEL_BATCH/result.tsv")"

# evidence_validate.sh has already authenticated every referenced LOCKED hash.
# Re-read the sealed run manifests to additionally require one effective config
# across both batches, so a config change cannot be hidden inside an aggregate.
CONFIG_HASH=""
RUNTIME_SIGNATURE=""
while IFS= read -r evidence_path; do
  run_manifest="$ROOT/$evidence_path/manifest.tsv"
  run_config="$(manifest_get "$run_manifest" config_hash)"
  run_runtime_value="$(manifest_get "$run_manifest" runtime)"
  # GitHub-hosted shards can land on different kernel patch levels. Compare
  # the game/Fabric/Java runtime prefix while retaining each full host runtime
  # in its sealed run manifest.
  run_runtime="$(manifest_get "$run_manifest" build_version)|${run_runtime_value%; *}"
  run_result="$(manifest_get "$run_manifest" result)"
  [[ -n "$run_config" && "$run_config" != unknown ]] || {
    printf '[mining-release-gate] referenced run has no usable config_hash: %s\n' "$evidence_path" >&2
    exit 1
  }
  if [[ "$run_result" == PASS ]] && ! mining_pass_has_physical_provenance "$TARGET" "$run_manifest"; then
    printf '[mining-release-gate] referenced PASS lacks physical provenance: %s\n' "$evidence_path" >&2
    exit 1
  fi
  if [[ -z "$CONFIG_HASH" ]]; then
    CONFIG_HASH="$run_config"
  elif [[ "$run_config" != "$CONFIG_HASH" ]]; then
    printf '[mining-release-gate] referenced runs used more than one effective config\n' >&2
    exit 1
  fi
  if [[ -z "$RUNTIME_SIGNATURE" ]]; then
    RUNTIME_SIGNATURE="$run_runtime"
  elif [[ "$run_runtime" != "$RUNTIME_SIGNATURE" ]]; then
    printf '[mining-release-gate] referenced runs used more than one build/runtime\n' >&2
    exit 1
  fi
done < <(awk -F '\t' 'FNR > 1 { print $6 }' "$PRIMARY_BATCH/result.tsv" "$SENTINEL_BATCH/result.tsv")

if ! meets_release_counts "$PRIMARY_PASS" "$PRIMARY_TOTAL" "$SENTINEL_PASS" "$SENTINEL_TOTAL"; then
  printf '[mining-release-gate] FAIL capability=%s primary=%s/%s sentinel=%s/%s\n' \
    "$CAPABILITY_ID" "$PRIMARY_PASS" "$PRIMARY_TOTAL" "$SENTINEL_PASS" "$SENTINEL_TOTAL" >&2
  exit 1
fi

printf 'MINING_MULTI_SEED_GATE=PASS\n'
printf 'CAPABILITY_ID=%s\n' "$CAPABILITY_ID"
printf 'SCENARIO=%s\n' "$SCENARIO"
printf 'COMMIT_SHA=%s\n' "$PRIMARY_COMMIT"
printf 'CONFIG_HASH=%s\n' "$CONFIG_HASH"
printf 'PRIMARY_RESULT=%s/%s\n' "$PRIMARY_PASS" "$PRIMARY_TOTAL"
printf 'SENTINEL_RESULT=%s/%s\n' "$SENTINEL_PASS" "$SENTINEL_TOTAL"
printf 'PRIMARY_BATCH=%s\n' "$PRIMARY_BATCH"
printf 'SENTINEL_BATCH=%s\n' "$SENTINEL_BATCH"
printf 'ZERO_DEATH_CONTRACT=from_zero_scenario_postcondition\n'
printf 'FULL_CAPABILITY_CERTIFIED=no\n'
