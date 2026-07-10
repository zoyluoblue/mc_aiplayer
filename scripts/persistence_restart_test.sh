#!/usr/bin/env bash
# Two-JVM persistence probe: stage runtime state, stop cleanly, then restore from the same world.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd -P)"
HARNESS_REPO_ROOT="$ROOT"
# shellcheck source=scripts/lib/harness.sh
source "$ROOT/scripts/lib/harness.sh"

STAMP="$(date -u '+%Y%m%dT%H%M%SZ')-$$-$RANDOM"
RELATIVE_RUN_DIR="build/run/restart-$STAMP"
SERVER_RUN_DIR="$ROOT/$RELATIVE_RUN_DIR"
REPORT_DIR="$ROOT/build/persistence-restart/$STAMP"
PORT="$(harness_choose_port)"
TEMP_ROOT="$(mktemp -d "${TMPDIR:-/tmp}/aibot-restart.XXXXXX")"
FIFO=""
SERVER_PID=""
FD_OPEN=0
SUCCESS=0

cleanup() {
  local status=$?
  trap - EXIT HUP INT TERM
  if [[ -n "$SERVER_PID" && "$SERVER_PID" =~ ^[0-9]+$ ]] && kill -0 "$SERVER_PID" 2>/dev/null; then
    [[ $FD_OPEN -eq 0 ]] || printf 'stop\n' >&9 2>/dev/null || true
    harness_wait_for_exit "$SERVER_PID" 12 || harness_kill_tree "$SERVER_PID" TERM
  fi
  if [[ $FD_OPEN -eq 1 ]]; then exec 9>&- 9<&- || true; fi
  [[ -z "$FIFO" ]] || rm -f -- "$FIFO"
  [[ ! -d "$TEMP_ROOT" || -L "$TEMP_ROOT" ]] || rm -rf -- "$TEMP_ROOT"
  if [[ $SUCCESS -eq 1 && -d "$SERVER_RUN_DIR" && ! -L "$SERVER_RUN_DIR" ]]; then
    rm -rf -- "$SERVER_RUN_DIR"
  fi
  exit "$status"
}
trap cleanup EXIT
trap 'exit 129' HUP
trap 'exit 130' INT
trap 'exit 143' TERM

mkdir -p "$SERVER_RUN_DIR/config" "$REPORT_DIR"
printf 'eula=true\n' > "$SERVER_RUN_DIR/eula.txt"
{
  printf 'online-mode=false\nserver-ip=127.0.0.1\nserver-port=%s\n' "$PORT"
  printf 'enable-query=false\nenable-rcon=false\nenable-status=false\n'
  printf 'level-name=world\nlevel-seed=20260610\nspawn-protection=0\n'
  printf 'pause-when-empty-seconds=-1\nmax-tick-time=120000\n'
} > "$SERVER_RUN_DIR/server.properties"
{
  printf '{\n  "profile": "strict_survival",\n'
  printf '  "operatorCapabilities": { "hiddenBlockScan": false, "emergencyTeleport": false, "forcedPickup": false, "manualTeleport": false },\n'
  printf '  "deepseek": { "apiKey": "" }\n}\n'
} > "$SERVER_RUN_DIR/config/aibot.json"

start_server() {
  local log_file="$1" ready=0
  FIFO="$TEMP_ROOT/server.fifo"
  rm -f -- "$FIFO"
  mkfifo "$FIFO"
  exec 9<> "$FIFO"
  FD_OPEN=1
  env -u DEEPSEEK_API_KEY AIBOT_PROFILE=strict_survival AIBOT_TEST_PORT="$PORT" \
    "$ROOT/gradlew" --no-daemon --console=plain --no-build-cache \
    -p "$ROOT" -PaibotHarnessRunDir="$RELATIVE_RUN_DIR" runHarnessServer \
    <&9 >> "$log_file" 2>&1 &
  SERVER_PID=$!
  for ((i = 0; i < 480; i++)); do
    if grep -aFq 'Done (' "$log_file" 2>/dev/null; then ready=1; break; fi
    kill -0 "$SERVER_PID" 2>/dev/null || break
    sleep 1
  done
  [[ $ready -eq 1 ]] || {
    printf 'persistence-restart: server failed to become ready; see %s\n' "$log_file" >&2
    return 1
  }
}

