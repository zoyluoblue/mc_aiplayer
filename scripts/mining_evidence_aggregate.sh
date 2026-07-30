#!/usr/bin/env bash
# Assemble the fixed Mining First shard matrix into two canonical evidence
# batches, then evaluate only the multi-seed gate. No directory is searched for
# a preferred result: every expected shard id is derived from the contract and
# must exist exactly once.
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
usage: scripts/mining_evidence_aggregate.sh <diamond|obsidian>
       scripts/mining_evidence_aggregate.sh --self-test

The command expects exactly the canonical 29 descriptors for one target under
artifacts/mining-shards and exactly their 29 referenced run bundles under
artifacts/evidence. It publishes primary/sentinel canonical batches and runs
scripts/mining_release_gate.sh. A failed capability run remains valid evidence;
only the final 18/20 + 9/9 multi-seed verdict controls exit status 0/1.
EOF
}

expected_shard_ids() {
  local target="$1" seed run_index old_ifs
  old_ifs="$IFS"
  IFS=','
  for seed in $MINING_PUBLIC_SEEDS; do
    mining_shard_id "$target" primary "$seed" 1
  done
  for seed in $MINING_SENTINEL_SEEDS; do
    for run_index in 1 2 3; do
      mining_shard_id "$target" sentinel "$seed" "$run_index"
    done
  done
  IFS="$old_ifs"
}

same_provenance() {
  local expected_commit="$1" expected_config="$2" expected_runtime="$3"
  local run_commit="$4" run_config="$5" run_runtime="$6"
  [[ "$run_commit" == "$expected_commit" && "$run_config" == "$expected_config" \
      && "$run_runtime" == "$expected_runtime" ]]
}

