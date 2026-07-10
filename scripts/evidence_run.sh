#!/usr/bin/env bash
# Run one isolated dedicated-server scenario and atomically publish its evidence.
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd -P)"
HARNESS_REPO_ROOT="$ROOT"
# shellcheck source=scripts/lib/harness.sh
source "$ROOT/scripts/lib/harness.sh"

usage() {
  cat >&2 <<'EOF'
usage: scripts/evidence_run.sh --scenario <verify-feature> [options]

Options:
  --seed <integer>              requested world seed (default: 20260610)
  --timeout <seconds>           verify timeout after startup (default: 900)
  --startup-timeout <seconds>   server startup timeout (default: 480)
  --profile <name>              strict_survival (default) or operator
  --operator-capabilities <csv> operator-only enabled flags, or all/none
  --mode <name>                 deterministic (default) or llm_story
  --with-llm                    pass DEEPSEEK_API_KEY to the isolated server
  --fixture-log <file>          seal a synthetic log without starting Minecraft

The output root is fixed at artifacts/evidence. Existing evidence is never
overwritten. Fixture evidence and dirty-worktree evidence are UNVERIFIED.
EOF
}

SCENARIO=""
REQUESTED_SEED="20260610"
MAXWAIT=900
STARTUP_TIMEOUT=480
PROFILE="strict_survival"
CAPABILITY_SPEC=""
MODE="deterministic"
WITH_LLM=0
FIXTURE_LOG=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --scenario) [[ $# -ge 2 ]] || { usage; exit 2; }; SCENARIO="$2"; shift 2 ;;
    --seed) [[ $# -ge 2 ]] || { usage; exit 2; }; REQUESTED_SEED="$2"; shift 2 ;;
    --timeout) [[ $# -ge 2 ]] || { usage; exit 2; }; MAXWAIT="$2"; shift 2 ;;
    --startup-timeout) [[ $# -ge 2 ]] || { usage; exit 2; }; STARTUP_TIMEOUT="$2"; shift 2 ;;
    --profile) [[ $# -ge 2 ]] || { usage; exit 2; }; PROFILE="$2"; shift 2 ;;
    --operator-capabilities) [[ $# -ge 2 ]] || { usage; exit 2; }; CAPABILITY_SPEC="$2"; shift 2 ;;
    --mode) [[ $# -ge 2 ]] || { usage; exit 2; }; MODE="$2"; shift 2 ;;
    --with-llm) WITH_LLM=1; shift ;;
    --fixture-log) [[ $# -ge 2 ]] || { usage; exit 2; }; FIXTURE_LOG="$2"; shift 2 ;;
    -h|--help) usage; exit 0 ;;
    *) printf 'evidence-run: unknown argument: %s\n' "$1" >&2; usage; exit 2 ;;
  esac
done

harness_safe_id "$SCENARIO" scenario 96 || exit 2
harness_safe_seed "$REQUESTED_SEED" || exit 2
[[ "$MAXWAIT" =~ ^[1-9][0-9]*$ && "$STARTUP_TIMEOUT" =~ ^[1-9][0-9]*$ ]] || {
  printf 'evidence-run: timeouts must be positive integers\n' >&2
  exit 2
}
[[ "$MAXWAIT" -le 86400 && "$STARTUP_TIMEOUT" -le 1800 ]] || {
  printf 'evidence-run: timeout exceeds safety limit (verify=86400, startup=1800)\n' >&2
  exit 2
}
case "$PROFILE" in strict_survival|operator) ;; *) printf 'evidence-run: invalid profile: %s\n' "$PROFILE" >&2; exit 2 ;; esac
case "$MODE" in deterministic|llm_story) ;; *) printf 'evidence-run: invalid mode: %s\n' "$MODE" >&2; exit 2 ;; esac
if [[ $WITH_LLM -eq 1 && -z "${DEEPSEEK_API_KEY:-}" ]]; then
  printf 'evidence-run: --with-llm requires DEEPSEEK_API_KEY\n' >&2
  exit 2
fi
if [[ -n "$FIXTURE_LOG" ]]; then
  [[ -f "$FIXTURE_LOG" && ! -L "$FIXTURE_LOG" ]] || {
    printf 'evidence-run: fixture must be a regular non-symlink file\n' >&2
    exit 2
  }
  MODE="fixture"
fi
if [[ "$MODE" == fixture && $WITH_LLM -eq 1 ]]; then
  printf 'evidence-run: fixture mode cannot use --with-llm\n' >&2
  exit 2
fi
if [[ "$MODE" == llm_story && $WITH_LLM -ne 1 ]]; then
  printf 'evidence-run: llm_story mode requires --with-llm\n' >&2
  exit 2
fi
if [[ "$MODE" == deterministic && $WITH_LLM -eq 1 ]]; then
  printf 'evidence-run: --with-llm requires --mode llm_story\n' >&2
  exit 2
fi

HIDDEN_SCAN=false
EMERGENCY_TELEPORT=false
FORCED_PICKUP=false
MANUAL_TELEPORT=false
if [[ "$PROFILE" == operator ]]; then
  if [[ -z "$CAPABILITY_SPEC" || "$CAPABILITY_SPEC" == all ]]; then
    HIDDEN_SCAN=true
    EMERGENCY_TELEPORT=true
    FORCED_PICKUP=true
    MANUAL_TELEPORT=true
  elif [[ "$CAPABILITY_SPEC" != none ]]; then
    old_ifs="$IFS"
    IFS=','
    for capability in $CAPABILITY_SPEC; do
      case "$capability" in
        hiddenBlockScan) HIDDEN_SCAN=true ;;
        emergencyTeleport) EMERGENCY_TELEPORT=true ;;
        forcedPickup) FORCED_PICKUP=true ;;
        manualTeleport) MANUAL_TELEPORT=true ;;
        *) printf 'evidence-run: invalid operator capability: %s\n' "$capability" >&2; exit 2 ;;
      esac
    done
    IFS="$old_ifs"
  fi
