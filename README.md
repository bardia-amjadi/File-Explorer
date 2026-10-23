# File Explorer

A Windows 11 / Fluent-inspired file manager for Android 12+ (minSdk 31), written in Kotlin with Jetpack Compose (MVVM, coroutines/Flow). Package `com.bardia.fileexplorer`. No ads, accounts, cloud or telemetry.

> **Status:** this source tree was written without access to the Android SDK or Gradle/Maven hosts, so it has **not been compiled or run**. Only a Kotlin *syntax* check was possible. Expect a few compile fixes on the first build, and verify the features marked below on a real device.

## Build

Requirements: JDK 17, Android SDK (platform 35).

```
./gradlew testDebugUnitTest      # unit tests
./gradlew assembleDebug          # app/build/outputs/apk/debug/
./gradlew assembleRelease        # unsigned unless signing variables are set
```

### GitHub Actions
- **Build debug APK** (`.github/workflows/debug.yml`): runs on every push/PR and manually (Actions tab > Run workflow). Runs unit tests, builds the debug APK and uploads it as the artifact **File-Explorer-debug-apk** (open the finished run, scroll to *Artifacts*).
- **Build release APK** (`.github/workflows/release.yml`): manual, or on tags `v*`. Without signing secrets it uploads **File-Explorer-release-apk-UNSIGNED** (not installable until signed). With secrets `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD` it signs the APK and uploads **File-Explorer-release-apk**. Pushing a `v*` tag also creates a GitHub Release with the APK.

Keystores and passwords are never stored in the repository (`*.jks`/`*.keystore` are git-ignored). To sign locally set `KEYSTORE_FILE`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD` before `assembleRelease`.

## Storage model and limitations
The app uses scoped storage and never requests all-files access. You grant folders or drives through Android's folder picker; grants are persisted. Android 11+ does not allow granting the storage root, the Download folder itself or `Android/data`/`Android/obb`. Media categories use MediaStore with the READ_MEDIA_* permissions. Some providers (cloud drives, some USB devices) lack rename/move/delete support; the UI hides or explains those actions. Trash is app-managed (`filesDir/trash`) with configurable retention. APK installs go through Android's installer.

## Layout
`app/` shell and navigation, `core/` models and pure logic, `storage/` SAF/MediaStore/walker, `operations/` copy/move/delete queue, trash and undo, `archive/` ZIP with Zip Slip protection, `search/`, `tools/` (hashing, sizes, compare, duplicates, APK), `design/` Fluent theme and components, `ui/` screens. Unit tests are in `app/src/test`.
