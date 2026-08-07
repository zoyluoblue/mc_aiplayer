#!/usr/bin/env bash
# Canonical Mining First acceptance constants. This file is sourced by the
# sequential runner, GitHub shard runner, aggregator, and multi-seed gate.

MINING_PUBLIC_SEEDS='3000,155361719,632510390,111,700,4040404,12345,54321,99999,246810,105441651,1061665215,206232996,42414950,456718736,586434987,633819475,715809951,222222,1234567'
MINING_SENTINEL_SEEDS='3000,20260610,777'
MINING_STRICT_CAPABILITIES='hiddenBlockScan=false,emergencyTeleport=false,forcedPickup=false,manualTeleport=false'
# Evidence must remain viable under the supported degraded-rate floor, not only Minecraft's ideal
# 20 TPS ceiling. These are harness ceilings, not scenario tick budgets: evidence_run verifies the
# actual timeout announced by the live verifier before accepting either value.
MINING_MIN_SUPPORTED_TPS=15
MINING_MAX_VERIFY_TIMEOUT_SECONDS=172800
MINING_MAX_SCENARIO_TIMEOUT_TICKS=2147483647
MINING_DIAMOND_VERIFY_TIMEOUT_SECONDS=172800
MINING_OBSIDIAN_VERIFY_TIMEOUT_SECONDS=18000
# User-mandated full-stack obsidian: 316,800 scenario ticks / 15 TPS = 21,120 s minimum;
# 25,200 s (7 h) keeps a ~19% margin instead of the 32-contract's thin 12.5%.
MINING_OBSIDIAN64_VERIFY_TIMEOUT_SECONDS=25200

mining_manifest_get() {
  local file="$1" key="$2"
  awk -F '\t' -v wanted="$key" '$1 == wanted { print substr($0, index($0, "\t") + 1); exit }' "$file"
}

# Defense-in-depth predicate shared by shard, aggregate and final release gates. Failed runs remain
# valid diagnostic evidence; every run counted as PASS must carry the complete physical contract.
mining_pass_has_physical_provenance() {
  local target="$1" manifest="$2" ticks mode_violations privileged death_delta final_inventory
  local verify_timeout scenario_timeout
  [[ -f "$manifest" && "$(mining_manifest_get "$manifest" schema_version)" == 2 \
      && "$(mining_manifest_get "$manifest" result)" == PASS \
      && "$(mining_manifest_get "$manifest" mining_provenance_schema)" == 2 \
      && "$(mining_manifest_get "$manifest" mining_provenance_verdict)" == PASS ]] || return 1
  ticks="$(mining_manifest_get "$manifest" survival_observed_ticks)"
  mode_violations="$(mining_manifest_get "$manifest" game_mode_violations)"
  privileged="$(mining_manifest_get "$manifest" privileged_allowed_count)"
  death_delta="$(mining_manifest_get "$manifest" death_delta)"
  final_inventory="$(mining_manifest_get "$manifest" mining_final_inventory)"
  verify_timeout="$(mining_manifest_get "$manifest" verify_timeout_seconds)"
  scenario_timeout="$(mining_manifest_get "$manifest" scenario_timeout_ticks)"
  [[ "$ticks" =~ ^[1-9][0-9]*$ && "$mode_violations" == 0 && "$privileged" == 0 \
      && "$death_delta" == 0 \
      && "$final_inventory" =~ ^[0-9]+$ \
      && "$verify_timeout" =~ ^[1-9][0-9]*$ \
      && "$scenario_timeout" =~ ^[1-9][0-9]*$ ]] || return 1
  mining_verify_timeout_covers_ticks "$verify_timeout" "$scenario_timeout" || return 1
  case "$target" in
    diamond)
      [[ "$final_inventory" -ge 64 \
          && "$(mining_manifest_get "$manifest" diamond_natural_ore_breaks)" =~ ^[0-9]+$ \
          && "$(mining_manifest_get "$manifest" diamond_natural_ore_breaks)" -ge 64 \
          && "$(mining_manifest_get "$manifest" diamond_native_drops)" =~ ^[0-9]+$ \
          && "$(mining_manifest_get "$manifest" diamond_native_drops)" -ge 64 \
          && "$(mining_manifest_get "$manifest" diamond_physical_pickups)" =~ ^[0-9]+$ \
          && "$(mining_manifest_get "$manifest" diamond_physical_pickups)" -ge 64 ]]
      ;;
    obsidian|obsidian64)
      local obsidian_quota=32
      [[ "$target" == obsidian64 ]] && obsidian_quota=64
      [[ "$final_inventory" -ge "$obsidian_quota" \
          && "$(mining_manifest_get "$manifest" water_placements)" =~ ^[0-9]+$ \
          && "$(mining_manifest_get "$manifest" water_placements)" -ge 1 \
          && "$(mining_manifest_get "$manifest" lava_conversions)" =~ ^[0-9]+$ \
          && "$(mining_manifest_get "$manifest" lava_conversions)" -ge "$obsidian_quota" \
          && "$(mining_manifest_get "$manifest" obsidian_breaks)" =~ ^[0-9]+$ \
          && "$(mining_manifest_get "$manifest" obsidian_breaks)" -ge "$obsidian_quota" \
          && "$(mining_manifest_get "$manifest" vanilla_obsidian_breaks)" =~ ^[0-9]+$ \
          && "$(mining_manifest_get "$manifest" vanilla_obsidian_breaks)" -ge "$obsidian_quota" \
          && "$(mining_manifest_get "$manifest" obsidian_physical_pickups)" =~ ^[0-9]+$ \
          && "$(mining_manifest_get "$manifest" obsidian_physical_pickups)" -ge "$obsidian_quota" ]]
      ;;
    *) return 1 ;;
  esac
}

