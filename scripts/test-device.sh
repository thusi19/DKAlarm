#!/usr/bin/env bash
set +e
gradle --no-daemon connectedDebugAndroidTest
result=$?
mkdir -p app/build/reports
adb logcat -d -s DK_OCR:I DK_WORD:I > app/build/reports/ocr-logcat.txt
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.dkalarm.text/com.dkalarm.app.MainActivity
sleep 3
adb exec-out screencap -p > app/build/reports/app-screen.png
exit "$result"
