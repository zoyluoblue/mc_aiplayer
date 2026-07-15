#!/usr/bin/env bash
set -euo pipefail

if [[ -z "${JAVA_HOME:-}" ]]; then
  JAVA_HOME="$(/usr/libexec/java_home -v 21)"
fi
export JAVA_HOME

JAVA_PROCESSES_BEFORE="$(pgrep -f "java" || true)"
GRADLE_DAEMONS_BEFORE="$(pgrep -f "org.gradle.launcher.daemon.bootstrap.GradleDaemon" || true)"

cleanup_gradle_java() {
  while IFS= read -r pid; do
    [[ -z "$pid" ]] && continue
    if grep -qx "$pid" <<< "$JAVA_PROCESSES_BEFORE"; then
      continue
    fi
    command_line="$(ps -p "$pid" -o command= 2>/dev/null || true)"
    if [[ "$command_line" == *"org.gradle.launcher.daemon.bootstrap.GradleDaemon"* \
      || "$command_line" == *"GradleWorkerMain"* ]]; then
      kill -9 "$pid" || true
    fi
  done < <(pgrep -f "java" || true)

  while IFS= read -r pid; do
    [[ -z "$pid" ]] && continue
    if ! grep -qx "$pid" <<< "$GRADLE_DAEMONS_BEFORE"; then
      kill -9 "$pid" || true
    fi
  done < <(pgrep -f "org.gradle.launcher.daemon.bootstrap.GradleDaemon" || true)

  ps aux | grep java
}

trap cleanup_gradle_java EXIT

./gradlew compileJava compileClientJava --no-daemon \
  -Djava.net.useSystemProxies=false \
  -Dhttp.proxyHost= \
  -Dhttps.proxyHost= \
  -Dorg.gradle.internal.http.connectionTimeout=45000 \
  -Dorg.gradle.internal.http.socketTimeout=45000

sleep 2
