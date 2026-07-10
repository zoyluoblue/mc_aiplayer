#!/usr/bin/env bash
# Explicitly pin one VERIFIED run. There is intentionally no latest/best scan.
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd -P)"
HARNESS_REPO_ROOT="$ROOT"
# shellcheck source=scripts/lib/harness.sh
source "$ROOT/scripts/lib/harness.sh"

usage() {
  printf 'usage: scripts/pin_baseline.sh [--allow-failure] <capability-id> <artifacts/evidence/run-id>\n' >&2
}

ALLOW_FAILURE=0
if [[ "${1:-}" == --allow-failure ]]; then
  ALLOW_FAILURE=1
  shift
fi
[[ $# -eq 2 ]] || { usage; exit 2; }
CAPABILITY_ID="$1"
SOURCE_INPUT="$2"
harness_safe_id "$CAPABILITY_ID" capability_id 80 || exit 2
case "$CAPABILITY_ID" in
  index.tsv|LOCKED|checksums.sha256)
    printf 'pin-baseline: reserved capability id: %s\n' "$CAPABILITY_ID" >&2
    exit 2
    ;;
esac

[[ -d "$SOURCE_INPUT" && ! -L "$SOURCE_INPUT" ]] || {
  printf 'pin-baseline: source must be an existing non-symlink evidence directory\n' >&2
  exit 2
}
SOURCE="$(harness_real_dir "$SOURCE_INPUT")" || exit 2
[[ -d "$HARNESS_ARTIFACT_ROOT" && ! -L "$HARNESS_ARTIFACT_ROOT" ]] || {
  printf 'pin-baseline: artifact evidence root does not exist\n' >&2
  exit 2
}
ARTIFACT_ROOT="$(harness_real_dir "$HARNESS_ARTIFACT_ROOT")"
if [[ "$(dirname "$SOURCE")" != "$ARTIFACT_ROOT" ]]; then
  printf 'pin-baseline: source must be a direct child of artifacts/evidence\n' >&2
  exit 2
fi
"$ROOT/scripts/evidence_validate.sh" --require-verified "$SOURCE_INPUT" >/dev/null || {
  printf 'pin-baseline: only structurally valid VERIFIED evidence can be pinned\n' >&2
  exit 2
}
SOURCE_RESULT="$(harness_manifest_get "$SOURCE/manifest.tsv" result)"
if [[ "$SOURCE_RESULT" != PASS && $ALLOW_FAILURE -ne 1 ]]; then
  printf 'pin-baseline: refusing to pin %s evidence without explicit --allow-failure\n' "$SOURCE_RESULT" >&2
  exit 2
fi

RUN_ID="$(harness_manifest_get "$SOURCE/manifest.tsv" run_id)"
COMMIT_SHA="$(harness_manifest_get "$SOURCE/manifest.tsv" commit_sha)"
SCENARIO="$(harness_manifest_get "$SOURCE/manifest.tsv" scenario)"
harness_safe_id "$RUN_ID" run_id 160 || exit 2
CAPABILITY_REGISTRY="$ROOT/reports/capability_baseline_manifest.tsv"
[[ -f "$CAPABILITY_REGISTRY" && ! -L "$CAPABILITY_REGISTRY" ]] || {
  printf 'pin-baseline: capability registry is missing or unsafe\n' >&2
  exit 2
}
EXPECTED_SCENARIO="$(awk -F '\t' -v capability="$CAPABILITY_ID" 'NR > 1 && $1 == capability { print $3; found++ } END { if (found != 1) exit 2 }' "$CAPABILITY_REGISTRY")" || {
  printf 'pin-baseline: capability id must have exactly one registry entry: %s\n' "$CAPABILITY_ID" >&2
  exit 2
}
[[ "$SCENARIO" == "$EXPECTED_SCENARIO" ]] || {
  printf 'pin-baseline: capability %s requires scenario %s, got %s\n' "$CAPABILITY_ID" "$EXPECTED_SCENARIO" "$SCENARIO" >&2
  exit 2
}

harness_prepare_root "$HARNESS_BASELINE_ROOT" || exit 3
harness_assert_not_symlink "$ROOT/reports" reports_directory || exit 3
LOCK="$HARNESS_BASELINE_ROOT/.pin.lock"
STAGING=""
INDEX_TMP=""
PUBLISHED=0

cleanup() {
  local status=$?
  trap - EXIT HUP INT TERM
  if [[ $PUBLISHED -eq 0 && -n "$STAGING" ]]; then
    harness_safe_remove_staging "$STAGING" "$HARNESS_BASELINE_ROOT" || true
  fi
  if [[ -n "$INDEX_TMP" ]]; then
    case "$INDEX_TMP" in "$HARNESS_BASELINE_ROOT"/.index.*) rm -f -- "$INDEX_TMP" ;; esac
  fi
  harness_release_lock || true
  exit "$status"
}
trap cleanup EXIT
trap 'exit 129' HUP
trap 'exit 130' INT
trap 'exit 143' TERM

