#!/usr/bin/env bash
#
# Run the device tests and collect the logs, while the emulator is still up.
#
# The emulator runner executes its `script` block one line at a time, so a shell variable set on
# one line is gone by the next. Everything that has to share state — the test exit code, the
# `set +e` — therefore lives in here, in a single process.
#
# The collection happens after the tests and before this script returns. It used to be a pair of
# workflow steps after the emulator action, by which point the device was already gone: `adb`
# answered "no devices/emulators found" and every artifact came out empty, which is why the first
# failures could not be read from CI.
#
# Usage: scripts/run-device-tests.sh <api-level> [extra gradle arguments...]
set -uo pipefail

API_LEVEL="${1:?an API level is needed for the artifact file names}"
shift

echo "== Running the device tests (API $API_LEVEL) =="
set +e
./gradlew connectedDebugAndroidTest --stacktrace \
  -Pandroid.testInstrumentationRunnerArguments.requireAccessibility=true \
  "$@"
tests_exit=$?
set -e

echo "== Collecting logs (test exit code was $tests_exit) =="
timeout 60 adb logcat -d > "logcat-api$API_LEVEL.txt" || true
timeout 60 adb shell dumpsys accessibility > "dumpsys-accessibility-api$API_LEVEL.txt" || true
mkdir -p "perception-api$API_LEVEL"
timeout 60 adb pull /sdcard/Android/data/com.phoneagent.debug/files/perception \
  "perception-api$API_LEVEL/" || true

# The eval writes its report to the app's files directory. Pulled here for the same reason as
# the logs: by the time the workflow's own steps run, the device is gone.
mkdir -p eval-out
timeout 60 adb pull /sdcard/Android/data/com.phoneagent.debug/files/eval-report.md eval-out/ || true

# The tests decide the job's result, not the collection above.
exit "$tests_exit"