elif [[ -n "$CAPABILITY_SPEC" && "$CAPABILITY_SPEC" != none ]]; then
  printf 'evidence-run: strict_survival cannot enable operator capabilities\n' >&2
  exit 2
fi

cd "$ROOT" || exit 1
if ! COMMIT_SHA="$(git rev-parse HEAD 2>/dev/null)"; then COMMIT_SHA=unknown; fi
if ! BRANCH="$(git branch --show-current 2>/dev/null)"; then BRANCH=unknown; fi
[[ -n "$BRANCH" ]] || BRANCH=detached
if ! GIT_STATUS_START="$(git status --porcelain=v1 --untracked-files=all 2>/dev/null)"; then
  WORKTREE_STATE=unknown
elif [[ -n "$GIT_STATUS_START" ]]; then
  WORKTREE_STATE=dirty
else
  WORKTREE_STATE=clean
fi
BUILD_VERSION="$(harness_gradle_property mod_version)"
MINECRAFT_VERSION="$(harness_gradle_property minecraft_version)"
LOADER_VERSION="$(harness_gradle_property loader_version)"
FABRIC_VERSION="$(harness_gradle_property fabric_version)"
[[ -n "$BUILD_VERSION" ]] || BUILD_VERSION=unknown
[[ -n "$MINECRAFT_VERSION" ]] || MINECRAFT_VERSION=unknown
[[ -n "$LOADER_VERSION" ]] || LOADER_VERSION=unknown
[[ -n "$FABRIC_VERSION" ]] || FABRIC_VERSION=unknown
STARTED_AT="$(harness_now_utc)"
RUN_ID="$(harness_run_id "$SCENARIO" "$COMMIT_SHA")"
TMP_BASE="$(cd "${TMPDIR:-/tmp}" && pwd -P)"
REPO_KEY="$(printf '%s' "$ROOT" | cksum | awk '{print $1}')"
LOCK_PATH="$TMP_BASE/aibot-evidence-$REPO_KEY.lock"

SERVER_PID=""
FIFO=""
RUN_DIR=""
SERVER_RUN_DIR=""
STAGING=""
FINAL=""
PUBLISHED=0
FD_OPEN=0

