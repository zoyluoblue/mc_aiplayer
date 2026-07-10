#!/usr/bin/env bash
# Shared, portable primitives for the immutable evidence harness.
# This file is sourced by scripts in ../; it does not execute a run by itself.

if [[ -z "${HARNESS_REPO_ROOT:-}" ]]; then
  HARNESS_REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd -P)"
fi

HARNESS_ARTIFACT_ROOT="$HARNESS_REPO_ROOT/artifacts/evidence"
HARNESS_BATCH_ROOT="$HARNESS_REPO_ROOT/artifacts/evidence-batches"
HARNESS_BASELINE_ROOT="$HARNESS_REPO_ROOT/reports/baselines"
HARNESS_LOCK_DIR=""
HARNESS_LOCK_TOKEN=""

harness_die() {
  printf 'evidence: %s\n' "$*" >&2
  return 1
}

harness_now_utc() {
  date -u '+%Y-%m-%dT%H:%M:%SZ'
}

harness_sha256() {
  local file="$1"
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$file" | awk '{print $1}'
  elif command -v shasum >/dev/null 2>&1; then
    shasum -a 256 "$file" | awk '{print $1}'
  else
    harness_die 'no SHA-256 implementation found (sha256sum or shasum required)'
  fi
}

harness_tsv_value() {
  local value="${1:-}"
  value="${value//$'\t'/ }"
  value="${value//$'\r'/ }"
  value="${value//$'\n'/ }"
  printf '%s' "$value"
}

harness_safe_id() {
  local value="${1:-}" label="${2:-identifier}" max="${3:-80}"
  if [[ -z "$value" || ${#value} -gt $max || "$value" == *'..'* || ! "$value" =~ ^[A-Za-z0-9][A-Za-z0-9_.+-]*$ ]]; then
    harness_die "$label contains unsafe characters: $value"
    return 1
  fi
}

harness_safe_seed() {
  local value="${1:-}"
  if [[ ! "$value" =~ ^-?[0-9]+$ ]]; then
    harness_die "seed must be a base-10 integer: $value"
    return 1
  fi
}

harness_assert_not_symlink() {
  local path="$1" label="${2:-path}"
  if [[ -L "$path" ]]; then
    harness_die "$label must not be a symlink: $path"
    return 1
  fi
}

harness_prepare_root() {
  local root="$1" parent
  parent="$(dirname "$root")"
  harness_assert_not_symlink "$parent" 'parent directory' || return 1
  mkdir -p "$parent" || return 1
  harness_assert_not_symlink "$parent" 'parent directory' || return 1
  harness_assert_not_symlink "$root" 'output root' || return 1
  mkdir -p "$root" || return 1
  harness_assert_not_symlink "$root" 'output root' || return 1
}

harness_real_dir() {
  local path="$1"
  (cd "$path" 2>/dev/null && pwd -P)
}

harness_is_within() {
  local path="$1" root="$2" canonical_path canonical_root
  canonical_path="$(harness_real_dir "$path")" || return 1
  canonical_root="$(harness_real_dir "$root")" || return 1
  case "$canonical_path/" in
    "$canonical_root/"*) return 0 ;;
    *) return 1 ;;
  esac
}

harness_assert_no_tree_symlinks() {
  local path="$1" found
  if ! found="$(find "$path" -type l -print -quit 2>/dev/null)"; then
    harness_die "could not inspect evidence tree for symlinks: $path"
    return 1
  fi
  if [[ -n "$found" ]]; then
    harness_die "symlink found in sealed tree: $found"
    return 1
  fi
}

harness_choose_port() {
  if ! command -v python3 >/dev/null 2>&1; then
    harness_die 'python3 is required to allocate an isolated dynamic port'
    return 1
  fi
  python3 - <<'PY'
import socket
with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as sock:
    sock.bind(("127.0.0.1", 0))
    print(sock.getsockname()[1])
PY
}

harness_run_id() {
  local scenario="$1" revision="${2:-unknown}" stamp short
  stamp="$(date -u '+%Y%m%dT%H%M%SZ')"
  short="${revision:0:8}"
  printf '%s-%s-%s-%s%05d' "$stamp" "$scenario" "$short" "$$" "$RANDOM"
}

harness_acquire_lock() {
  local lock="$1" attempts=0 owner stale parent token age
  parent="$(dirname "$lock")"
  harness_prepare_root "$parent" || return 1
  harness_assert_not_symlink "$lock" 'lock path' || return 1
  token="$$.$RANDOM.$(date +%s)"

  while (( attempts < 8 )); do
    if mkdir "$lock" 2>/dev/null; then
      if ! {
        printf '%s\n' "$$" > "$lock/pid" &&
        printf '%s\n' "$token" > "$lock/token" &&
        printf '%s\n' "$(harness_now_utc)" > "$lock/started_at_utc" &&
        printf '%s\n' "$HARNESS_REPO_ROOT" > "$lock/repository"
      }; then
        rm -f -- "$lock/pid" "$lock/token" "$lock/started_at_utc" "$lock/repository"
        rmdir "$lock" 2>/dev/null || true
        harness_die "could not initialize lock metadata: $lock"
        return 1
      fi
      HARNESS_LOCK_DIR="$lock"
      HARNESS_LOCK_TOKEN="$token"
      return 0
    fi

    harness_assert_not_symlink "$lock" 'lock path' || return 1
    [[ -d "$lock" ]] || {
      harness_die "lock path exists and is not a directory: $lock"
      return 1
    }
    owner="$(sed -n '1p' "$lock/pid" 2>/dev/null || true)"
    # Never steal a freshly created, partially initialized lock. A process can
    # be paused between mkdir and metadata writes; only an old ownerless lock
    # is stale. Numeric dead owners can be recovered immediately.
    if [[ ! "$owner" =~ ^[0-9]+$ ]]; then
      age="$(python3 - "$lock" <<'PY'
import os
import sys
import time
print(max(0, int(time.time() - os.lstat(sys.argv[1]).st_mtime)))
PY
)" || return 1
      if [[ ! "$age" =~ ^[0-9]+$ || "$age" -lt 300 ]]; then
        harness_die "lock is initializing or has no recoverable owner: $lock"
        return 1
      fi
    fi
    if [[ "$owner" =~ ^[0-9]+$ ]] && kill -0 "$owner" 2>/dev/null; then
      harness_die "another evidence run owns lock $lock (pid=$owner)"
      return 1
    fi

    stale="$lock.stale.$$.$RANDOM"
    if mv "$lock" "$stale" 2>/dev/null; then
      # The path is generated next to the expected lock. rm does not follow
      # symlinks found inside the stale directory.
      rm -rf -- "$stale"
    fi
    attempts=$((attempts + 1))
  done

  harness_die "could not acquire lock after stale-lock recovery: $lock"
}