harness_acquire_lock "$LOCK" || exit 3
CAPABILITY_DIR="$HARNESS_BASELINE_ROOT/$CAPABILITY_ID"
harness_assert_not_symlink "$CAPABILITY_DIR" capability_directory || exit 3
mkdir -p "$CAPABILITY_DIR" || exit 3
harness_assert_not_symlink "$CAPABILITY_DIR" capability_directory || exit 3
DESTINATION="$CAPABILITY_DIR/$RUN_ID"
if [[ -e "$DESTINATION" || -L "$DESTINATION" ]]; then
  [[ -d "$DESTINATION" && ! -L "$DESTINATION" ]] || {
    printf 'pin-baseline: existing destination is not a safe evidence directory\n' >&2
    exit 3
  }
  "$ROOT/scripts/evidence_validate.sh" --require-verified "$DESTINATION" >/dev/null || {
    printf 'pin-baseline: existing destination is not valid VERIFIED evidence\n' >&2
    exit 3
  }
  [[ "$(harness_sha256 "$DESTINATION/LOCKED")" == "$(harness_sha256 "$SOURCE/LOCKED")" ]] || {
    printf 'pin-baseline: existing destination differs from requested evidence\n' >&2
    exit 3
  }
  PUBLISHED=1
else
  STAGING="$(mktemp -d "$HARNESS_BASELINE_ROOT/.staging.${CAPABILITY_ID}.${RUN_ID}.XXXXXX")" || exit 3
  for file in LOCKED checksums.sha256 effective-config.redacted.json manifest.tsv result.tsv server.log; do
    cp -p "$SOURCE/$file" "$STAGING/$file" || exit 3
  done
  harness_verify_bundle_files "$STAGING" || exit 3
  harness_publish_staging "$STAGING" "$DESTINATION" || exit 3
  PUBLISHED=1
  STAGING=""
fi
"$ROOT/scripts/evidence_validate.sh" --require-verified "$DESTINATION" >/dev/null || {
  printf 'pin-baseline: post-copy validation failed; rerun can recover the immutable destination\n' >&2
  exit 3
}
# Destination metadata is authoritative. Re-read after the copy/idempotent
# recovery window so a source swap cannot bypass the failure gate or poison the
# index with provenance from a different bundle.
RUN_ID="$(harness_manifest_get "$DESTINATION/manifest.tsv" run_id)"
COMMIT_SHA="$(harness_manifest_get "$DESTINATION/manifest.tsv" commit_sha)"
SCENARIO="$(harness_manifest_get "$DESTINATION/manifest.tsv" scenario)"
DESTINATION_RESULT="$(harness_manifest_get "$DESTINATION/manifest.tsv" result)"
DESTINATION_PROFILE="$(harness_manifest_get "$DESTINATION/manifest.tsv" profile)"
DESTINATION_MODE="$(harness_manifest_get "$DESTINATION/manifest.tsv" mode)"
EXPECTED_SCENARIO_FINAL="$(awk -F '\t' -v capability="$CAPABILITY_ID" 'NR > 1 && $1 == capability { print $3; found++ } END { if (found != 1) exit 2 }' "$CAPABILITY_REGISTRY")" || exit 2
[[ "$EXPECTED_SCENARIO_FINAL" == "$EXPECTED_SCENARIO" && "$SCENARIO" == "$EXPECTED_SCENARIO_FINAL" ]] || {
  printf 'pin-baseline: destination scenario no longer matches capability registry\n' >&2
  exit 2
}
[[ "$DESTINATION_PROFILE" == strict_survival && "$DESTINATION_MODE" == deterministic ]] || {
  printf 'pin-baseline: capability baselines require strict_survival deterministic evidence\n' >&2
  exit 2
}
if [[ "$DESTINATION_RESULT" != PASS && $ALLOW_FAILURE -ne 1 ]]; then
  printf 'pin-baseline: refusing to pin %s destination without explicit --allow-failure\n' "$DESTINATION_RESULT" >&2
  exit 2
fi

# The index is the only selector. Old run directories remain immutable and are
# never deleted when the explicit pointer moves to a new run.
INDEX="$HARNESS_BASELINE_ROOT/index.tsv"
INDEX_TMP="$(mktemp "$HARNESS_BASELINE_ROOT/.index.${CAPABILITY_ID}.XXXXXX")" || exit 3
EXPECTED_HEADER=$'capability_id\trun_id\tevidence_path\tevidence_lock_sha256\tcommit_sha\tscenario\tpinned_at_utc'
if [[ -e "$INDEX" && ( ! -f "$INDEX" || -L "$INDEX" ) ]]; then
  printf 'pin-baseline: baseline index must be a regular non-symlink file\n' >&2
  exit 3