cleanup() {
  local status=$?
  trap - EXIT HUP INT TERM
  if [[ -n "$SERVER_PID" ]] && kill -0 "$SERVER_PID" 2>/dev/null; then
    if [[ $FD_OPEN -eq 1 ]]; then printf 'stop\n' >&9 2>/dev/null || true; fi
    if ! harness_wait_for_exit "$SERVER_PID" 12; then
      harness_kill_tree "$SERVER_PID" TERM
      harness_wait_for_exit "$SERVER_PID" 5 || harness_kill_tree "$SERVER_PID" KILL
    fi
  fi
  if [[ $FD_OPEN -eq 1 ]]; then exec 9>&- 9<&- || true; fi
  [[ -z "$FIFO" ]] || rm -f -- "$FIFO"
  if [[ -n "$SERVER_RUN_DIR" && -d "$SERVER_RUN_DIR" && ! -L "$SERVER_RUN_DIR" ]]; then
    case "$SERVER_RUN_DIR" in
      "$ROOT"/build/run/evidence-*) chmod -R u+w "$SERVER_RUN_DIR" 2>/dev/null || true; rm -rf -- "$SERVER_RUN_DIR" ;;
    esac
  fi
  if [[ -n "$RUN_DIR" && -d "$RUN_DIR" && ! -L "$RUN_DIR" ]]; then
    case "$RUN_DIR" in "$TMP_BASE"/aibot-evidence-runs/run.*) rm -rf -- "$RUN_DIR" ;; esac
  fi
  if [[ $PUBLISHED -eq 0 && -n "$STAGING" ]]; then
    harness_safe_remove_staging "$STAGING" "$HARNESS_ARTIFACT_ROOT" || true
  fi
  harness_release_lock || true
  exit "$status"
}
trap cleanup EXIT
trap 'exit 129' HUP
trap 'exit 130' INT
trap 'exit 143' TERM

harness_acquire_lock "$LOCK_PATH" || exit 3
harness_prepare_root "$HARNESS_ARTIFACT_ROOT" || exit 3
harness_assert_not_symlink "$ROOT/artifacts" 'artifacts directory' || exit 3

STAGING="$(mktemp -d "$HARNESS_ARTIFACT_ROOT/.staging.${RUN_ID}.XXXXXX")" || exit 3
FINAL="$HARNESS_ARTIFACT_ROOT/$RUN_ID"
: > "$STAGING/server.log" || exit 3

mkdir -p "$TMP_BASE/aibot-evidence-runs" || exit 3
harness_assert_not_symlink "$TMP_BASE/aibot-evidence-runs" 'temporary run root' || exit 3
RUN_DIR="$(mktemp -d "$TMP_BASE/aibot-evidence-runs/run.${RUN_ID}.XXXXXX")" || exit 3
FIFO="$RUN_DIR/server.fifo"
SERVER_PORT="$(harness_choose_port)" || exit 3
EXEC_ROOT="$ROOT"
SOURCE_SNAPSHOT=live_worktree
if [[ "$WORKTREE_STATE" == clean && "$COMMIT_SHA" != unknown ]]; then
  mkdir -p "$RUN_DIR/source" || exit 3
  if git archive "$COMMIT_SHA" | tar -x -C "$RUN_DIR/source"; then
    EXEC_ROOT="$RUN_DIR/source"
    SOURCE_SNAPSHOT=git_archive
  else
    SOURCE_SNAPSHOT=git_archive_failed_live_fallback
  fi
