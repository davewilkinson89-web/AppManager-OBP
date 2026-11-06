# AppManager OBP — Resume M1 (2026-11-06)

## Recovery point

- Project: `AppManager-OBP-M1` Android Java source tree.
- Repo: `davewilkinson89-web/AppManager-OBP` (created, public; stable `main` seeded with documentation only; M1 code belongs on feature branch).
- Intended branch: `feature/m1-secure-apk-manager` until build and physical device acceptance pass.
- Stable `main`: minimal documentation baseline; **no untested app code** should be merged.
- Artifact: local project source ZIP supplied with this handover.
- Build: **Not executed** (Android SDK/Gradle unavailable in session). No APK yet.
- GitHub Actions: workflow authored, **NOT run**; `workflow_dispatch` only. A copy must also be available on `main` to expose the manual workflow in Actions; running against main is deliberately skipped.

## Implemented source

- SAF-based APK directory selection, scanning/import, archive inspection.
- Android `PackageInstaller` session and result handling; update-first, verified cleanup, failure log and recovery prompt.
- Safe user-approved uninstall/reinstall for compatible conflicts.
- Installed OBP app grid with real application icons and launch/app-info actions.
- Launcher-pinned Apps shortcut request (requires launcher approval; not native folder management).
- Choose separate PNG icon output directory, export all installed OBP icons, automatic export after successful install.
- Local diagnostic history, copy/share, queued best-effort SAF log export to selected folder.
- JUnit InstallPolicy tests and manual-only GitHub build/test/lint job.

## Next milestone: validation

1. Source is intended for `feature/m1-secure-apk-manager`; verify the branch commit and source tree in GitHub. Never merge untested source to `main`.
2. Manually trigger GitHub workflow and fix any compilation/lint failures. Download debug APK artifact.
3. Device acceptance: folder selection, import one APK, install/update verified, cleanup, cancellation preserves APK, failure log copy/export, reinstall confirmation/data warning, icon grid, icon PNG export, pin dashboard shortcut.
4. Commit test fixes to feature branch; when fully green and user accepts device tests, merge to main and save verified source ZIP / APK into existing OBP Drive folder as needed.

## Known limitations / risks

- Drive upload uses SAF provider and is not guaranteed if Drive app does not offer a writable folder. Requires separate Drive OAuth integration if direct upload is necessary.
- Signing mismatch replacement can destroy old local data. Explicit confirmation required. Never silent uninstall.
- Pending reinstall relies on persisted attempt ID and retained source APK.
- Broad Android app visibility permissions are intended only for this personal utility; source may be public, but device logs should remain private.

## Security hardening checkpoint (2026-11-06)

- Original M1 ZIP retained as prior recovery point.
- Public-source audit: no embedded passwords, secrets, service-account keys, OAuth tokens, signing keys, or private email addresses found.
- Exported MainActivity cannot launch externally supplied approval intents; system-provided installer approval is opened via an immutable PendingIntent directly.
- External ACTION_SEND/ACTION_VIEW requires user confirmation before file import.
- Cleanup requires exact installed version and unchanged installer SHA-256 before deletion.
- Logs may reveal app names, filenames, device details and Android errors, and should **not** be uploaded to the public GitHub repo.
- Security changes NOT yet compiled or Android-device-tested. Run manual workflow and device acceptance before promoting.

- Automatic uninstall of AppManager itself is blocked to protect its own history and continuity.