wait_for_outcome() {
  local log_file="$1" pass_pattern="$2" fail_pattern="$3" seconds="$4"
  for ((i = 0; i < seconds; i++)); do
    grep -aFq "$pass_pattern" "$log_file" 2>/dev/null && return 0
    if grep -aFq "$fail_pattern" "$log_file" 2>/dev/null; then
      return 1
    fi
    kill -0 "$SERVER_PID" 2>/dev/null || return 1
    sleep 1
  done
  return 1
}

poll_command_until() {
  local log_file="$1" command="$2" pass_pattern="$3" fail_pattern="$4" attempts="$5"
  for ((i = 0; i < attempts; i++)); do
    printf '%s\n' "$command" >&9
    sleep 1
    grep -aFq "$pass_pattern" "$log_file" 2>/dev/null && return 0
    if grep -aFq "$fail_pattern" "$log_file" 2>/dev/null; then
      return 1
    fi
    kill -0 "$SERVER_PID" 2>/dev/null || return 1
  done
  return 1
}

stop_server() {
  printf 'stop\n' >&9
  harness_wait_for_exit "$SERVER_PID" 30 || {
    harness_kill_tree "$SERVER_PID" TERM
    harness_wait_for_exit "$SERVER_PID" 5 || return 1
  }
  wait "$SERVER_PID"
  SERVER_PID=""
  exec 9>&- 9<&-
  FD_OPEN=0
  rm -f -- "$FIFO"
  FIFO=""
}

phase1="$REPORT_DIR/phase1.log"
phase2="$REPORT_DIR/phase2.log"
: > "$phase1"
: > "$phase2"

start_server "$phase1"
printf 'aibot spawn RestartBob assistant\n' >&9
printf 'aibot harness restart-stage RestartBob\n' >&9
wait_for_outcome "$phase1" \
  '[AIBot Harness] restart-stage STARTED' \
  '[AIBot Harness] restart-stage FAIL' 120 || {
  printf 'persistence-restart: mission start failed; see %s\n' "$phase1" >&2
  exit 1
}
poll_command_until "$phase1" \
  'aibot harness restart-stage-check RestartBob' \
  '[AIBot Harness] restart-stage-check PASS' \
  '[AIBot Harness] restart-stage-check FAIL' 120 || {
  printf 'persistence-restart: non-default checkpoint stage failed; see %s\n' "$phase1" >&2
  exit 1
}
stop_server

start_server "$phase2"
printf 'aibot harness restart-check RestartBob\n' >&9
wait_for_outcome "$phase2" \
  '[AIBot Harness] persistence_restart RESTORE_PASS' \
  '[AIBot Harness] persistence_restart RESTORE_FAIL' 120 || {
  printf 'persistence-restart: exact checkpoint/lease restore failed; see %s\n' "$phase2" >&2
  exit 1
}
poll_command_until "$phase2" \
  'aibot harness restart-progress RestartBob' \
  '[AIBot Harness] restart-progress PASS' \
  '[AIBot Harness] restart-progress FAIL' 180 || {
  printf 'persistence-restart: resumed Mission made no verified progress; see %s\n' "$phase2" >&2
  exit 1
}
stop_server

{
  printf 'result\tPASS\n'
  printf 'profile\tstrict_survival\n'
  printf 'checkpoint\tnon_default_exact_restore\n'
  printf 'stale_lease\treopened\n'
  printf 'resume\tcompleted_with_postcondition\n'
  printf 'phase1\t%s\n' "$phase1"
  printf 'phase2\t%s\n' "$phase2"
} > "$REPORT_DIR/result.tsv"
SUCCESS=1
printf 'persistence-restart: PASS (%s)\n' "$REPORT_DIR"