fi
RELATIVE_SERVER_RUN_DIR="build/run/evidence-$RUN_ID"
harness_assert_not_symlink "$EXEC_ROOT/build" build_directory || exit 3
mkdir -p "$EXEC_ROOT/build" || exit 3
harness_assert_not_symlink "$EXEC_ROOT/build" build_directory || exit 3
harness_assert_not_symlink "$EXEC_ROOT/build/run" build_run_directory || exit 3
mkdir -p "$EXEC_ROOT/build/run" || exit 3
harness_assert_not_symlink "$EXEC_ROOT/build/run" build_run_directory || exit 3
SERVER_RUN_DIR="$EXEC_ROOT/$RELATIVE_SERVER_RUN_DIR"
harness_assert_not_symlink "$SERVER_RUN_DIR" evidence_server_directory || exit 3
mkdir -p "$SERVER_RUN_DIR/config" || exit 3
harness_assert_not_symlink "$SERVER_RUN_DIR" evidence_server_directory || exit 3
printf 'eula=true\n' > "$SERVER_RUN_DIR/eula.txt" || exit 3
{
  printf 'online-mode=false\n'
  printf 'server-ip=127.0.0.1\n'
  printf 'server-port=%s\n' "$SERVER_PORT"
  printf 'query.port=%s\n' "$SERVER_PORT"
  printf 'enable-query=false\n'
  printf 'enable-rcon=false\n'
  printf 'enable-status=false\n'
  printf 'level-name=world\n'
  printf 'level-seed=%s\n' "$REQUESTED_SEED"
  printf 'gamemode=survival\n'
  printf 'difficulty=easy\n'
  printf 'spawn-protection=0\n'
  printf 'view-distance=10\n'
  printf 'simulation-distance=10\n'
  printf 'pause-when-empty-seconds=-1\n'
  printf 'max-tick-time=120000\n'
  printf 'motd=AIBot isolated evidence run\n'
} > "$SERVER_RUN_DIR/server.properties" || exit 3

# The runtime input contains no credential. A requested LLM key is supplied only
# through the child environment and is represented by a redaction marker below.
{
  printf '{\n'
  printf '  "profile": "%s",\n' "$PROFILE"
  printf '  "operatorCapabilities": {\n'
  printf '    "hiddenBlockScan": %s,\n' "$HIDDEN_SCAN"
  printf '    "emergencyTeleport": %s,\n' "$EMERGENCY_TELEPORT"
  printf '    "forcedPickup": %s,\n' "$FORCED_PICKUP"
  printf '    "manualTeleport": %s\n' "$MANUAL_TELEPORT"
  printf '  },\n'
  printf '  "deepseek": { "apiKey": "" }\n'
  printf '}\n'
} > "$SERVER_RUN_DIR/config/aibot.json" || exit 3

KEY_MARKER='<redacted:unset>'
[[ $WITH_LLM -eq 1 ]] && KEY_MARKER='<redacted:env>'
{
  printf '{\n'
  printf '  "schemaVersion": 1,\n'
  printf '  "profile": "%s",\n' "$PROFILE"
  printf '  "operatorCapabilities": {\n'
  printf '    "hiddenBlockScan": %s,\n' "$HIDDEN_SCAN"
  printf '    "emergencyTeleport": %s,\n' "$EMERGENCY_TELEPORT"
  printf '    "forcedPickup": %s,\n' "$FORCED_PICKUP"
  printf '    "manualTeleport": %s\n' "$MANUAL_TELEPORT"
  printf '  },\n'
  printf '  "deepseek": { "enabled": %s, "apiKey": "%s" },\n' "$([[ $WITH_LLM -eq 1 ]] && printf true || printf false)" "$KEY_MARKER"
  printf '  "server": { "onlineMode": false, "gamemode": "survival", "difficulty": "easy", "viewDistance": 10, "simulationDistance": 10 }\n'
  printf '}\n'
} > "$STAGING/effective-config.redacted.json" || exit 3
CONFIG_HASH="$(harness_sha256 "$STAGING/effective-config.redacted.json")" || exit 3

ACTUAL_SEED=unknown
ACTUAL_SEED_VERIFIED=no
GRADLE_EXIT=not_applicable
READY=no
WORKDIR_VERIFIED=no

if [[ -n "$FIXTURE_LOG" ]]; then
  cp "$FIXTURE_LOG" "$STAGING/server.log" || exit 3
