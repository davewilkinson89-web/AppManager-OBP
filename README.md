# AppManager OBP — Personal Android Utility (M1 source)

A private, locally operated Android app for installing and updating APKs, with a safety-first uninstall/reinstall fallback, local logs, optional automatic failure-log export to a user-selected folder and PNG app-icon export.

**Status:** Source build prepared. Android SDK and Gradle are unavailable in this authoring environment; an actual APK build, device acceptance and GitHub Actions results are **not** claimed. The included manual GitHub workflow provides build, JUnit and lint checks once this project is pushed to the repository.

## Functional scope

- **APKs**: Choose a user-accessible installer directory using Android's Storage Access Framework (SAF). Add APKs via Android's file picker or Share/Open With, inspect package label/version, install or update through Android `PackageInstaller`. A selected folder is polled approximately every 30 seconds while the APKs screen is visible.
- **Apps**: Browse recognised installed OBP apps in a grid displaying the actual system app icons; tap to launch, long-press for Android App Info. Optionally pin an **OBP Apps** home-screen shortcut, with launcher confirmation. Android does not permit third-party apps to silently create or populate a native launcher folder.
- **Icons**: Choose an icon-export directory (such as `Pictures/OBP Icons`); export existing installed OBP app icons as PNGs. After a verified installation, the current app icon is saved to that directory, best effort. Names are based on package ID and an existing PNG is overwritten when the app changes.
- **Install safety**: `PackageInstaller` result callback, SHA-256 of streamed source, installed package/version verification; source APK deleted **only** after OS success and version check. Retain source after failed/cancelled installation. Signing/downgrade conflicts offer explicit **user-confirmed** uninstall then reinstall (loss of app data possible). Tracks interrupted reinstall for recovery.
- **Logs**: Persist up to 250 attempts on-device. Copy/share individual logs or whole history. Failed installations queued for automatic plaintext export to a separate user-selected SAF folder. Google Drive export works **only if** the user's installed Drive DocumentsProvider exposes a writable folder via Android's picker; this is **not** a Drive API OAuth integration or guaranteed upload to Drive.

## How to build

1. Add the source files to the `AppManager-OBP` GitHub repository, preserving the `.github/workflows/manual-android.yml` folder.
2. From GitHub **Actions → AppManager OBP - Manual Android Validation → Run workflow**, choose branch `feature/m1-secure-apk-manager`. The job deliberately skips `main` because `main` contains no Android application yet. The workflow uses `workflow_dispatch` only.
3. On success, download the `AppManager-OBP-M1-debug-apk` workflow artifact containing `app-debug.apk`.
4. Sideload the APK onto the Android device; allow installation from this source when Android prompts.
5. In the app, select `Downloads/OBP Installers` (create the folder in Files first if necessary). Optionally select a Logs folder and `Pictures/OBP Icons`.
6. Test an APK install/update, a cancelled install, a deliberate mismatched signing test on a disposable test app, a successful cleanup and log export. Do **not** use an app containing irreplaceable data for uninstall tests.

Alternatively, Android Studio with JDK 17 and Android SDK 35 can import the folder and build `:app:assembleDebug` locally.

## Operational limits

- The system may require permission and user confirmation to install or uninstall APKs. APKs **cannot be installed into a chosen folder** on a normal Android phone; Android controls application storage. The dedicated folder is for **APK files**, not the actual installed app binaries.
- Automatic file watching is limited to periodic foreground scanning; no permanent background service is installed. Android launchers own their home-screen folders.
- Icons can be extracted from **installed apps**, not archived from APKs that were never installed.
- The APK install flow uses a *single base APK*. AABs and split-APK bundles (`.apks`, `.xapk`, `.apkm`) are not supported by M1.
- A failed icon export does not block an otherwise successful APK installation; re-export in the Apps tab.
- This tool is intended for personal internal testing; broad package visibility and install/delete permissions are inappropriate for Play Store release without a separate policy review.

## Project recovery

See `RESUME.md` for file inventory, next actions and acceptance checks. Public source is acceptable if no credentials, device diagnostics, private APKs, or signing keys are included. Do not merge into main before GitHub build, lint and device checks pass.

## Security checks (2026-11-06)

- Public source audit found no hard-coded credential material or private account email in the M1 ZIP. This is a static inspection, **not** proof the build is vulnerability-free.
- `INTERNET` permission is absent. Google Drive diagnostics are optional exports via user-approved Android DocumentsProvider, **not** API credential uploads.
- External shares require confirmation before copying an APK; exported MainActivity does not forward third-party-provided approval intents.
- After successful installation the source file is deleted only when its SHA-256 still matches the streamed installer; changed/vanished files are retained/flagged.
- Build workflow has `contents: read` and `workflow_dispatch` only. Never commit APKs, tokens, keystores, `local.properties`, exported logs, or private icons; use a **private** folder for diagnostics.
- `QUERY_ALL_PACKAGES`, `REQUEST_INSTALL_PACKAGES` and `REQUEST_DELETE_PACKAGES` are sensitive by design. Review privacy and user approval if distribution is ever expanded.
- No Android compile or on-device security verification has yet been completed.

- The app cannot offer an automatic uninstall/reinstall of its own package; update AppManager through Android instead.
