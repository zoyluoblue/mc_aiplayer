#!/usr/bin/env bash
# Local unattended regression runner backed by immutable evidence batches.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd -P)"
ROUNDS="${1:-1}"
SEEDS="${AIBOT_NIGHT_SEEDS:-20260610,3000,777}"
PROFILE_VALUE="${AIBOT_PROFILE:-strict_survival}"
SCENARIOS="${AIBOT_NIGHT_SCENARIOS:-capability_profile+runtime_control_suite,food_suite,mining}"
REPORT="$ROOT/reports/night_$(date '+%Y%m%d_%H%M').md"

[[ "$ROUNDS" =~ ^[1-9][0-9]*$ && "$ROUNDS" -le 20 ]] || {
  printf '[night-watch] rounds must be between 1 and 20\n' >&2
  exit 2
}

mkdir -p "$ROOT/reports"
overall=0
{
  printf '# 夜间值守报告 %s\n\n' "$(date '+%F %T')"
  printf -- '- profile: `%s`\n- seeds: `%s`\n- runs per seed: `%s`\n\n' "$PROFILE_VALUE" "$SEEDS" "$ROUNDS"
} > "$REPORT"

old_ifs="$IFS"
IFS=',' read -r -a scenario_list <<< "$SCENARIOS"
IFS="$old_ifs"
for scenario in "${scenario_list[@]}"; do
  args=(--scenario "$scenario" --seeds "$SEEDS" --runs "$ROUNDS"
    --timeout "${AIBOT_NIGHT_TIMEOUT:-2400}" --profile "$PROFILE_VALUE")
  [[ "$PROFILE_VALUE" != operator ]] || args+=(--operator-capabilities "${AIBOT_OPERATOR_CAPABILITIES:-all}")
  output="$(mktemp "${TMPDIR:-/tmp}/aibot-night.XXXXXX")"
  set +e
  "$ROOT/scripts/evidence_batch.sh" "${args[@]}" | tee "$output"
  status=${PIPESTATUS[0]}
  set -e
  batch_dir="$(sed -n 's/^BATCH_EVIDENCE_DIR=//p' "$output" | tail -1)"
  rm -f -- "$output"
  {
    printf '## `%s`\n\n' "$scenario"
    printf -- '- status: `%s`\n' "$status"
    printf -- '- evidence: `%s`\n\n' "${batch_dir:-missing}"
  } >> "$REPORT"
  [[ $status -eq 0 ]] || overall=1
done

printf '[night-watch] report: %s\n' "$REPORT"
exit "$overall"