else
  {
    printf '%s\n' 'gradle.projectsEvaluated {'
    printf '%s\n' '  rootProject.tasks.matching { it.name == "runHarnessServer" }.configureEach {'
    printf '%s\n' '    workingDir = new File(System.getenv("AIBOT_HARNESS_RUN_DIR"))'
    printf '%s\n' '    doFirst {'
    printf '%s\n' '      def expected = new File(System.getenv("AIBOT_HARNESS_RUN_DIR")).canonicalFile'
    printf '%s\n' '      def actual = getWorkingDir().canonicalFile'
    printf '%s\n' '      if (actual != expected) { throw new GradleException("AIBot evidence workingDir mismatch") }'
    printf '%s\n' '      println("AIBOT_EVIDENCE_WORKDIR=" + actual)'
    printf '%s\n' '      println("AIBOT_EVIDENCE_JAVA_HOME=" + System.getProperty("java.home"))'
    printf '%s\n' '      println("AIBOT_EVIDENCE_JAVA_VERSION=" + System.getProperty("java.version"))'
    printf '%s\n' '      println("AIBOT_EVIDENCE_JAVA_VENDOR=" + System.getProperty("java.vendor"))'
    printf '%s\n' '    }'
    printf '%s\n' '  }'
    printf '%s\n' '}'
  } > "$RUN_DIR/evidence.init.gradle" || exit 3
  mkfifo "$FIFO" || exit 3
  exec 9<> "$FIFO" || exit 3
  FD_OPEN=1

  if [[ $WITH_LLM -eq 1 ]]; then
    env AIBOT_PROFILE="$PROFILE" AIBOT_HARNESS_RUN_DIR="$SERVER_RUN_DIR" \
      "$EXEC_ROOT/gradlew" --no-daemon --console=plain --no-build-cache \
      -p "$EXEC_ROOT" -PaibotHarnessRunDir="$RELATIVE_SERVER_RUN_DIR" \
      -I "$RUN_DIR/evidence.init.gradle" runHarnessServer <&9 >> "$STAGING/server.log" 2>&1 &
  else
    env -u DEEPSEEK_API_KEY AIBOT_PROFILE="$PROFILE" AIBOT_HARNESS_RUN_DIR="$SERVER_RUN_DIR" \
      "$EXEC_ROOT/gradlew" --no-daemon --console=plain --no-build-cache \
      -p "$EXEC_ROOT" -PaibotHarnessRunDir="$RELATIVE_SERVER_RUN_DIR" \
      -I "$RUN_DIR/evidence.init.gradle" runHarnessServer <&9 >> "$STAGING/server.log" 2>&1 &
  fi
  SERVER_PID=$!

  for ((i = 0; i < STARTUP_TIMEOUT; i++)); do
    if grep -aq 'Done (' "$STAGING/server.log" 2>/dev/null; then READY=yes; break; fi
    kill -0 "$SERVER_PID" 2>/dev/null || break
    sleep 1
  done
  if grep -aFq "AIBOT_EVIDENCE_WORKDIR=$SERVER_RUN_DIR" "$STAGING/server.log" 2>/dev/null; then
    WORKDIR_VERIFIED=yes
  fi

  if [[ "$READY" == yes ]]; then
    printf 'seed\n' >&9 || READY=no
    for ((i = 0; i < 8; i++)); do
      ACTUAL_SEED="$(sed -nE 's/.*Seed: \[(-?[0-9]+)\].*/\1/p' "$STAGING/server.log" | tail -1)"
      [[ -n "$ACTUAL_SEED" ]] && break
      sleep 1
    done
    [[ -n "$ACTUAL_SEED" ]] || ACTUAL_SEED=unknown
    if [[ "$ACTUAL_SEED" == "$REQUESTED_SEED" ]]; then ACTUAL_SEED_VERIFIED=yes; fi

    printf 'aibot spawn EvidenceBot assistant\n' >&9 || READY=no
    sleep 5
    printf 'aibot verify %s\n' "$SCENARIO" >&9 || READY=no
    for ((i = 0; i < MAXWAIT; i++)); do
      grep -aqE '\[AIBot Verify\] summary' "$STAGING/server.log" 2>/dev/null && break
      kill -0 "$SERVER_PID" 2>/dev/null || break
      sleep 1
    done
  fi

  if kill -0 "$SERVER_PID" 2>/dev/null; then
    printf 'stop\n' >&9 2>/dev/null || true
    if ! harness_wait_for_exit "$SERVER_PID" 20; then
      harness_kill_tree "$SERVER_PID" TERM
      harness_wait_for_exit "$SERVER_PID" 5 || harness_kill_tree "$SERVER_PID" KILL
    fi
  fi
  if wait "$SERVER_PID" 2>/dev/null; then GRADLE_EXIT=0; else GRADLE_EXIT=$?; fi
  SERVER_PID=""
