#!/usr/bin/env bash
# Validate sealed run evidence without trusting paths or sourcing metadata.
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd -P)"
HARNESS_REPO_ROOT="$ROOT"
# shellcheck source=scripts/lib/harness.sh
source "$ROOT/scripts/lib/harness.sh"

usage() {
  printf 'usage: scripts/evidence_validate.sh [--require-verified] <evidence-dir> [...]\n       scripts/evidence_validate.sh --self-test\n' >&2
}

REQUIRE_VERIFIED=0
SELF_TEST=0
case "${1:-}" in
  --require-verified) REQUIRE_VERIFIED=1; shift ;;
  --self-test) SELF_TEST=1; shift ;;
esac
if [[ $SELF_TEST -eq 1 ]]; then
  [[ $# -eq 0 ]] || { usage; exit 2; }
else
  [[ $# -gt 0 ]] || { usage; exit 2; }
fi

validation_fail() {
  VALIDATION_REASON="$1"
  return 1
}

assert_no_symlink_components() {
  local path="$1" root="$2" relative current part old_ifs
  relative="${path#"$root"/}"
  [[ "$relative" != "$path" ]] || return 1
  current="$root"
  old_ifs="$IFS"
  IFS='/'
  for part in $relative; do
    [[ -n "$part" && "$part" != . && "$part" != .. ]] || { IFS="$old_ifs"; return 1; }
    current="$current/$part"
    [[ ! -L "$current" ]] || { IFS="$old_ifs"; return 1; }
  done
  IFS="$old_ifs"
}

validate_one() {
  local input="$1" input_absolute canonical artifact_root baseline_root allowed_root=""
  local manifest result header row state reason run_id scenario requested actual actual_verified
  local profile mode worktree worktree_end config_hash calculated_config manifest_result manifest_passed manifest_total
  local capabilities llm_enabled config_facts config_profile config_capabilities config_llm
  local commit_end run_directory run_directory_verified source_snapshot gradle_exit server_port redactions java_home java_runtime_verified
  local log_summary_count log_summary parsed_log_passed parsed_log_total
  local row_scenario row_requested row_actual row_result row_passed row_total row_exit row_summary
  local schema commit timestamp finished sealed key value required_count

  VALIDATION_REASON=unknown
  [[ -d "$input" && ! -L "$input" ]] || { validation_fail path_missing_or_symlink; return 1; }
  case "$input" in /*) input_absolute="$input" ;; *) input_absolute="$(pwd -P)/$input" ;; esac
  case "$input_absolute/" in *'/../'*|*'/./'*|*'//'*) validation_fail traversal_path_component; return 1 ;; esac
  canonical="$(harness_real_dir "$input")" || { validation_fail path_unresolvable; return 1; }
  [[ -d "$HARNESS_ARTIFACT_ROOT" && ! -L "$HARNESS_ARTIFACT_ROOT" ]] && \
    artifact_root="$(harness_real_dir "$HARNESS_ARTIFACT_ROOT")" || artifact_root=""
  [[ -d "$HARNESS_BASELINE_ROOT" && ! -L "$HARNESS_BASELINE_ROOT" ]] && \
    baseline_root="$(harness_real_dir "$HARNESS_BASELINE_ROOT")" || baseline_root=""

  if [[ -n "$artifact_root" ]] && harness_is_within "$canonical" "$artifact_root"; then
    allowed_root="$artifact_root"
  elif [[ -n "$baseline_root" ]] && harness_is_within "$canonical" "$baseline_root"; then
    allowed_root="$baseline_root"
  else
    validation_fail path_outside_evidence_roots
    return 1
  fi
  assert_no_symlink_components "$input_absolute" "$allowed_root" || {
    validation_fail symlink_or_traversal_component
    return 1
  }
  [[ "$(basename "$canonical")" != .staging.* ]] || { validation_fail staging_not_sealed; return 1; }
  harness_verify_bundle_files "$canonical" || { validation_fail checksum_or_schema_file_failure; return 1; }

  manifest="$canonical/manifest.tsv"
  result="$canonical/result.tsv"
  if ! awk -F '\t' '
      NF != 2 || $1 == "" || $2 == "" { exit 2 }
      seen[$1]++ { exit 3 }
    ' "$manifest"; then
    validation_fail malformed_or_duplicate_manifest_key
    return 1
  fi

  for key in schema_version run_id evidence_state verification_reason commit_sha git_branch \
    finished_commit_sha working_tree_state working_tree_state_end build_version timestamp_utc finished_at_utc runtime java_home java_runtime_verified scenario requested_seed \
    actual_seed actual_seed_verified mode llm_enabled profile operator_capabilities config_hash config_sha256 server_port \
    run_directory run_directory_verified source_snapshot log_secret_redactions gradle_exit_code result passed total; do
    value="$(harness_manifest_get "$manifest" "$key")"
    [[ -n "$value" ]] || { validation_fail "missing_manifest_key:$key"; return 1; }
  done

  schema="$(harness_manifest_get "$manifest" schema_version)"
  [[ "$schema" == 1 ]] || { validation_fail unsupported_schema_version; return 1; }
  run_id="$(harness_manifest_get "$manifest" run_id)"
  harness_safe_id "$run_id" run_id 160 >/dev/null 2>&1 || { validation_fail unsafe_run_id; return 1; }
  [[ "$run_id" == "$(basename "$canonical")" ]] || { validation_fail run_id_path_mismatch; return 1; }

  state="$(harness_manifest_get "$manifest" evidence_state)"
  reason="$(harness_manifest_get "$manifest" verification_reason)"
  case "$state" in VERIFIED|UNVERIFIED) ;; *) validation_fail invalid_evidence_state; return 1 ;; esac
  if [[ "$state" == VERIFIED && "$reason" != complete ]] || \
     [[ "$state" == UNVERIFIED && ( -z "$reason" || "$reason" == complete ) ]]; then
    validation_fail evidence_state_reason_mismatch
    return 1
  fi
  worktree="$(harness_manifest_get "$manifest" working_tree_state)"
  worktree_end="$(harness_manifest_get "$manifest" working_tree_state_end)"
  case "$worktree" in clean|dirty|unknown) ;; *) validation_fail invalid_worktree_state; return 1 ;; esac
  case "$worktree_end" in clean|dirty|unknown) ;; *) validation_fail invalid_finished_worktree_state; return 1 ;; esac
  if [[ ( "$worktree" != clean || "$worktree_end" != clean ) && "$state" != UNVERIFIED ]]; then
    validation_fail nonclean_evidence_claims_verified
    return 1
  fi

  commit="$(harness_manifest_get "$manifest" commit_sha)"
  commit_end="$(harness_manifest_get "$manifest" finished_commit_sha)"
  if [[ ${#commit} -ne 40 && ${#commit} -ne 64 ]] || [[ "$commit" == *[!0-9a-f]* ]]; then
    [[ "$state" == UNVERIFIED && "$commit" == unknown ]] || { validation_fail invalid_commit_sha; return 1; }
  fi
  if [[ ${#commit_end} -ne 40 && ${#commit_end} -ne 64 ]] || [[ "$commit_end" == *[!0-9a-f]* ]]; then
    [[ "$state" == UNVERIFIED && "$commit_end" == unknown ]] || { validation_fail invalid_finished_commit_sha; return 1; }
  fi
  if [[ "$commit" != unknown ]] && ! git -C "$ROOT" cat-file -e "$commit^{commit}" 2>/dev/null; then
    validation_fail commit_object_not_available
    return 1
  fi
  if [[ "$commit_end" != unknown ]] && ! git -C "$ROOT" cat-file -e "$commit_end^{commit}" 2>/dev/null; then
    validation_fail finished_commit_object_not_available
    return 1
  fi
  if [[ "$state" == VERIFIED ]] && ! git -C "$ROOT" merge-base --is-ancestor "$commit" HEAD 2>/dev/null; then
    validation_fail verified_commit_not_reachable_from_head
    return 1
  fi
  [[ "$state" != VERIFIED || "$commit" == "$commit_end" ]] || {
    validation_fail verified_revision_changed_during_run
    return 1
  }
  if [[ "$state" == VERIFIED && ( "$(harness_manifest_get "$manifest" build_version)" == unknown || \
       "$(harness_manifest_get "$manifest" runtime)" == *unknown* ) ]]; then
    validation_fail verified_runtime_metadata_missing
    return 1
  fi
  java_home="$(harness_manifest_get "$manifest" java_home)"
  java_runtime_verified="$(harness_manifest_get "$manifest" java_runtime_verified)"
  case "$java_runtime_verified" in yes|no) ;; *) validation_fail invalid_java_runtime_verified; return 1 ;; esac
  if [[ "$state" == VERIFIED && ( "$java_runtime_verified" != yes || "$java_home" == unknown ) ]]; then
    validation_fail verified_java_runtime_missing
    return 1
  fi
  timestamp="$(harness_manifest_get "$manifest" timestamp_utc)"
  finished="$(harness_manifest_get "$manifest" finished_at_utc)"
  [[ "$timestamp" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z$ && \
     "$finished" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z$ ]] || {
    validation_fail invalid_utc_timestamp
    return 1
  }
  sealed="$(harness_manifest_get "$canonical/LOCKED" sealed_at_utc)"
  [[ ! "$sealed" < "$finished" ]] || { validation_fail sealed_before_run_finished; return 1; }

  scenario="$(harness_manifest_get "$manifest" scenario)"
  harness_safe_id "$scenario" scenario 96 >/dev/null 2>&1 || { validation_fail unsafe_scenario; return 1; }
  requested="$(harness_manifest_get "$manifest" requested_seed)"
  harness_safe_seed "$requested" >/dev/null 2>&1 || { validation_fail invalid_requested_seed; return 1; }
  actual="$(harness_manifest_get "$manifest" actual_seed)"
  actual_verified="$(harness_manifest_get "$manifest" actual_seed_verified)"
  case "$actual_verified" in yes|no) ;; *) validation_fail invalid_actual_seed_verified; return 1 ;; esac
  if [[ "$actual_verified" == yes ]]; then
    harness_safe_seed "$actual" >/dev/null 2>&1 || { validation_fail invalid_actual_seed; return 1; }
    [[ "$actual" == "$requested" ]] || { validation_fail requested_actual_seed_mismatch; return 1; }
  elif [[ "$state" == VERIFIED ]]; then
    validation_fail unverified_seed_claims_verified
    return 1
  fi

  mode="$(harness_manifest_get "$manifest" mode)"
  case "$mode" in deterministic|llm_story|fixture) ;; *) validation_fail invalid_mode; return 1 ;; esac
  [[ "$mode" != fixture || "$state" == UNVERIFIED ]] || { validation_fail fixture_claims_verified; return 1; }
  llm_enabled="$(harness_manifest_get "$manifest" llm_enabled)"
  case "$llm_enabled" in yes|no) ;; *) validation_fail invalid_llm_enabled; return 1 ;; esac
  if [[ "$mode" == llm_story && "$llm_enabled" != yes ]] || \
     [[ "$mode" != llm_story && "$llm_enabled" != no ]]; then
    validation_fail mode_llm_mismatch
    return 1
  fi
  profile="$(harness_manifest_get "$manifest" profile)"
  case "$profile" in strict_survival|operator) ;; *) validation_fail invalid_profile; return 1 ;; esac
  capabilities="$(harness_manifest_get "$manifest" operator_capabilities)"
  if [[ ! "$capabilities" =~ ^hiddenBlockScan=(true|false),emergencyTeleport=(true|false),forcedPickup=(true|false),manualTeleport=(true|false)$ ]]; then
    validation_fail malformed_operator_capabilities
    return 1
  fi
  if [[ "$profile" == strict_survival ]] && \
     [[ "$capabilities" != 'hiddenBlockScan=false,emergencyTeleport=false,forcedPickup=false,manualTeleport=false' ]]; then
    validation_fail strict_profile_has_privileged_capability
    return 1
  fi

  config_hash="$(harness_manifest_get "$manifest" config_sha256)"
  [[ "$(harness_manifest_get "$manifest" config_hash)" == "$config_hash" ]] || {
    validation_fail inconsistent_config_hash_fields
    return 1
  }
  calculated_config="$(harness_sha256 "$canonical/effective-config.redacted.json")" || {
    validation_fail config_hash_unavailable
    return 1
  }
  [[ "$config_hash" == "$calculated_config" ]] || { validation_fail config_hash_mismatch; return 1; }
  if ! command -v python3 >/dev/null 2>&1; then
    validation_fail python3_required_for_config_validation
    return 1
  fi
  config_facts="$(python3 - "$canonical/effective-config.redacted.json" <<'PY'
import json
import sys

try:
    def unique_object(pairs):
        result = {}
        for key, value in pairs:
            if key in result:
                raise ValueError("duplicate key")
            result[key] = value
        return result
    with open(sys.argv[1], "r", encoding="utf-8") as handle:
        data = json.load(handle, object_pairs_hook=unique_object)
    if set(data) != {"schemaVersion", "profile", "operatorCapabilities", "deepseek", "server"}:
        raise ValueError("top-level schema")
    caps = data["operatorCapabilities"]
    ordered = ("hiddenBlockScan", "emergencyTeleport", "forcedPickup", "manualTeleport")
    if data.get("schemaVersion") != 1 or set(caps) != set(ordered):
        raise ValueError("schema")
    if not all(isinstance(caps[name], bool) for name in ordered):
        raise ValueError("capability type")
    deepseek = data["deepseek"]
    if set(deepseek) != {"enabled", "apiKey"}:
        raise ValueError("deepseek schema")
    enabled = deepseek["enabled"]
    marker = deepseek["apiKey"]
    if not isinstance(enabled, bool) or marker not in ("<redacted:env>", "<redacted:unset>"):
        raise ValueError("secret redaction")
    if enabled != (marker == "<redacted:env>"):
        raise ValueError("LLM marker")
    server = data["server"]
    expected_server = {
        "onlineMode": False,
        "gamemode": "survival",
        "difficulty": "easy",
        "viewDistance": 10,
        "simulationDistance": 10,
    }
    if server != expected_server:
        raise ValueError("server schema")
    def credential_like(value):
        if isinstance(value, dict):
            return any(credential_like(item) for item in value.values())
        if isinstance(value, list):
            return any(credential_like(item) for item in value)
        return isinstance(value, str) and value.startswith("sk-")
    if credential_like(data):
        raise ValueError("credential-like value")
    print(data["profile"])
    print(",".join(f"{name}={str(caps[name]).lower()}" for name in ordered))
    print("yes" if enabled else "no")
except Exception:
    sys.exit(2)
PY
)" || {
    validation_fail invalid_or_unredacted_effective_config
    return 1
  }
  config_profile="$(printf '%s\n' "$config_facts" | sed -n '1p')"
  config_capabilities="$(printf '%s\n' "$config_facts" | sed -n '2p')"
  config_llm="$(printf '%s\n' "$config_facts" | sed -n '3p')"
  [[ "$config_profile" == "$profile" && "$config_capabilities" == "$capabilities" && "$config_llm" == "$llm_enabled" ]] || {
    validation_fail effective_config_manifest_mismatch
    return 1
  }

  server_port="$(harness_manifest_get "$manifest" server_port)"
  [[ "$server_port" =~ ^[0-9]+$ && "$server_port" -ge 1024 && "$server_port" -le 65535 ]] || {
    validation_fail invalid_server_port
    return 1
  }
  run_directory="$(harness_manifest_get "$manifest" run_directory)"
  run_directory_verified="$(harness_manifest_get "$manifest" run_directory_verified)"
  source_snapshot="$(harness_manifest_get "$manifest" source_snapshot)"
  [[ "$run_directory" == isolated_temporary ]] || { validation_fail shared_run_directory_claim; return 1; }
  case "$run_directory_verified" in yes|no) ;; *) validation_fail invalid_run_directory_verified; return 1 ;; esac
  case "$source_snapshot" in git_archive|live_worktree|git_archive_failed_live_fallback) ;;
    *) validation_fail invalid_source_snapshot; return 1 ;;
  esac
  if [[ "$state" == VERIFIED && ( "$run_directory_verified" != yes || "$source_snapshot" != git_archive ) ]]; then
    validation_fail verified_run_lacks_isolated_sources
    return 1
  fi
  gradle_exit="$(harness_manifest_get "$manifest" gradle_exit_code)"
  if [[ "$mode" == fixture ]]; then
    [[ "$gradle_exit" == not_applicable ]] || { validation_fail fixture_has_gradle_exit; return 1; }
  else
    [[ "$gradle_exit" =~ ^[0-9]+$ ]] || { validation_fail invalid_gradle_exit_code; return 1; }
    [[ "$state" != VERIFIED || "$gradle_exit" -eq 0 ]] || { validation_fail verified_run_has_nonzero_gradle_exit; return 1; }
  fi
  redactions="$(harness_manifest_get "$manifest" log_secret_redactions)"
  [[ "$redactions" =~ ^[0-9]+$ ]] || { validation_fail invalid_log_secret_redactions; return 1; }
  [[ "$state" != VERIFIED || "$redactions" -eq 0 ]] || { validation_fail verified_log_required_secret_redaction; return 1; }
  if grep -aEq 'sk-[A-Za-z0-9_-]{12,}|DEEPSEEK_API_KEY[[:space:]]*[=:][[:space:]]*[^<[:space:]]' "$canonical/server.log"; then
    validation_fail server_log_contains_credential_like_data
    return 1
  fi

  IFS= read -r header < "$result"
  [[ "$header" == $'scenario\trequested_seed\tactual_seed\tresult\tpassed\ttotal\texit_code\tsummary' ]] || {
    validation_fail invalid_result_header
    return 1
  }
  required_count="$(wc -l < "$result" | tr -d ' ')"
  [[ "$required_count" == 2 ]] || { validation_fail result_must_have_exactly_one_row; return 1; }
  row="$(sed -n '2p' "$result")"
  IFS=$'\t' read -r row_scenario row_requested row_actual row_result row_passed row_total row_exit row_summary <<EOF
$row
EOF
  [[ -n "$row_summary" && "$row" == *$'\t'* ]] || { validation_fail malformed_result_row; return 1; }
  manifest_result="$(harness_manifest_get "$manifest" result)"
  manifest_passed="$(harness_manifest_get "$manifest" passed)"
  manifest_total="$(harness_manifest_get "$manifest" total)"
  [[ "$row_scenario" == "$scenario" && "$row_requested" == "$requested" && "$row_actual" == "$actual" && \
     "$row_result" == "$manifest_result" && "$row_passed" == "$manifest_passed" && "$row_total" == "$manifest_total" ]] || {
    validation_fail result_manifest_mismatch
    return 1
  }
  case "$row_result" in PASS|FAIL|ERROR) ;; *) validation_fail invalid_result; return 1 ;; esac
  [[ "$row_passed" =~ ^[0-9]+$ && "$row_total" =~ ^[0-9]+$ && "$row_exit" =~ ^[0-9]+$ ]] || {
    validation_fail invalid_result_numbers
    return 1
  }
  if [[ "$row_result" == PASS && ( "$row_total" -eq 0 || "$row_passed" -ne "$row_total" || "$row_exit" -ne 0 ) ]]; then
    validation_fail inconsistent_pass_result
    return 1
  fi
  if [[ "$row_result" != PASS && "$row_exit" -eq 0 ]]; then
    validation_fail failing_result_has_zero_exit
    return 1
  fi
  log_summary_count="$(grep -acE '\[AIBot Verify\] summary' "$canonical/server.log" 2>/dev/null || true)"
  log_summary="$(grep -aE '\[AIBot Verify\] summary' "$canonical/server.log" 2>/dev/null | tail -1 || true)"
  log_summary="$(harness_tsv_value "$log_summary")"
  if [[ "$row_result" == PASS || "$row_result" == FAIL ]]; then
    [[ "$log_summary_count" -eq 1 && "$log_summary" == "$row_summary" ]] || {
      validation_fail terminal_summary_result_mismatch
      return 1
    }
    parsed_log_passed="$(printf '%s\n' "$log_summary" | sed -nE 's/.*summary ([0-9]+)\/([0-9]+) PASS.*/\1/p')"
    parsed_log_total="$(printf '%s\n' "$log_summary" | sed -nE 's/.*summary ([0-9]+)\/([0-9]+) PASS.*/\2/p')"
    [[ "$parsed_log_passed" == "$row_passed" && "$parsed_log_total" == "$row_total" ]] || {
      validation_fail terminal_summary_counts_mismatch
      return 1
    }
  elif [[ "$log_summary_count" -eq 0 ]]; then
    [[ "$row_summary" == NO_SUMMARY ]] || { validation_fail missing_summary_not_recorded; return 1; }
  else
    [[ "$log_summary_count" -eq 1 && "$log_summary" == "$row_summary" ]] || {
      validation_fail malformed_terminal_summary_mismatch
      return 1
    }
  fi

  if [[ $REQUIRE_VERIFIED -eq 1 && "$state" != VERIFIED ]]; then
    validation_fail "$reason"
    return 1
  fi
  VALIDATION_REASON="$state"
  return 0
}

validate_batch() {
  local input="$1" input_absolute canonical batch_root expected actual file line hash name calculated
  local manifest result key value batch_id state reason requires_verified profile mode scenario manifest_result
  local commit batch_commit_end batch_worktree batch_worktree_end seed_csv runs_requested expected_rows seed_allowed expected_seed batch_capabilities batch_llm batch_started batch_finished batch_sealed old_batch_ifs
  local header rows seed run_index run_exit evidence_state run_result evidence_path evidence_hash
  local referenced referenced_state referenced_result referenced_hash all_verified=1 all_pass=1
  local -a expected_seeds

  VALIDATION_REASON=unknown
  [[ -d "$input" && ! -L "$input" ]] || { validation_fail path_missing_or_symlink; return 1; }
  case "$input" in /*) input_absolute="$input" ;; *) input_absolute="$(pwd -P)/$input" ;; esac
  case "$input_absolute/" in *'/../'*|*'/./'*|*'//'*) validation_fail traversal_path_component; return 1 ;; esac
  canonical="$(harness_real_dir "$input")" || { validation_fail path_unresolvable; return 1; }
  [[ -d "$HARNESS_BATCH_ROOT" && ! -L "$HARNESS_BATCH_ROOT" ]] || {
    validation_fail batch_root_missing_or_symlink
    return 1
  }
  batch_root="$(harness_real_dir "$HARNESS_BATCH_ROOT")"
  [[ "$(dirname "$canonical")" == "$batch_root" ]] || { validation_fail batch_path_not_direct_child; return 1; }
  assert_no_symlink_components "$input_absolute" "$batch_root" || {
    validation_fail symlink_or_traversal_component
    return 1
  }
  harness_assert_no_tree_symlinks "$canonical" || { validation_fail batch_tree_contains_symlink; return 1; }

  expected=$'LOCKED\nchecksums.sha256\nmanifest.tsv\nresult.tsv'
  if ! actual="$(find "$canonical" -mindepth 1 -maxdepth 1 -print | sed 's#^.*/##' | LC_ALL=C sort)"; then
    validation_fail batch_tree_enumeration_failed
    return 1
  fi
  [[ "$actual" == "$expected" ]] || { validation_fail batch_has_missing_or_extra_files; return 1; }
  for file in LOCKED checksums.sha256 manifest.tsv result.tsv; do
    [[ -f "$canonical/$file" && ! -L "$canonical/$file" ]] || {
      validation_fail "invalid_batch_file:$file"
      return 1
    }
  done

  expected=$'manifest.tsv\nresult.tsv'
  actual=""
  while IFS= read -r line || [[ -n "$line" ]]; do
    hash="${line%%  *}"
    name="${line#*  }"
    if [[ "$line" == "$hash" || ${#hash} -ne 64 || "$hash" == *[!0-9a-f]* ]]; then
      validation_fail malformed_batch_checksum
      return 1
    fi
    case "$name" in manifest.tsv|result.tsv) ;; *) validation_fail unsafe_batch_checksum_path; return 1 ;; esac
    calculated="$(harness_sha256 "$canonical/$name")" || return 1
    [[ "$calculated" == "$hash" ]] || { validation_fail "batch_checksum_mismatch:$name"; return 1; }
    actual="${actual}${actual:+$'\n'}$name"
  done < "$canonical/checksums.sha256"
  actual="$(printf '%s\n' "$actual" | LC_ALL=C sort)"
  [[ "$actual" == "$expected" ]] || { validation_fail incomplete_or_duplicate_batch_checksums; return 1; }
  harness_verify_locked_marker "$canonical/LOCKED" || { validation_fail batch_locked_marker_mismatch; return 1; }

  manifest="$canonical/manifest.tsv"
  result="$canonical/result.tsv"
  awk -F '\t' 'NF != 2 || $1 == "" || $2 == "" || seen[$1]++ { exit 2 }' "$manifest" || {
    validation_fail malformed_batch_manifest
    return 1
  }
  for key in schema_version batch_id evidence_state verification_reason commit_sha finished_commit_sha \
    working_tree_state working_tree_state_end timestamp_utc finished_at_utc scenario seeds runs_per_seed \
    profile operator_capabilities mode llm_enabled requires_verified dependency_root result; do
    value="$(harness_manifest_get "$manifest" "$key")"
    [[ -n "$value" ]] || { validation_fail "missing_batch_manifest_key:$key"; return 1; }
  done
  [[ "$(harness_manifest_get "$manifest" schema_version)" == 1 ]] || {
    validation_fail unsupported_batch_schema
    return 1
  }
  batch_id="$(harness_manifest_get "$manifest" batch_id)"
  harness_safe_id "$batch_id" batch_id 180 >/dev/null 2>&1 || { validation_fail unsafe_batch_id; return 1; }
  [[ "$batch_id" == "$(basename "$canonical")" ]] || { validation_fail batch_id_path_mismatch; return 1; }
  commit="$(harness_manifest_get "$manifest" commit_sha)"
  if [[ ${#commit} -ne 40 && ${#commit} -ne 64 ]] || [[ "$commit" == *[!0-9a-f]* ]] || \
     ! git -C "$ROOT" cat-file -e "$commit^{commit}" 2>/dev/null; then
    validation_fail invalid_or_missing_batch_commit
    return 1
  fi
  batch_commit_end="$(harness_manifest_get "$manifest" finished_commit_sha)"
  if [[ "$batch_commit_end" != unknown ]] && ! git -C "$ROOT" cat-file -e "$batch_commit_end^{commit}" 2>/dev/null; then
    validation_fail invalid_finished_batch_commit
    return 1
  fi
  batch_worktree="$(harness_manifest_get "$manifest" working_tree_state)"
  batch_worktree_end="$(harness_manifest_get "$manifest" working_tree_state_end)"
  case "$batch_worktree" in clean|dirty|unknown) ;; *) validation_fail invalid_batch_worktree_state; return 1 ;; esac
  case "$batch_worktree_end" in clean|dirty|unknown) ;; *) validation_fail invalid_finished_batch_worktree_state; return 1 ;; esac
  batch_started="$(harness_manifest_get "$manifest" timestamp_utc)"
  batch_finished="$(harness_manifest_get "$manifest" finished_at_utc)"
  [[ "$batch_started" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z$ && \
     "$batch_finished" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z$ ]] || {
    validation_fail invalid_batch_timestamps
    return 1
  }
  batch_sealed="$(harness_manifest_get "$canonical/LOCKED" sealed_at_utc)"
  [[ ! "$batch_sealed" < "$batch_finished" ]] || { validation_fail batch_sealed_before_finish; return 1; }
  state="$(harness_manifest_get "$manifest" evidence_state)"
  reason="$(harness_manifest_get "$manifest" verification_reason)"
  case "$state" in VERIFIED|UNVERIFIED) ;; *) validation_fail invalid_batch_state; return 1 ;; esac
  if [[ "$state" == VERIFIED && ( "$batch_commit_end" != "$commit" || "$batch_worktree" != clean || "$batch_worktree_end" != clean ) ]]; then
    validation_fail unstable_batch_claims_verified
    return 1
  fi
  if [[ "$state" == VERIFIED && "$reason" != complete ]] || \
     [[ "$state" == UNVERIFIED && ( -z "$reason" || "$reason" == complete ) ]]; then
    validation_fail batch_state_reason_mismatch
    return 1
  fi
  if [[ "$state" == VERIFIED ]] && ! git -C "$ROOT" merge-base --is-ancestor "$commit" HEAD 2>/dev/null; then
    validation_fail verified_batch_commit_not_reachable_from_head
    return 1
  fi
  requires_verified="$(harness_manifest_get "$manifest" requires_verified)"
  case "$requires_verified" in yes|no) ;; *) validation_fail invalid_batch_verified_policy; return 1 ;; esac
  [[ "$(harness_manifest_get "$manifest" dependency_root)" == artifacts/evidence ]] || {
    validation_fail invalid_batch_dependency_root
    return 1
  }
  profile="$(harness_manifest_get "$manifest" profile)"
  case "$profile" in strict_survival|operator) ;; *) validation_fail invalid_batch_profile; return 1 ;; esac
  batch_capabilities="$(harness_manifest_get "$manifest" operator_capabilities)"
  [[ "$batch_capabilities" =~ ^hiddenBlockScan=(true|false),emergencyTeleport=(true|false),forcedPickup=(true|false),manualTeleport=(true|false)$ ]] || {
    validation_fail malformed_batch_capabilities
    return 1
  }
  if [[ "$profile" == strict_survival && "$batch_capabilities" != 'hiddenBlockScan=false,emergencyTeleport=false,forcedPickup=false,manualTeleport=false' ]]; then
    validation_fail strict_batch_has_privileged_capability
    return 1
  fi
  mode="$(harness_manifest_get "$manifest" mode)"
  case "$mode" in deterministic|llm_story|fixture) ;; *) validation_fail invalid_batch_mode; return 1 ;; esac
  batch_llm="$(harness_manifest_get "$manifest" llm_enabled)"
  case "$batch_llm" in yes|no) ;; *) validation_fail invalid_batch_llm_enabled; return 1 ;; esac
  if [[ "$mode" == llm_story && "$batch_llm" != yes ]] || \
     [[ "$mode" != llm_story && "$batch_llm" != no ]]; then
    validation_fail batch_mode_llm_mismatch
    return 1
  fi
  scenario="$(harness_manifest_get "$manifest" scenario)"
  harness_safe_id "$scenario" scenario 96 >/dev/null 2>&1 || { validation_fail unsafe_batch_scenario; return 1; }
  seed_csv="$(harness_manifest_get "$manifest" seeds)"
  old_batch_ifs="$IFS"
  IFS=',' read -r -a expected_seeds <<< "$seed_csv"
  IFS="$old_batch_ifs"
  [[ ${#expected_seeds[@]} -gt 0 ]] || { validation_fail empty_batch_seed_set; return 1; }
  if ! printf '%s\n' "${expected_seeds[@]}" | awk 'seen[$0]++ { exit 2 }'; then
    validation_fail duplicate_batch_manifest_seed
    return 1
  fi
  for expected_seed in "${expected_seeds[@]}"; do
    harness_safe_seed "$expected_seed" >/dev/null 2>&1 || { validation_fail invalid_batch_manifest_seed; return 1; }
  done
  runs_requested="$(harness_manifest_get "$manifest" runs_per_seed)"
  [[ "$runs_requested" =~ ^[1-9][0-9]*$ ]] || { validation_fail invalid_runs_per_seed; return 1; }
  expected_rows=$((${#expected_seeds[@]} * runs_requested))

  IFS= read -r header < "$result"
  [[ "$header" == $'seed\trun_index\texit_code\tevidence_state\tresult\tevidence_path\tevidence_lock_sha256' ]] || {
    validation_fail invalid_batch_result_header
    return 1
  }
  awk -F '\t' 'NR == 1 { next } NF != 7 || seen[$1 SUBSEP $2]++ { exit 2 }' "$result" || {
    validation_fail malformed_or_duplicate_batch_result_row
    return 1
  }
  rows=0
  while IFS=$'\t' read -r seed run_index run_exit evidence_state run_result evidence_path evidence_hash; do
    rows=$((rows + 1))
    harness_safe_seed "$seed" >/dev/null 2>&1 || { validation_fail invalid_batch_seed; return 1; }
    [[ "$run_index" =~ ^[1-9][0-9]*$ && "$run_index" -le "$runs_requested" && "$run_exit" =~ ^[0-9]+$ ]] || {
      validation_fail invalid_batch_numeric_field
      return 1
    }
    seed_allowed=0
    for expected_seed in "${expected_seeds[@]}"; do
      [[ "$seed" != "$expected_seed" ]] || seed_allowed=1
    done
    [[ $seed_allowed -eq 1 ]] || { validation_fail unexpected_batch_seed; return 1; }
    case "$evidence_state" in VERIFIED|UNVERIFIED) ;; *) validation_fail invalid_referenced_state; return 1 ;; esac
    case "$run_result" in PASS|FAIL|ERROR) ;; *) validation_fail invalid_referenced_result; return 1 ;; esac
    if [[ "$evidence_path" == - ]]; then
      if [[ "$evidence_state" != UNVERIFIED || "$run_result" != ERROR || "$evidence_hash" != - || "$run_exit" -eq 0 ]]; then
        validation_fail inconsistent_missing_evidence_row
        return 1
      fi
      all_verified=0
      all_pass=0
      continue
    fi
    if [[ "$evidence_path" == *'..'* || ! "$evidence_path" =~ ^artifacts/evidence/[A-Za-z0-9][A-Za-z0-9_.+-]*$ ]]; then
      validation_fail unsafe_referenced_evidence_path
      return 1
    fi
    referenced="$ROOT/$evidence_path"
    validate_one "$referenced" || { validation_fail referenced_evidence_invalid; return 1; }
    referenced_state="$(harness_manifest_get "$referenced/manifest.tsv" evidence_state)"
    referenced_result="$(harness_manifest_get "$referenced/manifest.tsv" result)"
    referenced_hash="$(harness_sha256 "$referenced/LOCKED")"
    [[ "$evidence_state" == "$referenced_state" && "$run_result" == "$referenced_result" && \
       "$evidence_hash" == "$referenced_hash" ]] || {
      validation_fail referenced_evidence_metadata_mismatch
      return 1
    }
    [[ "$(harness_manifest_get "$referenced/manifest.tsv" scenario)" == "$scenario" && \
       "$(harness_manifest_get "$referenced/manifest.tsv" requested_seed)" == "$seed" && \
       "$(harness_manifest_get "$referenced/manifest.tsv" profile)" == "$profile" && \
       "$(harness_manifest_get "$referenced/manifest.tsv" operator_capabilities)" == "$batch_capabilities" && \
       "$(harness_manifest_get "$referenced/manifest.tsv" llm_enabled)" == "$batch_llm" && \
       "$(harness_manifest_get "$referenced/manifest.tsv" commit_sha)" == "$commit" && \
       "$(harness_manifest_get "$referenced/manifest.tsv" mode)" == "$mode" ]] || {
      validation_fail referenced_run_not_in_requested_batch
      return 1
    }
    [[ "$evidence_state" == VERIFIED ]] || all_verified=0
    [[ "$run_result" == PASS && "$run_exit" -eq 0 ]] || all_pass=0
  done < <(sed -n '2,$p' "$result")
  [[ $rows -eq $expected_rows ]] || { validation_fail incomplete_batch_matrix; return 1; }
  if [[ $all_verified -eq 0 && "$state" != UNVERIFIED ]]; then
    validation_fail batch_state_overclaims_incomplete_runs
    return 1
  fi
  if [[ "$requires_verified" == yes && ( $all_verified -ne 1 || "$state" != VERIFIED ) ]]; then all_pass=0; fi
  manifest_result="$(harness_manifest_get "$manifest" result)"
  if [[ $all_pass -eq 1 && "$manifest_result" != PASS ]] || \
     [[ $all_pass -eq 0 && "$manifest_result" != FAIL ]]; then
    validation_fail batch_result_mismatch
    return 1
  fi
  if [[ $REQUIRE_VERIFIED -eq 1 && "$state" != VERIFIED ]]; then
    validation_fail "$reason"
    return 1
  fi
  VALIDATION_REASON="$state"
  return 0
}

run_self_test() (
  set -euo pipefail
  local fixture evidence output batch batch_output link traversal relative
  local -a generated
  fixture="$(mktemp "${TMPDIR:-/tmp}/aibot-evidence-self-test.XXXXXX")"
  generated=()
  cleanup_self_test() {
    local path
    rm -f -- "$fixture" "${link:-}"
    for path in "${generated[@]}"; do
      case "$path" in
        "$HARNESS_ARTIFACT_ROOT"/*|"$HARNESS_BATCH_ROOT"/*)
          if [[ -d "$path" && ! -L "$path" ]]; then
            chmod -R u+w "$path" 2>/dev/null || true
            rm -rf -- "$path"
          fi
          ;;
      esac
    done
    rmdir "$HARNESS_BATCH_ROOT" 2>/dev/null || true
    rmdir "$(cd "${TMPDIR:-/tmp}" && pwd -P)/aibot-evidence-runs" 2>/dev/null || true
  }
  trap cleanup_self_test EXIT

  printf '%s\n' '[Server thread/INFO]: Seed: [424242]' \
    '[Server thread/INFO]: [AIBot Verify] summary 1/1 PASS {evidence_self_test=PASS}' > "$fixture"
  output="$("$ROOT/scripts/evidence_run.sh" --scenario evidence_self_test --seed 424242 --fixture-log "$fixture")"
  evidence="$(printf '%s\n' "$output" | sed -n 's/^EVIDENCE_DIR=//p' | tail -1)"
  [[ -d "$evidence" ]]
  generated+=("$evidence")
  "$ROOT/scripts/evidence_validate.sh" "$evidence" >/dev/null
  if "$ROOT/scripts/evidence_validate.sh" --require-verified "$evidence" >/dev/null 2>&1; then
    printf 'evidence-self-test: fixture incorrectly became VERIFIED\n' >&2
    exit 1
  fi
  if "$ROOT/scripts/pin_baseline.sh" index.tsv "$evidence" >/dev/null 2>&1; then
    printf 'evidence-self-test: reserved capability id was accepted\n' >&2
    exit 1
  fi

  link="$HARNESS_ARTIFACT_ROOT/.self-test-link-$$"
  ln -s "$evidence" "$link"
  if "$ROOT/scripts/evidence_validate.sh" "$link" >/dev/null 2>&1; then
    printf 'evidence-self-test: symlink evidence was accepted\n' >&2
    exit 1
  fi
  rm -f -- "$link"
  traversal="$HARNESS_ARTIFACT_ROOT/../evidence/$(basename "$evidence")"
  if "$ROOT/scripts/evidence_validate.sh" "$traversal" >/dev/null 2>&1; then
    printf 'evidence-self-test: traversal path was accepted\n' >&2
    exit 1
  fi

  chmod -R u+w "$evidence"
  printf 'tampered\n' >> "$evidence/server.log"
  if "$ROOT/scripts/evidence_validate.sh" "$evidence" >/dev/null 2>&1; then
    printf 'evidence-self-test: checksum tamper was accepted\n' >&2
    exit 1
  fi
  harness_write_checksums "$evidence"
  harness_write_locked_marker "$evidence"
  "$ROOT/scripts/evidence_validate.sh" "$evidence" >/dev/null
  printf 'schema_version\t1\n' >> "$evidence/LOCKED"
  if "$ROOT/scripts/evidence_validate.sh" "$evidence" >/dev/null 2>&1; then
    printf 'evidence-self-test: duplicate LOCKED key was accepted\n' >&2
    exit 1
  fi
  harness_write_locked_marker "$evidence"
  python3 - "$evidence/effective-config.redacted.json" <<'PY'
import sys
path = sys.argv[1]
value = open(path, encoding="utf-8").read()
value = value.replace('"schemaVersion": 1,', '"schemaVersion": 1,\n  "schemaVersion": 1,')
open(path, "w", encoding="utf-8").write(value)
PY
  harness_write_checksums "$evidence"
  harness_write_locked_marker "$evidence"
  if "$ROOT/scripts/evidence_validate.sh" "$evidence" >/dev/null 2>&1; then
    printf 'evidence-self-test: duplicate JSON key was accepted\n' >&2
    exit 1
  fi

  batch_output="$("$ROOT/scripts/evidence_batch.sh" --scenario evidence_self_test --seeds 424242 --runs 2 --fixture-log "$fixture" --allow-unverified)"
  batch="$(printf '%s\n' "$batch_output" | sed -n 's/^BATCH_EVIDENCE_DIR=//p' | tail -1)"
  [[ -d "$batch" ]]
  generated+=("$batch")
  while IFS= read -r relative; do
    [[ -n "$relative" && "$relative" != - ]] || continue
    generated+=("$ROOT/$relative")
  done < <(awk -F '\t' 'NR > 1 { print $6 }' "$batch/result.tsv")
  "$ROOT/scripts/evidence_validate.sh" "$batch" >/dev/null
  chmod -R u+w "$batch"
  sed -n '1,2p' "$batch/result.tsv" > "$batch/result.tsv.tmp"
  mv "$batch/result.tsv.tmp" "$batch/result.tsv"
  {
    printf '%s  manifest.tsv\n' "$(harness_sha256 "$batch/manifest.tsv")"
    printf '%s  result.tsv\n' "$(harness_sha256 "$batch/result.tsv")"
  } > "$batch/checksums.sha256"
  harness_write_locked_marker "$batch"
  if "$ROOT/scripts/evidence_validate.sh" "$batch" >/dev/null 2>&1; then
    printf 'evidence-self-test: incomplete batch matrix was accepted\n' >&2
    exit 1
  fi

  printf 'evidence-self-test: PASS\n'
)

if [[ $SELF_TEST -eq 1 ]]; then
  run_self_test
  exit
fi

overall=0
for evidence_dir in "$@"; do
  canonical_candidate="$(harness_real_dir "$evidence_dir" 2>/dev/null || true)"
  if [[ -n "$canonical_candidate" && -d "$HARNESS_BATCH_ROOT" && ! -L "$HARNESS_BATCH_ROOT" && \
        "$(dirname "$canonical_candidate")" == "$(harness_real_dir "$HARNESS_BATCH_ROOT")" ]]; then
    validator=validate_batch
  else
    validator=validate_one
  fi
  if "$validator" "$evidence_dir"; then
    printf '%s\t%s\n' "$VALIDATION_REASON" "$(harness_real_dir "$evidence_dir")"
  else
    printf 'UNVERIFIED\t%s\t%s\n' "$evidence_dir" "$VALIDATION_REASON" >&2
    overall=1
  fi
done
exit "$overall"