harness_release_lock() {
  local token
  [[ -n "${HARNESS_LOCK_DIR:-}" ]] || return 0
  harness_assert_not_symlink "$HARNESS_LOCK_DIR" 'lock path' || return 1
  token="$(sed -n '1p' "$HARNESS_LOCK_DIR/token" 2>/dev/null || true)"
  if [[ "$token" != "$HARNESS_LOCK_TOKEN" ]]; then
    harness_die "refusing to release a lock owned by another process: $HARNESS_LOCK_DIR"
    return 1
  fi
  rm -f -- "$HARNESS_LOCK_DIR/pid" "$HARNESS_LOCK_DIR/token" \
    "$HARNESS_LOCK_DIR/started_at_utc" "$HARNESS_LOCK_DIR/repository"
  rmdir "$HARNESS_LOCK_DIR" 2>/dev/null || true
  HARNESS_LOCK_DIR=""
  HARNESS_LOCK_TOKEN=""
}

harness_wait_for_exit() {
  local pid="$1" seconds="${2:-20}" i
  for ((i = 0; i < seconds; i++)); do
    kill -0 "$pid" 2>/dev/null || return 0
    sleep 1
  done
  return 1
}

harness_kill_tree() {
  local pid="$1" signal="${2:-TERM}" child children
  [[ "$pid" =~ ^[0-9]+$ ]] || return 0
  if command -v pgrep >/dev/null 2>&1; then
    children="$(pgrep -P "$pid" 2>/dev/null || true)"
  else
    children="$(ps -eo pid=,ppid= 2>/dev/null | awk -v parent="$pid" '$2 == parent { print $1 }')"
  fi
  for child in $children; do
    harness_kill_tree "$child" "$signal"
  done
  kill -"$signal" "$pid" 2>/dev/null || true
}