fi

if [[ "$MODE" == fixture ]]; then
  ACTUAL_SEED="$(sed -nE 's/.*Seed: \[(-?[0-9]+)\].*/\1/p' "$STAGING/server.log" | tail -1)"
  [[ -n "$ACTUAL_SEED" ]] || ACTUAL_SEED=unknown
  [[ "$ACTUAL_SEED" == "$REQUESTED_SEED" ]] && ACTUAL_SEED_VERIFIED=yes
fi
JAVA_HOME_ACTUAL="$(sed -n 's/^AIBOT_EVIDENCE_JAVA_HOME=//p' "$STAGING/server.log" | tail -1)"
JAVA_VERSION_ACTUAL="$(sed -n 's/^AIBOT_EVIDENCE_JAVA_VERSION=//p' "$STAGING/server.log" | tail -1)"
JAVA_VENDOR_ACTUAL="$(sed -n 's/^AIBOT_EVIDENCE_JAVA_VENDOR=//p' "$STAGING/server.log" | tail -1)"
JAVA_RUNTIME_VERIFIED=no
if [[ -n "$JAVA_HOME_ACTUAL" && -n "$JAVA_VERSION_ACTUAL" && -n "$JAVA_VENDOR_ACTUAL" ]]; then
  JAVA_RUNTIME_VERIFIED=yes
else
  JAVA_HOME_ACTUAL=unknown
  JAVA_VERSION_ACTUAL="$(java -version 2>&1 | head -1 | tr '\t\r\n' '   ')"
  JAVA_VENDOR_ACTUAL=unknown
fi

# Secrets are never part of a sealed log. Redaction is byte-for-byte and is
# explicitly recorded; the exact key is read from the existing environment,
# never passed on a command line or written to metadata.
LOG_SECRET_REDACTIONS="$(python3 - "$STAGING/server.log" <<'PY'
import os
import re
import sys

path = sys.argv[1]
with open(path, "rb") as handle:
    data = handle.read()
count = 0
secret = os.environ.get("DEEPSEEK_API_KEY", "").encode()
if secret:
    occurrences = data.count(secret)
    if occurrences:
        data = data.replace(secret, b"<redacted:deepseek_api_key>")
        count += occurrences
patterns = (
    re.compile(rb"(?i)(DEEPSEEK_API_KEY\s*[=:]\s*)([^\s]+)"),
    re.compile(rb"(?i)(api[_-]?key\s*[=:]\s*)([\"']?)(sk-[A-Za-z0-9_-]{8,})([\"']?)"),
    re.compile(rb"sk-[A-Za-z0-9_-]{12,}"),
)
data, replacements = patterns[0].subn(rb"\1<redacted:deepseek_api_key>", data)
count += replacements
data, replacements = patterns[1].subn(rb"\1\2<redacted:api_key>\4", data)
count += replacements
data, replacements = patterns[2].subn(b"<redacted:api_key>", data)
count += replacements
temporary = path + ".redacted"
with open(temporary, "wb") as handle:
    handle.write(data)
os.replace(temporary, path)
print(count)
PY
)" || exit 3
[[ "$LOG_SECRET_REDACTIONS" =~ ^[0-9]+$ ]] || exit 3
if [[ -n "${DEEPSEEK_API_KEY:-}" ]] && grep -aFq -- "$DEEPSEEK_API_KEY" "$STAGING/server.log"; then
  printf 'evidence-run: refusing to seal a log containing DEEPSEEK_API_KEY\n' >&2
  exit 3
fi
if grep -aEq 'sk-[A-Za-z0-9_-]{12,}|DEEPSEEK_API_KEY[[:space:]]*[=:][[:space:]]*[^<[:space:]]' "$STAGING/server.log"; then
  printf 'evidence-run: refusing to seal a log containing credential-like data\n' >&2
  exit 3