verify_descriptor_envelope() {
  local shard="$1" manifest="$1/manifest.tsv" checksum_line checksum_hash checksum_name
  [[ -d "$shard" && ! -L "$shard" ]] || return 1
  harness_assert_no_tree_symlinks "$shard" >/dev/null || return 1
  [[ "$(find "$shard" -mindepth 1 -maxdepth 1 -print | sed 's#^.*/##' | LC_ALL=C sort)" == $'LOCKED\nchecksums.sha256\nmanifest.tsv' ]] || return 1
  [[ -f "$manifest" && ! -L "$manifest" && -f "$shard/checksums.sha256" && ! -L "$shard/checksums.sha256" \
      && -f "$shard/LOCKED" && ! -L "$shard/LOCKED" ]] || return 1
  [[ "$(wc -l < "$shard/checksums.sha256" | tr -d ' ')" == 1 ]] || return 1
  IFS= read -r checksum_line < "$shard/checksums.sha256"
  checksum_hash="${checksum_line%%  *}"
  checksum_name="${checksum_line#*  }"
  [[ "$checksum_name" == manifest.tsv && ${#checksum_hash} -eq 64 && "$checksum_hash" != *[!0-9a-f]* \
      && "$(harness_sha256 "$manifest")" == "$checksum_hash" ]] || return 1
  harness_verify_locked_marker "$shard/LOCKED" >/dev/null || return 1
  awk -F '\t' '
    NF != 2 || $1 == "" || $2 == "" || seen[$1]++ { exit 2 }
    $1 !~ /^(schema_version|shard_id|target|role|scenario|seed|run_index|evidence_path|evidence_lock_sha256|created_at_utc)$/ { exit 3 }
    END { if (NR != 10) exit 4 }
  ' "$manifest"
}

if [[ "${1:-}" == --self-test ]]; then
  [[ $# -eq 1 ]] || { usage; exit 2; }
  for target in diamond obsidian; do
    ids="$(expected_shard_ids "$target")"
    [[ "$(printf '%s\n' "$ids" | sed '/^$/d' | wc -l | tr -d ' ')" == 29 ]]
    [[ "$(printf '%s\n' "$ids" | LC_ALL=C sort -u | wc -l | tr -d ' ')" == 29 ]]
  done
  same_provenance commit-a config-a runtime-a commit-a config-a runtime-a
  ! same_provenance commit-a config-a runtime-a commit-b config-a runtime-a
  ! same_provenance commit-a config-a runtime-a commit-a config-b runtime-a
  ! same_provenance commit-a config-a runtime-a commit-a config-a runtime-b
  descriptor="$(mktemp -d "${TMPDIR:-/tmp}/aibot-shard-envelope.XXXXXX")"
  trap 'rm -rf -- "$descriptor"' EXIT
  {
    printf 'schema_version\t1\nshard_id\ttest-primary-1-r1\ntarget\ttest\nrole\tprimary\n'
    printf 'scenario\ttest_scenario\nseed\t1\nrun_index\t1\n'
    printf 'evidence_path\tartifacts/evidence/test\n'
    printf 'evidence_lock_sha256\t%s\n' "$(printf 'a%.0s' {1..64})"
    printf 'created_at_utc\t2026-07-10T00:00:00Z\n'
  } > "$descriptor/manifest.tsv"
  printf '%s  manifest.tsv\n' "$(harness_sha256 "$descriptor/manifest.tsv")" > "$descriptor/checksums.sha256"
  harness_write_locked_marker "$descriptor"
  verify_descriptor_envelope "$descriptor"
  printf 'tampered\tvalue\n' >> "$descriptor/manifest.tsv"
  ! verify_descriptor_envelope "$descriptor"
  rm -rf -- "$descriptor"
  trap - EXIT
  bash "$ROOT/scripts/mining_evidence_shard.sh" --self-test >/dev/null
  bash "$ROOT/scripts/mining_release_gate.sh" --self-test >/dev/null
  printf '[mining-evidence-aggregate] self-test PASS\n'
  exit 0
fi

[[ $# -eq 1 ]] || { usage; exit 2; }
TARGET="$1"
case "$TARGET" in diamond|obsidian) ;; *) usage; exit 2 ;; esac
SCENARIO="$(mining_scenario_for_target "$TARGET")"

cd "$ROOT"
[[ -d "$SHARD_ROOT" && ! -L "$SHARD_ROOT" ]] || {
  printf '[mining-evidence-aggregate] shard root is missing or unsafe\n' >&2
  exit 3
}
[[ -d "$HARNESS_ARTIFACT_ROOT" && ! -L "$HARNESS_ARTIFACT_ROOT" ]] || {
  printf '[mining-evidence-aggregate] run evidence root is missing or unsafe\n' >&2
  exit 3
}
if [[ -n "$(git status --porcelain=v1 --untracked-files=all 2>/dev/null)" ]]; then
  printf '[mining-evidence-aggregate] aggregation requires a clean worktree\n' >&2
  exit 3
fi
HEAD_COMMIT="$(git rev-parse HEAD 2>/dev/null)" || exit 3

TMP_EXPECTED="$(mktemp "${TMPDIR:-/tmp}/aibot-mining-expected.XXXXXX")"
TMP_ACTUAL="$(mktemp "${TMPDIR:-/tmp}/aibot-mining-actual.XXXXXX")"
USED_EVIDENCE="$(mktemp "${TMPDIR:-/tmp}/aibot-mining-used.XXXXXX")"
PRIMARY_RESULTS="$(mktemp "${TMPDIR:-/tmp}/aibot-mining-primary.XXXXXX")"
SENTINEL_RESULTS="$(mktemp "${TMPDIR:-/tmp}/aibot-mining-sentinel.XXXXXX")"
STAGING=""
cleanup() {
  local status=$?
  trap - EXIT HUP INT TERM
  rm -f -- "$TMP_EXPECTED" "$TMP_ACTUAL" "$USED_EVIDENCE" "$PRIMARY_RESULTS" "$SENTINEL_RESULTS"
  if [[ -n "$STAGING" ]]; then
    harness_safe_remove_staging "$STAGING" "$HARNESS_BATCH_ROOT" || true
  fi
  exit "$status"
}
trap cleanup EXIT
trap 'exit 129' HUP
trap 'exit 130' INT
trap 'exit 143' TERM

expected_shard_ids "$TARGET" | LC_ALL=C sort > "$TMP_EXPECTED"
find "$SHARD_ROOT" -mindepth 1 -maxdepth 1 -print | sed 's#^.*/##' | LC_ALL=C sort > "$TMP_ACTUAL"
if ! diff -u "$TMP_EXPECTED" "$TMP_ACTUAL" >/dev/null; then
  printf '[mining-evidence-aggregate] shard set is missing, duplicated, or contains unexpected entries\n' >&2
  diff -u "$TMP_EXPECTED" "$TMP_ACTUAL" >&2 || true
  exit 3
fi

printf 'seed\trun_index\texit_code\tevidence_state\tresult\tevidence_path\tevidence_lock_sha256\n' > "$PRIMARY_RESULTS"
printf 'seed\trun_index\texit_code\tevidence_state\tresult\tevidence_path\tevidence_lock_sha256\n' > "$SENTINEL_RESULTS"
COMMON_COMMIT=""
COMMON_CONFIG=""
COMMON_RUNTIME=""

validate_shard() {
  local role="$1" seed="$2" run_index="$3" shard_id shard manifest created_at sealed_at
  local evidence_path evidence_hash evidence_dir run_manifest run_commit run_config run_runtime run_runtime_value run_result run_state run_finished
  local row_scenario row_requested row_actual row_result row_passed row_total row_exit row_summary result_file
  shard_id="$(mining_shard_id "$TARGET" "$role" "$seed" "$run_index")"
  shard="$SHARD_ROOT/$shard_id"
  manifest="$shard/manifest.tsv"
  verify_descriptor_envelope "$shard" || return 1
  created_at="$(harness_manifest_get "$manifest" created_at_utc)"
  sealed_at="$(harness_manifest_get "$shard/LOCKED" sealed_at_utc)"
  [[ "$(harness_manifest_get "$manifest" schema_version)" == 1 \
      && "$(harness_manifest_get "$manifest" shard_id)" == "$shard_id" \
      && "$(harness_manifest_get "$manifest" target)" == "$TARGET" \
      && "$(harness_manifest_get "$manifest" role)" == "$role" \
      && "$(harness_manifest_get "$manifest" scenario)" == "$SCENARIO" \
      && "$(harness_manifest_get "$manifest" seed)" == "$seed" \
      && "$(harness_manifest_get "$manifest" run_index)" == "$run_index" \
      && "$created_at" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z$ \
      && ! "$sealed_at" < "$created_at" ]] || return 1

  evidence_path="$(harness_manifest_get "$manifest" evidence_path)"
  evidence_hash="$(harness_manifest_get "$manifest" evidence_lock_sha256)"
  [[ "$evidence_path" =~ ^artifacts/evidence/[A-Za-z0-9][A-Za-z0-9_.+-]*$ \
      && ${#evidence_hash} -eq 64 && "$evidence_hash" != *[!0-9a-f]* ]] || return 1
  if grep -Fxq "$evidence_path" "$USED_EVIDENCE"; then
    printf '[mining-evidence-aggregate] one run bundle was reused by multiple shards: %s\n' "$evidence_path" >&2
    return 1
  fi
  evidence_dir="$ROOT/$evidence_path"
  "$ROOT/scripts/evidence_validate.sh" --require-verified "$evidence_dir" >/dev/null || return 1
  [[ "$(harness_sha256 "$evidence_dir/LOCKED")" == "$evidence_hash" ]] || return 1
  run_manifest="$evidence_dir/manifest.tsv"
  run_commit="$(harness_manifest_get "$run_manifest" commit_sha)"
  run_config="$(harness_manifest_get "$run_manifest" config_hash)"
  run_runtime_value="$(harness_manifest_get "$run_manifest" runtime)"
  run_runtime="$(harness_manifest_get "$run_manifest" build_version)|${run_runtime_value%; *}"
  run_result="$(harness_manifest_get "$run_manifest" result)"
  run_state="$(harness_manifest_get "$run_manifest" evidence_state)"
  run_finished="$(harness_manifest_get "$run_manifest" finished_at_utc)"
  [[ "$run_state" == VERIFIED \
      && "$(harness_manifest_get "$run_manifest" scenario)" == "$SCENARIO" \
      && "$(harness_manifest_get "$run_manifest" requested_seed)" == "$seed" \
      && "$(harness_manifest_get "$run_manifest" actual_seed)" == "$seed" \
      && "$(harness_manifest_get "$run_manifest" actual_seed_verified)" == yes \
      && "$(harness_manifest_get "$run_manifest" profile)" == strict_survival \
      && "$(harness_manifest_get "$run_manifest" mode)" == deterministic \
      && "$(harness_manifest_get "$run_manifest" llm_enabled)" == no \
      && "$(harness_manifest_get "$run_manifest" operator_capabilities)" == "$MINING_STRICT_CAPABILITIES" \
      && ! "$created_at" < "$run_finished" ]] || return 1
  if [[ "$run_result" == PASS ]] && ! mining_pass_has_physical_provenance "$TARGET" "$run_manifest"; then
    printf '[mining-evidence-aggregate] PASS shard lacks physical provenance: %s\n' "$evidence_path" >&2
    return 1
  fi
  [[ "$run_commit" == "$HEAD_COMMIT" ]] || {
    printf '[mining-evidence-aggregate] shard commit does not equal aggregate checkout HEAD\n' >&2
    return 1
  }
  if [[ -z "$COMMON_COMMIT" ]]; then
    COMMON_COMMIT="$run_commit"
    COMMON_CONFIG="$run_config"
    COMMON_RUNTIME="$run_runtime"
  elif ! same_provenance "$COMMON_COMMIT" "$COMMON_CONFIG" "$COMMON_RUNTIME" \
      "$run_commit" "$run_config" "$run_runtime"; then
    printf '[mining-evidence-aggregate] shards mix commit, config, build, or runtime\n' >&2
    return 1
  fi

  IFS=$'\t' read -r row_scenario row_requested row_actual row_result row_passed row_total row_exit row_summary \
    < <(sed -n '2p' "$evidence_dir/result.tsv")
  [[ "$row_scenario" == "$SCENARIO" && "$row_requested" == "$seed" && "$row_actual" == "$seed" \
      && "$row_result" == "$run_result" && "$row_exit" =~ ^[0-9]+$ ]] || return 1
  result_file="$PRIMARY_RESULTS"
  [[ "$role" != sentinel ]] || result_file="$SENTINEL_RESULTS"
  printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\n' "$seed" "$run_index" "$row_exit" \
    "$run_state" "$run_result" "$evidence_path" "$evidence_hash" >> "$result_file"
  printf '%s\n' "$evidence_path" >> "$USED_EVIDENCE"
}

old_ifs="$IFS"
IFS=','
for seed in $MINING_PUBLIC_SEEDS; do
  validate_shard primary "$seed" 1 || {
    printf '[mining-evidence-aggregate] invalid primary shard: %s\n' "$seed" >&2
    exit 3
  }
done
for seed in $MINING_SENTINEL_SEEDS; do
  for run_index in 1 2 3; do
    validate_shard sentinel "$seed" "$run_index" || {
      printf '[mining-evidence-aggregate] invalid sentinel shard: %s/%s\n' "$seed" "$run_index" >&2
      exit 3
    }
  done
done
IFS="$old_ifs"

sed 's#^artifacts/evidence/##' "$USED_EVIDENCE" | LC_ALL=C sort > "$TMP_EXPECTED"
find "$HARNESS_ARTIFACT_ROOT" -mindepth 1 -maxdepth 1 -print | sed 's#^.*/##' | LC_ALL=C sort > "$TMP_ACTUAL"
if ! diff -u "$TMP_EXPECTED" "$TMP_ACTUAL" >/dev/null; then
  printf '[mining-evidence-aggregate] run evidence set contains missing or unreferenced bundles\n' >&2
  exit 3
fi

create_batch() {
  local output_variable="$1" role="$2" seed_csv="$3" runs_per_seed="$4" result_file="$5"
  local batch_id final started finished result pass_count row_count
  batch_id="$(harness_run_id "batch-$SCENARIO-$role" "$COMMON_COMMIT")"
  harness_prepare_root "$HARNESS_BATCH_ROOT" || return 1
  final="$HARNESS_BATCH_ROOT/$batch_id"
  STAGING="$(mktemp -d "$HARNESS_BATCH_ROOT/.staging.${batch_id}.XXXXXX")" || return 1
  cp "$result_file" "$STAGING/result.tsv" || return 1
  row_count="$(awk 'END { print NR - 1 }' "$result_file")"
  pass_count="$(awk -F '\t' 'NR > 1 && $5 == "PASS" { pass++ } END { print pass + 0 }' "$result_file")"
  result=FAIL
  [[ "$pass_count" == "$row_count" ]] && result=PASS
  started="$(harness_now_utc)"
  finished="$(harness_now_utc)"
  {
    printf 'schema_version\t1\n'
    printf 'batch_id\t%s\n' "$batch_id"
    printf 'evidence_state\tVERIFIED\n'
    printf 'verification_reason\tcomplete\n'
    printf 'commit_sha\t%s\n' "$COMMON_COMMIT"
    printf 'finished_commit_sha\t%s\n' "$COMMON_COMMIT"
    printf 'working_tree_state\tclean\n'
    printf 'working_tree_state_end\tclean\n'
    printf 'timestamp_utc\t%s\n' "$started"
    printf 'finished_at_utc\t%s\n' "$finished"
    printf 'scenario\t%s\n' "$SCENARIO"
    printf 'seeds\t%s\n' "$seed_csv"
    printf 'runs_per_seed\t%s\n' "$runs_per_seed"
    printf 'profile\tstrict_survival\n'
    printf 'operator_capabilities\t%s\n' "$MINING_STRICT_CAPABILITIES"
    printf 'mode\tdeterministic\n'
    printf 'llm_enabled\tno\n'
    printf 'requires_verified\tyes\n'
    printf 'dependency_root\tartifacts/evidence\n'
    printf 'result\t%s\n' "$result"
  } > "$STAGING/manifest.tsv" || return 1
  {
    printf '%s  manifest.tsv\n' "$(harness_sha256 "$STAGING/manifest.tsv")"
    printf '%s  result.tsv\n' "$(harness_sha256 "$STAGING/result.tsv")"
  } > "$STAGING/checksums.sha256" || return 1
  harness_write_locked_marker "$STAGING" || return 1
  harness_publish_staging "$STAGING" "$final" || return 1
  STAGING=""
  "$ROOT/scripts/evidence_validate.sh" --require-verified "$final" >/dev/null || return 1
  printf -v "$output_variable" '%s' "$final"
}

[[ -z "$(git status --porcelain=v1 --untracked-files=all 2>/dev/null)" && "$(git rev-parse HEAD)" == "$COMMON_COMMIT" ]] || {
  printf '[mining-evidence-aggregate] worktree or revision changed during aggregation\n' >&2
  exit 3
}
PRIMARY_BATCH=""
SENTINEL_BATCH=""
create_batch PRIMARY_BATCH primary "$MINING_PUBLIC_SEEDS" 1 "$PRIMARY_RESULTS" || exit 3
create_batch SENTINEL_BATCH sentinel "$MINING_SENTINEL_SEEDS" 3 "$SENTINEL_RESULTS" || exit 3
[[ -z "$(git status --porcelain=v1 --untracked-files=all 2>/dev/null)" \
    && "$(git rev-parse HEAD)" == "$COMMON_COMMIT" ]] || {
  printf '[mining-evidence-aggregate] worktree or revision changed while publishing canonical batches\n' >&2
  exit 3
}

printf 'MINING_PRIMARY_BATCH=%s\n' "$PRIMARY_BATCH"
printf 'MINING_SENTINEL_BATCH=%s\n' "$SENTINEL_BATCH"
set +e
bash "$ROOT/scripts/mining_release_gate.sh" --target "$TARGET" \
  --primary-batch "$PRIMARY_BATCH" --sentinel-batch "$SENTINEL_BATCH"
GATE_STATUS=$?
set -e
exit "$GATE_STATUS"
