#!/usr/bin/env bash
set -euo pipefail
mkdir -p reports
adb install -r artifacts/debug/app-debug.apk
adb install -r artifacts/androidTest/debug/app-debug-androidTest.apk
adb logcat -c
set +e
adb shell am instrument -w -r -e class "$DK_TEST_FILTER" com.dkalarm.text.test/androidx.test.runner.AndroidJUnitRunner > reports/instrumentation.txt 2>&1
instrument_status=$?
cat reports/instrumentation.txt
adb logcat -d -s DK_OCR:I DK_WORD:I > reports/ocr-logcat.txt
adb shell am start -n com.dkalarm.text/com.dkalarm.app.MainActivity
sleep 3
adb exec-out screencap -p > reports/app-screen.png
set -e
# Android's am instrument can return exit 0 even when JUnit fails.
test "$instrument_status" -eq 0
rg -q "OK \\(${DK_EXPECTED_TESTS} tests?\\)" reports/instrumentation.txt
! rg -q 'FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed' reports/instrumentation.txt