fi

if ! COMMIT_SHA_END="$(git rev-parse HEAD 2>/dev/null)"; then COMMIT_SHA_END=unknown; fi
if ! GIT_STATUS_END="$(git status --porcelain=v1 --untracked-files=all 2>/dev/null)"; then
  WORKTREE_STATE_END=unknown
elif [[ -n "$GIT_STATUS_END" ]]; then
  WORKTREE_STATE_END=dirty
else
  WORKTREE_STATE_END=clean
fi

SUMMARY="$(grep -aE '\[AIBot Verify\] summary' "$STAGING/server.log" 2>/dev/null | tail -1 || true)"
SUMMARY="$(harness_tsv_value "$SUMMARY")"
PASSED="$(printf '%s\n' "$SUMMARY" | sed -nE 's/.*summary ([0-9]+)\/([0-9]+) PASS.*/\1/p')"
TOTAL="$(printf '%s\n' "$SUMMARY" | sed -nE 's/.*summary ([0-9]+)\/([0-9]+) PASS.*/\2/p')"
if [[ -n "$PASSED" && -n "$TOTAL" && "$TOTAL" -gt 0 ]]; then
  if [[ "$PASSED" == "$TOTAL" ]]; then RUN_RESULT=PASS; SCRIPT_EXIT=0; else RUN_RESULT=FAIL; SCRIPT_EXIT=1; fi
else
  PASSED=0
  TOTAL=0
  RUN_RESULT=ERROR
  SCRIPT_EXIT=1
  [[ -n "$SUMMARY" ]] || SUMMARY='NO_SUMMARY'
fi

EVIDENCE_STATE=VERIFIED
VERIFY_REASON=complete
add_unverified_reason() {
  local reason="$1"
  if [[ "$EVIDENCE_STATE" == VERIFIED ]]; then VERIFY_REASON="$reason"; else VERIFY_REASON="$VERIFY_REASON,$reason"; fi
  EVIDENCE_STATE=UNVERIFIED
}
[[ "$WORKTREE_STATE" == clean ]] || add_unverified_reason worktree_not_clean_at_start
[[ "$WORKTREE_STATE_END" == clean ]] || add_unverified_reason worktree_not_clean_at_finish
[[ "$COMMIT_SHA" == "$COMMIT_SHA_END" && "$COMMIT_SHA" != unknown ]] || add_unverified_reason revision_changed_or_unavailable
[[ "$MODE" != fixture ]] || add_unverified_reason fixture_input
[[ "$READY" == yes || "$MODE" == fixture ]] || add_unverified_reason server_not_ready
[[ "$ACTUAL_SEED_VERIFIED" == yes ]] || add_unverified_reason actual_seed_unverified
[[ "$COMMIT_SHA" != unknown && "$BUILD_VERSION" != unknown ]] || add_unverified_reason revision_metadata_missing
[[ "$MODE" == fixture || "$GRADLE_EXIT" == 0 ]] || add_unverified_reason server_exit_nonzero
[[ "$MODE" == fixture || "$WORKDIR_VERIFIED" == yes ]] || add_unverified_reason isolated_workdir_unverified
[[ "$MODE" == fixture || "$JAVA_RUNTIME_VERIFIED" == yes ]] || add_unverified_reason java_runtime_unverified
[[ "$WORKTREE_STATE" != clean || "$SOURCE_SNAPSHOT" == git_archive ]] || add_unverified_reason source_snapshot_failed
[[ "$LOG_SECRET_REDACTIONS" == 0 ]] || add_unverified_reason server_log_secret_redacted

