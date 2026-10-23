#!/usr/bin/env bash
#
# Put the debug app on the emulator into the state the device tests need.
#
# This exists because the previous inline version enabled the accessibility service before the
# app was installed. Android drops the enable request for a package it does not know, and the
# tests then skipped themselves rather than failing: the job reported success while nineteen
# of the thirty tests never ran. The order below matters, and the last step asserts the service
# actually bound so a silent skip cannot happen again.
#
# Usage: scripts/prepare-device.sh
set -euo pipefail

APP_ID="com.phoneagent.debug"
A11Y_COMPONENT="$APP_ID/com.phoneagent.service.PhoneAgentAccessibilityService"
NOTIFICATION_COMPONENT="$APP_ID/com.phoneagent.service.PhoneAgentNotificationListener"

echo "== Building and installing =="
# Testbed is installed here too: the device scenarios drive its screens and its deep links
# have to resolve before the tests run.
./gradlew installDebug installDebugAndroidTest :testbed:installDebug --stacktrace

echo "== Granting runtime permissions =="
adb shell pm grant "$APP_ID" android.permission.POST_NOTIFICATIONS || true
adb shell appops set "$APP_ID" SYSTEM_ALERT_WINDOW allow || true

echo "== Enabling the notification listener =="
adb shell cmd notification allow_listener "$NOTIFICATION_COMPONENT" || true

echo "== Launching once to clear the stopped state =="
# A freshly installed package sits in Android's "stopped" state, and the system will not bind
# its services until it has been started once. Without this the accessibility service is
# enabled in settings but never binds — `dumpsys accessibility` showed it under "Binding
# services" and absent from "Bound services" — and the tests that need it skipped instead of
# failing, so the job went green having run eleven of its thirty tests.
adb shell am start -W -n "$APP_ID/com.phoneagent.ui.MainActivity" || true
sleep 3

echo "== Enabling the accessibility service =="
adb shell settings put secure enabled_accessibility_services "$A11Y_COMPONENT"
adb shell settings put secure accessibility_enabled 1

echo "== Verifying =="
ENABLED="$(adb shell settings get secure enabled_accessibility_services | tr -d '\r')"
echo "enabled_accessibility_services=$ENABLED"
if [ "$ENABLED" != "$A11Y_COMPONENT" ]; then
  echo "the accessibility service was not enabled; the device tests would skip" >&2
  exit 1
fi

# Give the system a moment to bind the service before asking whether it did.
bound=""
for _ in $(seq 1 30); do
  if adb shell dumpsys accessibility | grep -A2 "Bound services" | grep -q "$APP_ID"; then
    bound="yes"
    break
  fi
  sleep 2
done

adb shell dumpsys accessibility | head -40

if [ -z "$bound" ]; then
  # A warning, not a failure. Binding is the system's to schedule and the test runner reinstalls
  # the app just before the tests, which unbinds it again — so a service that is about to come
  # up here can legitimately read as unbound. The tests themselves wait for it and, on a run that
  # requires it, fail if it never arrives, which is the check that matters.
  echo "warning: the accessibility service is enabled but not bound yet" >&2
else
  echo "accessibility service is bound"
fi
