#!/usr/bin/env bash
# Runs the Android instrumented tests against an already-booted emulator, and
# dumps the app's logcat if they fail.
#
# This lives in a file rather than inline in the workflow because
# android-emulator-runner feeds its `script:` input to `sh -c` one line at a
# time. Any multi-line shell construct — an `if`, a `||  { ... }` block — is
# split across separate shells and dies with "Syntax error: end of file
# unexpected". A single script invocation sidesteps that entirely.

set -uo pipefail

cd "$(dirname "$0")/../../android" || exit 1

./gradlew connectedDebugAndroidTest --stacktrace
status=$?

if [ "$status" -ne 0 ]; then
  echo ""
  echo "=================== logcat (OpenLink) ==================="
  # A runtime fault otherwise reaches CI as only a JUnit assertion message,
  # while the stack trace explaining it dies with the emulator.
  adb logcat -d -v threadtime \
    OpenLinkServer:V \
    MonitorService:V \
    TlsIdentity:V \
    AndroidRuntime:E \
    System.err:W \
    TestRunner:V \
    '*:S' 2>/dev/null | tail -250
  echo "========================================================="
fi

exit "$status"