fi
if [[ -f "$INDEX" ]]; then
  harness_assert_not_symlink "$INDEX" baseline_index || exit 3
  IFS= read -r actual_header < "$INDEX"
  [[ "$actual_header" == "$EXPECTED_HEADER" ]] || {
    rm -f -- "$INDEX_TMP"
    printf 'pin-baseline: refusing to rewrite malformed baseline index\n' >&2
    exit 3
  }
  while IFS=$'\t' read -r existing_cap existing_run existing_path existing_hash existing_commit existing_scenario existing_pinned || \
      [[ -n "${existing_cap:-}${existing_run:-}${existing_path:-}" ]]; do
    [[ "$existing_cap" != capability_id ]] || continue
    harness_safe_id "$existing_cap" capability_id 80 >/dev/null 2>&1 || exit 3
    harness_safe_id "$existing_run" run_id 160 >/dev/null 2>&1 || exit 3
    harness_safe_id "$existing_scenario" scenario 96 >/dev/null 2>&1 || exit 3
    [[ "$existing_path" == "reports/baselines/$existing_cap/$existing_run" && \
       ${#existing_hash} -eq 64 && "$existing_hash" != *[!0-9a-f]* && \
       ( ${#existing_commit} -eq 40 || ${#existing_commit} -eq 64 ) && "$existing_commit" != *[!0-9a-f]* && \
       "$existing_pinned" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z$ ]] || {
      printf 'pin-baseline: baseline index contains unsafe provenance fields\n' >&2
      exit 3
    }
    existing_bundle="$ROOT/$existing_path"
    "$ROOT/scripts/evidence_validate.sh" --require-verified "$existing_bundle" >/dev/null || exit 3
    existing_expected_scenario="$(awk -F '\t' -v capability="$existing_cap" 'NR > 1 && $1 == capability { print $3; found++ } END { if (found != 1) exit 2 }' "$CAPABILITY_REGISTRY")" || exit 3
    [[ "$(harness_sha256 "$existing_bundle/LOCKED")" == "$existing_hash" && \
       "$(harness_manifest_get "$existing_bundle/manifest.tsv" commit_sha)" == "$existing_commit" && \
       "$(harness_manifest_get "$existing_bundle/manifest.tsv" scenario)" == "$existing_scenario" && \
       "$existing_scenario" == "$existing_expected_scenario" && \
       "$(harness_manifest_get "$existing_bundle/manifest.tsv" profile)" == strict_survival && \
       "$(harness_manifest_get "$existing_bundle/manifest.tsv" mode)" == deterministic ]] || {
      printf 'pin-baseline: baseline index does not match its pinned bundle\n' >&2
      exit 3
    }
  done < "$INDEX"
  awk -F '\t' -v capability="$CAPABILITY_ID" '
    NR == 1 { print; next }
    NF != 7 || $1 == "" || seen[$1]++ || index($3, "..") ||
      $1 !~ /^[A-Za-z0-9][A-Za-z0-9_.+-]*$/ ||
      $2 !~ /^[A-Za-z0-9][A-Za-z0-9_.+-]*$/ ||
      $3 !~ /^reports\/baselines\/[A-Za-z0-9][A-Za-z0-9_.+-]*\/[A-Za-z0-9][A-Za-z0-9_.+-]*$/ ||
      $4 !~ /^[0-9a-f]+$/ || $5 !~ /^[0-9a-f]+$/ { exit 2 }
    $1 != capability { print }
  ' "$INDEX" > "$INDEX_TMP" || {
    rm -f -- "$INDEX_TMP"
    printf 'pin-baseline: baseline index contains malformed or duplicate rows\n' >&2
    exit 3
  }
else
  printf '%s\n' "$EXPECTED_HEADER" > "$INDEX_TMP"
fi

RELATIVE_PATH="reports/baselines/$CAPABILITY_ID/$RUN_ID"
EVIDENCE_HASH="$(harness_sha256 "$DESTINATION/LOCKED")" || exit 3
printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\n' "$CAPABILITY_ID" "$RUN_ID" "$RELATIVE_PATH" \
  "$EVIDENCE_HASH" "$COMMIT_SHA" "$SCENARIO" "$(harness_now_utc)" >> "$INDEX_TMP"
chmod 0644 "$INDEX_TMP" || exit 3
harness_atomic_replace_file "$INDEX_TMP" "$INDEX" || exit 3
INDEX_TMP=""

printf 'PINNED_BASELINE=%s\n' "$RELATIVE_PATH"
