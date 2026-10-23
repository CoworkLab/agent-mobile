#!/usr/bin/env bash
#
# Run the device tests and collect the logs, while the emulator is still up.
#
# The tests are started with `am instrument` rather than `connectedDebugAndroidTest`, and that
# is the point of this script. The gradle task installs the APKs immediately before the tests,
# and that install kills the process holding the accessibility service and drops the component
# from the enabled list — so the service that device setup had just confirmed as bound was gone
# by the first test, and the tests that need it failed. There is no hook between that install and
# the first test to put it back. Installing here instead, in `prepare-device.sh`, leaves the
# service alone: nothing reinstalls between the enable and the tests, so it stays bound.
#
# The cost is gradle's test reporting, so the result is read from the instrumentation's own
# output, which is printed in full. `am instrument` is also what the gradle task ends up calling.
#
# The emulator runner executes its `script` block one line at a time, so a variable set on one
# line is gone by the next. Everything that has to share state lives in here, in one process.
#
# Usage: scripts/run-device-tests.sh <api-level> [extra `am instrument` arguments...]
set -uo pipefail

API_LEVEL="${1:?an API level is needed for the artifact file names}"
shift

# The test APK is installed against the app's application id, so the runner component is the
# test package's.
RUNNER="com.phoneagent.debug.test/com.phoneagent.testing.PhoneAgentTestRunner"
OUTPUT="instrument-api$API_LEVEL.txt"

echo "== Running the device tests (API $API_LEVEL) =="
set +e
# `-w` waits for the run to finish, `-r` prints a line per test. The accessibility requirement
# is passed as an argument so the tests fail, rather than skip, when the service is missing.
adb shell am instrument -w -r -e requireAccessibility true "$@" "$RUNNER" 2>&1 | tee "$OUTPUT"
set -e

echo "== Collecting logs =="
timeout 60 adb logcat -d > "logcat-api$API_LEVEL.txt" || true
timeout 60 adb shell dumpsys accessibility > "dumpsys-accessibility-api$API_LEVEL.txt" || true

mkdir -p "perception-api$API_LEVEL"
timeout 60 adb pull /sdcard/Android/data/com.phoneagent.debug/files/perception \
  "perception-api$API_LEVEL/" || true

# The eval writes its report to the app's files directory. Pulled here for the same reason as
# the logs: by the time the workflow's own steps run, the device is gone.
mkdir -p eval-out
timeout 60 adb pull /sdcard/Android/data/com.phoneagent.debug/files/eval-report.md eval-out/ || true

# `am instrument` reports through its output rather than its exit status, so the result is read
# from the summary line. A run that did not finish is a failure too: an unrecognised output must
# not read as success, which is the mistake that let nineteen skipped tests look green.
echo "== Result =="
if grep -q 'INSTRUMENTATION_FAILED\|INSTRUMENTATION_ABORTED\|FAILURES!!!' "$OUTPUT"; then
  echo "the device tests failed"
  exit 1
fi
if grep -qE '^OK \([0-9]+ test' "$OUTPUT"; then
  echo "the device tests passed"
  exit 0
fi
echo "the device tests did not report a result; treating that as a failure" >&2
exit 1
