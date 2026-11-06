package com.oddbearproductions.appmanager;

/** Small, platform-independent rules used by both UI and unit tests. */
public final class InstallPolicy {
    private InstallPolicy() {}

    public enum FailureAction { OFFER_REINSTALL, REPORT_ONLY, NO_REINSTALL }

    /** Android PackageInstaller status integers are stable across supported versions. */
    public static FailureAction classify(int status, boolean alreadyInstalled) {
        // STATUS_FAILURE_CONFLICT=5, STATUS_FAILURE_INVALID=4; the latter
        // can indicate an invalid APK, so never use it to trigger uninstall.
        if (status == 5 && alreadyInstalled) return FailureAction.OFFER_REINSTALL;
        if (status == 3) return FailureAction.NO_REINSTALL; // user aborted
        return FailureAction.REPORT_ONLY;
    }

    public static FailureAction classifyDetailed(int status, String detail, boolean installed,
                                                 long incomingCode, long installedCode) {
        if (!installed) return classify(status, false);
        if (classify(status, true) == FailureAction.OFFER_REINSTALL) return FailureAction.OFFER_REINSTALL;
        // Some Android versions describe signing and downgrade errors as INVALID.
        String d = detail == null ? "" : detail.toUpperCase(java.util.Locale.ROOT);
        if (status == 4 && (d.contains("UPDATE_INCOMPATIBLE") ||
                d.contains("VERSION_DOWNGRADE") || d.contains("SIGNATURE"))) {
            return FailureAction.OFFER_REINSTALL;
        }
        return classify(status, true);
    }

    public static boolean mayDeleteInstaller(boolean installerReportedSuccess,
                                              boolean verifiedPackage, boolean matchedVersion) {
        return installerReportedSuccess && verifiedPackage && matchedVersion;
    }

    public static boolean isApkName(String name) {
        return name != null && name.toLowerCase(java.util.Locale.ROOT).endsWith(".apk");
    }

    public static String safeName(String name) {
        if (name == null) return "unknown.apk";
        String clean = name.replaceAll("[^a-zA-Z0-9._ -]", "_").trim();
        if (clean.isEmpty()) clean = "unknown.apk";
        if (clean.length() > 120) clean = clean.substring(clean.length() - 120);
        return clean;
    }

    public static boolean looksLikeObp(String pkg, String label) {
        String a = pkg == null ? "" : pkg.toLowerCase(java.util.Locale.ROOT);
        String b = label == null ? "" : label.toLowerCase(java.util.Locale.ROOT);
        return a.contains("oddbear") || a.startsWith("com.obp.") ||
                b.contains("obp") || b.contains("oddbear");
    }
}