FINISHED_AT="$(harness_now_utc)"
CAPABILITIES="hiddenBlockScan=$HIDDEN_SCAN,emergencyTeleport=$EMERGENCY_TELEPORT,forcedPickup=$FORCED_PICKUP,manualTeleport=$MANUAL_TELEPORT"
OS_RUNTIME="$(uname -srm | tr '\t\r\n' '   ')"
{
  printf 'schema_version\t1\n'
  printf 'run_id\t%s\n' "$RUN_ID"
  printf 'evidence_state\t%s\n' "$EVIDENCE_STATE"
  printf 'verification_reason\t%s\n' "$VERIFY_REASON"
  printf 'commit_sha\t%s\n' "$COMMIT_SHA"
  printf 'finished_commit_sha\t%s\n' "$COMMIT_SHA_END"
  printf 'git_branch\t%s\n' "$(harness_tsv_value "$BRANCH")"
  printf 'working_tree_state\t%s\n' "$WORKTREE_STATE"
  printf 'working_tree_state_end\t%s\n' "$WORKTREE_STATE_END"
  printf 'build_version\t%s\n' "$BUILD_VERSION"
  printf 'timestamp_utc\t%s\n' "$STARTED_AT"
  printf 'finished_at_utc\t%s\n' "$FINISHED_AT"
  printf 'runtime\tMinecraft %s; Fabric Loader %s; Fabric API %s; Java %s (%s); %s\n' \
    "$MINECRAFT_VERSION" "$LOADER_VERSION" "$FABRIC_VERSION" \
    "$(harness_tsv_value "$JAVA_VERSION_ACTUAL")" "$(harness_tsv_value "$JAVA_VENDOR_ACTUAL")" "$OS_RUNTIME"
  printf 'java_home\t%s\n' "$(harness_tsv_value "$JAVA_HOME_ACTUAL")"
  printf 'java_runtime_verified\t%s\n' "$JAVA_RUNTIME_VERIFIED"
  printf 'scenario\t%s\n' "$SCENARIO"
  printf 'requested_seed\t%s\n' "$REQUESTED_SEED"
  printf 'actual_seed\t%s\n' "$ACTUAL_SEED"
  printf 'actual_seed_verified\t%s\n' "$ACTUAL_SEED_VERIFIED"
  printf 'mode\t%s\n' "$MODE"
  printf 'llm_enabled\t%s\n' "$([[ $WITH_LLM -eq 1 ]] && printf yes || printf no)"
  printf 'profile\t%s\n' "$PROFILE"
  printf 'operator_capabilities\t%s\n' "$CAPABILITIES"
  printf 'config_hash\t%s\n' "$CONFIG_HASH"
  printf 'config_sha256\t%s\n' "$CONFIG_HASH"
  printf 'server_port\t%s\n' "$SERVER_PORT"
  printf 'run_directory\tisolated_temporary\n'
  printf 'run_directory_verified\t%s\n' "$WORKDIR_VERIFIED"
  printf 'source_snapshot\t%s\n' "$SOURCE_SNAPSHOT"
  printf 'log_secret_redactions\t%s\n' "$LOG_SECRET_REDACTIONS"
  printf 'gradle_exit_code\t%s\n' "$GRADLE_EXIT"
  printf 'result\t%s\n' "$RUN_RESULT"
  printf 'passed\t%s\n' "$PASSED"
  printf 'total\t%s\n' "$TOTAL"
} > "$STAGING/manifest.tsv" || exit 3

{
  printf 'scenario\trequested_seed\tactual_seed\tresult\tpassed\ttotal\texit_code\tsummary\n'
  printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' \
    "$SCENARIO" "$REQUESTED_SEED" "$ACTUAL_SEED" "$RUN_RESULT" "$PASSED" "$TOTAL" "$SCRIPT_EXIT" "$SUMMARY"
} > "$STAGING/result.tsv" || exit 3

harness_write_checksums "$STAGING" || exit 3
harness_write_locked_marker "$STAGING" || exit 3
harness_verify_bundle_files "$STAGING" || exit 3
harness_publish_staging "$STAGING" "$FINAL" || exit 3
PUBLISHED=1
STAGING=""
if ! "$ROOT/scripts/evidence_validate.sh" "$FINAL" >/dev/null; then
  printf 'evidence-run: published bundle failed full validation\n' >&2
  printf 'EVIDENCE_DIR=%s\n' "$FINAL"
  exit 3
fi

printf 'evidence-run: %s (%s, %s)\n' "$RUN_RESULT" "$EVIDENCE_STATE" "$RUN_ID"
printf 'EVIDENCE_DIR=%s\n' "$FINAL"
exit "$SCRIPT_EXIT"