mining_scenario_for_target() {
  case "${1:-}" in
    diamond) printf 'diamond_stack_64_from_zero\n' ;;
    obsidian) printf 'obsidian_half_stack_32_from_zero\n' ;;
    obsidian64) printf 'obsidian_stack_64_from_zero\n' ;;
    *) return 1 ;;
  esac
}

mining_target_for_scenario() {
  case "${1:-}" in
    diamond_stack_64_from_zero) printf 'diamond\n' ;;
    obsidian_half_stack_32_from_zero) printf 'obsidian\n' ;;
    obsidian_stack_64_from_zero) printf 'obsidian64\n' ;;
    *) return 1 ;;
  esac
}

mining_default_verify_timeout_for_target() {
  case "${1:-}" in
    diamond) printf '%s\n' "$MINING_DIAMOND_VERIFY_TIMEOUT_SECONDS" ;;
    obsidian) printf '%s\n' "$MINING_OBSIDIAN_VERIFY_TIMEOUT_SECONDS" ;;
    obsidian64) printf '%s\n' "$MINING_OBSIDIAN64_VERIFY_TIMEOUT_SECONDS" ;;
    *) return 1 ;;
  esac
}

mining_minimum_verify_timeout_seconds() {
  local scenario_ticks="${1:-}"
  [[ "$scenario_ticks" =~ ^[1-9][0-9]*$ && ${#scenario_ticks} -le 10 \
      && "$scenario_ticks" -le "$MINING_MAX_SCENARIO_TIMEOUT_TICKS" ]] || return 1
  printf '%s\n' "$(( (scenario_ticks + MINING_MIN_SUPPORTED_TPS - 1) / MINING_MIN_SUPPORTED_TPS ))"
}

mining_verify_timeout_covers_ticks() {
  local verify_seconds="${1:-}" scenario_ticks="${2:-}"
  [[ "$verify_seconds" =~ ^[1-9][0-9]*$ && ${#verify_seconds} -le 6 \
      && "$verify_seconds" -le "$MINING_MAX_VERIFY_TIMEOUT_SECONDS" \
      && "$scenario_ticks" =~ ^[1-9][0-9]*$ && ${#scenario_ticks} -le 10 \
      && "$scenario_ticks" -le "$MINING_MAX_SCENARIO_TIMEOUT_TICKS" ]] || return 1
  (( verify_seconds * MINING_MIN_SUPPORTED_TPS >= scenario_ticks ))
}

# Prints every exact timeout announcement for one scenario. Callers reject duplicates rather than
# silently choosing one, because the announced budget is part of the sealed acceptance contract.
mining_running_timeout_ticks_from_log() {
  local scenario="${1:-}" log_file="${2:-}" marker
  [[ -n "$scenario" && -f "$log_file" ]] || return 1
  marker="[AIBot Verify] $scenario RUNNING timeout="
  awk -v marker="$marker" '
    {
      start = index($0, marker)
      if (start > 0) {
        tail = substr($0, start + length(marker))
        if (match(tail, /^[0-9]+/)) {
          value = substr(tail, RSTART, RLENGTH)
          rest = substr(tail, RLENGTH + 1)
          if (rest ~ /^[[:space:]]*$/) {
            print value
          }
        }
      }
    }
  ' "$log_file"
}

mining_running_timeout_announcement_count_from_log() {
  local scenario="${1:-}" log_file="${2:-}" marker
  [[ -n "$scenario" && -f "$log_file" ]] || return 1
  marker="[AIBot Verify] $scenario RUNNING timeout="
  awk -v marker="$marker" 'index($0, marker) { count++ } END { print count + 0 }' "$log_file"
}

mining_seed_in_csv() {
  local wanted="${1:-}" csv="${2:-}" old_ifs value
  old_ifs="$IFS"
  IFS=','
  for value in $csv; do
    if [[ "$value" == "$wanted" ]]; then
      IFS="$old_ifs"
      return 0
    fi
  done
  IFS="$old_ifs"
  return 1
}

mining_shard_id() {
  local target="${1:-}" role="${2:-}" seed="${3:-}" run_index="${4:-}"
  printf '%s-%s-%s-r%s\n' "$target" "$role" "$seed" "$run_index"
}
