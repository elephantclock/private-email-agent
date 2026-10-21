# PEA: Private Email Agent

Read `docs/DEV_PLAN.md` § 0 and the Status table at the start of every session.

- Gradle needs JDK 21. Locally: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"`.
- adb isn't on PATH: `~/Library/Android/sdk/platform-tools/adb`. Always pass `-s <serial>`.
- Full check before committing: `./gradlew spotlessApply spotlessCheck`, then `./gradlew lint unitTest :app:verifyRoborazziDebug assembleDebug :app:assembleRelease`. Re-record screenshots after an intended UI change with `./gradlew :app:recordRoborazziDebug` and look at the PNGs before committing.
