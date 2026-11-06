# AppManager OBP — security and privacy

This personal Android utility manages user-selected APK files. It can install APKs only through Android PackageInstaller and requires system approval for installation/uninstall. Use trusted APKs only.

- Public repository contains **source** and CI configuration only. Never upload original third-party APKs or app signing credentials.
- `QUERY_ALL_PACKAGES` enables a local installed-app list; no inventory is transmitted by this application.
- Logs stay private in app storage until copied/shared or optionally written to a user-approved SAF folder. They may include device model, app identifiers, APK filenames and error messages. Keep this destination private.
- The app does not request INTERNET permission; Google Drive sync is only through a user-selected provider folder when available.
- APK reinstall may delete the old app's local data and requires explicit Android/user approval.
- Source is not yet tested on a physical device. Report security issues privately to the project owner rather than posting diagnostic logs publicly.