harness_safe_remove_staging() {
  local path="${1:-}" root="${2:-}" parent basename canonical_root
  [[ -n "$path" && -n "$root" ]] || return 0
  basename="$(basename "$path")"
  [[ "$basename" == .staging.* && "$basename" != *'..'* && "$basename" != */* ]] || {
    harness_die "refusing to remove non-staging path: $path"
    return 1
  }
  parent="$(cd "$(dirname "$path")" 2>/dev/null && pwd -P)" || return 1
  canonical_root="$(harness_real_dir "$root")" || return 1
  [[ "$parent" == "$canonical_root" ]] || {
    harness_die "staging path parent is outside its root: $path"
    return 1
  }
  if [[ -L "$path" ]]; then
    rm -f -- "$path"
  elif [[ -d "$path" ]]; then
    chmod -R u+w "$path" 2>/dev/null || true
    rm -rf -- "$path"
  fi
}

harness_manifest_get() {
  local file="$1" key="$2"
  awk -F '\t' -v wanted="$key" '$1 == wanted { print substr($0, index($0, "\t") + 1); exit }' "$file"
}

harness_write_checksums() {
  local dir="$1" output file hash
  output="$dir/checksums.sha256"
  : > "$output" || return 1
  for file in effective-config.redacted.json manifest.tsv result.tsv server.log; do
    hash="$(harness_sha256 "$dir/$file")" || return 1
    printf '%s  %s\n' "$hash" "$file" >> "$output" || return 1
  done
}

harness_write_locked_marker() {
  local dir="$1" checksum_hash
  checksum_hash="$(harness_sha256 "$dir/checksums.sha256")" || return 1
  {
    printf 'schema_version\t1\n'
    printf 'sealed_at_utc\t%s\n' "$(harness_now_utc)"
    printf 'checksums_sha256\t%s\n' "$checksum_hash"
  } > "$dir/LOCKED"
}

harness_verify_locked_marker() {
  local file="$1" schema sealed recorded calculated
  if ! awk -F '\t' '
      NF != 2 || $1 == "" || $2 == "" || seen[$1]++ { exit 2 }
      $1 != "schema_version" && $1 != "sealed_at_utc" && $1 != "checksums_sha256" { exit 3 }
      END { if (NR != 3) exit 4 }
    ' "$file"; then
    harness_die 'LOCKED marker must contain exactly three unique schema fields'
    return 1
  fi
  schema="$(harness_manifest_get "$file" schema_version)"
  sealed="$(harness_manifest_get "$file" sealed_at_utc)"
  recorded="$(harness_manifest_get "$file" checksums_sha256)"
  [[ "$schema" == 1 ]] || { harness_die 'unsupported LOCKED schema'; return 1; }
  [[ "$sealed" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z$ ]] || {
    harness_die 'invalid LOCKED timestamp'
    return 1
  }
  [[ ${#recorded} -eq 64 && "$recorded" != *[!0-9a-f]* ]] || {
    harness_die 'invalid LOCKED checksum hash'
    return 1
  }
  calculated="$(harness_sha256 "$(dirname "$file")/checksums.sha256")" || return 1
  [[ "$calculated" == "$recorded" ]] || {
    harness_die 'LOCKED marker does not match checksums.sha256'
    return 1
  }
}

harness_verify_bundle_files() {
  local dir="$1" expected actual file line hash name calculated
  [[ -d "$dir" && ! -L "$dir" ]] || {
    harness_die "evidence bundle is missing or is a symlink: $dir"
    return 1
  }
  harness_assert_no_tree_symlinks "$dir" || return 1

  expected=$'LOCKED\nchecksums.sha256\neffective-config.redacted.json\nmanifest.tsv\nresult.tsv\nserver.log'
  if ! actual="$(find "$dir" -mindepth 1 -maxdepth 1 -print | sed 's#^.*/##' | LC_ALL=C sort)"; then
    harness_die "could not enumerate evidence bundle: $dir"
    return 1
  fi
  if [[ "$actual" != "$expected" ]]; then
    harness_die "evidence bundle has missing or unexpected entries: $dir"
    return 1
  fi
  for file in LOCKED checksums.sha256 effective-config.redacted.json manifest.tsv result.tsv server.log; do
    [[ -f "$dir/$file" && ! -L "$dir/$file" ]] || {
      harness_die "required evidence file is not a regular file: $file"
      return 1
    }
  done

  expected=$'effective-config.redacted.json\nmanifest.tsv\nresult.tsv\nserver.log'
  actual=""
  while IFS= read -r line || [[ -n "$line" ]]; do
    hash="${line%%  *}"
    name="${line#*  }"
    if [[ "$line" == "$hash" || ${#hash} -ne 64 || "$hash" == *[!0-9a-f]* || \
          ! "$name" =~ ^[A-Za-z0-9][A-Za-z0-9._-]*$ || "$name" == *'..'* ]]; then
      harness_die "malformed checksum entry: $line"
      return 1
    fi
    case "$name" in
      effective-config.redacted.json|manifest.tsv|result.tsv|server.log) ;;
      *) harness_die "checksum entry escapes the evidence schema: $name"; return 1 ;;
    esac
    calculated="$(harness_sha256 "$dir/$name")" || return 1
    [[ "$calculated" == "$hash" ]] || {
      harness_die "checksum mismatch: $name"
      return 1
    }
    actual="${actual}${actual:+$'\n'}$name"
  done < "$dir/checksums.sha256"
  actual="$(printf '%s\n' "$actual" | LC_ALL=C sort)"
  [[ "$actual" == "$expected" ]] || {
    harness_die 'checksum list is incomplete or contains duplicate entries'
    return 1
  }

  harness_verify_locked_marker "$dir/LOCKED" || return 1
}

harness_publish_staging() {
  local staging="$1" final="$2" staging_identity nested
  [[ -d "$staging" && ! -L "$staging" ]] || {
    harness_die "staging directory is missing or unsafe: $staging"
    return 1
  }
  if [[ -e "$final" || -L "$final" ]]; then
    harness_die "refusing to overwrite existing evidence: $final"
    return 1
  fi
  harness_assert_no_tree_symlinks "$staging" || return 1
  chmod 0444 "$staging"/* || return 1
  python3 - "$staging" <<'PY' || return 1
import os
import sys

root = sys.argv[1]
for name in sorted(os.listdir(root)):
    path = os.path.join(root, name)
    if not os.path.isfile(path) or os.path.islink(path):
        raise SystemExit("non-regular evidence entry")
    descriptor = os.open(path, os.O_RDONLY)
    try:
        os.fsync(descriptor)
    finally:
        os.close(descriptor)
try:
    descriptor = os.open(root, os.O_RDONLY)
    try:
        os.fsync(descriptor)
    finally:
        os.close(descriptor)
except OSError:
    pass
PY
  staging_identity="$(python3 - "$staging" <<'PY'
import os
import sys
value = os.lstat(sys.argv[1])
print(f"{value.st_dev}:{value.st_ino}")
PY
)" || return 1
  mv -n "$staging" "$final" || return 1
  if [[ -e "$staging" || ! -f "$final/LOCKED" ]] || ! python3 - "$final" "$staging_identity" <<'PY'
import os
import sys
value = os.lstat(sys.argv[1])
raise SystemExit(0 if f"{value.st_dev}:{value.st_ino}" == sys.argv[2] else 1)
PY
  then
    nested="$final/$(basename "$staging")"
    if [[ -d "$nested" && ! -L "$nested" ]] && python3 - "$nested" "$staging_identity" <<'PY'
import os
import sys
value = os.lstat(sys.argv[1])
raise SystemExit(0 if f"{value.st_dev}:{value.st_ino}" == sys.argv[2] else 1)
PY
    then
      chmod -R u+w "$nested" 2>/dev/null || true
      rm -rf -- "$nested"
    fi
    harness_die "atomic publish lost a destination race: $final"
    return 1
  fi
  if ! chmod 0555 "$final"; then
    # The inode check above proves this is the directory we just moved, so it
    # is safe to roll back a publication whose read-only transition failed.
    chmod -R u+w "$final" 2>/dev/null || true
    rm -rf -- "$final"
    harness_die "could not make published evidence read-only: $final"
    return 1
  fi
  python3 - "$final" "$(dirname "$final")" <<'PY' || return 1
import os
import sys
for path in sys.argv[1:]:
    try:
        descriptor = os.open(path, os.O_RDONLY)
        try:
            os.fsync(descriptor)
        finally:
            os.close(descriptor)
    except OSError:
        pass
PY
}

harness_gradle_property() {
  local key="$1"
  awk -F '=' -v wanted="$key" '$1 == wanted { print substr($0, index($0, "=") + 1); exit }' \
    "$HARNESS_REPO_ROOT/gradle.properties"
}

harness_atomic_replace_file() {
  local source="$1" destination="$2"
  command -v python3 >/dev/null 2>&1 || {
    harness_die 'python3 is required for atomic file replacement'
    return 1
  }
  python3 - "$source" "$destination" <<'PY'
import os
import stat
import sys

source, destination = sys.argv[1:]
source_stat = os.lstat(source)
if not stat.S_ISREG(source_stat.st_mode):
    raise SystemExit("source is not a regular file")
descriptor = os.open(source, os.O_RDONLY)
try:
    os.fsync(descriptor)
finally:
    os.close(descriptor)
parent = os.path.dirname(destination)
if stat.S_ISLNK(os.lstat(parent).st_mode):
    raise SystemExit("destination parent is a symlink")
try:
    destination_stat = os.lstat(destination)
except FileNotFoundError:
    pass
else:
    if not stat.S_ISREG(destination_stat.st_mode):
        raise SystemExit("destination is not a regular file")
os.replace(source, destination)
try:
    descriptor = os.open(parent, os.O_RDONLY)
    try:
        os.fsync(descriptor)
    finally:
        os.close(descriptor)
except OSError:
    pass
PY
}
