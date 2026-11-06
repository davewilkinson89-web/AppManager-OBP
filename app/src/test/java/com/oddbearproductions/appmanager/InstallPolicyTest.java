package com.oddbearproductions.appmanager;

import org.junit.Test;
import static org.junit.Assert.*;

public class InstallPolicyTest {
    @Test public void conflictsCanOfferSafeReinstallOnlyForInstalledPackages() {
        assertEquals(InstallPolicy.FailureAction.OFFER_REINSTALL, InstallPolicy.classify(5, true));
        assertEquals(InstallPolicy.FailureAction.REPORT_ONLY, InstallPolicy.classify(5, false));
        assertEquals(InstallPolicy.FailureAction.REPORT_ONLY, InstallPolicy.classify(4, true));
        assertEquals(InstallPolicy.FailureAction.REPORT_ONLY, InstallPolicy.classify(7, true));
        assertEquals(InstallPolicy.FailureAction.NO_REINSTALL, InstallPolicy.classify(3, true));
    }
    @Test public void signatureAndDowngradeCanOfferConfirmedReinstall() {
        assertEquals(InstallPolicy.FailureAction.OFFER_REINSTALL,
                InstallPolicy.classifyDetailed(4,"INSTALL_FAILED_VERSION_DOWNGRADE",true,4,5));
        assertEquals(InstallPolicy.FailureAction.OFFER_REINSTALL,
                InstallPolicy.classifyDetailed(4,"update signature mismatch",true,4,5));
        assertEquals(InstallPolicy.FailureAction.REPORT_ONLY,
                InstallPolicy.classifyDetailed(4,"Malformed APK",true,4,5));
        assertEquals(InstallPolicy.FailureAction.REPORT_ONLY,
                InstallPolicy.classifyDetailed(4,"INSTALL_FAILED_VERSION_DOWNGRADE",false,4,-1));
    }
    @Test public void deletionRequiresVerifiedInstallAndVersion() {
        assertFalse(InstallPolicy.mayDeleteInstaller(true, false, true));
        assertFalse(InstallPolicy.mayDeleteInstaller(true, true, false));
        assertFalse(InstallPolicy.mayDeleteInstaller(false, true, true));
        assertTrue(InstallPolicy.mayDeleteInstaller(true, true, true));
    }
    @Test public void fileNamesMustBeApks() {
        assertTrue(InstallPolicy.isApkName("NEW-APP.APK"));
        assertFalse(InstallPolicy.isApkName("app.aab"));
        assertFalse(InstallPolicy.isApkName("app.apk.zip"));
        assertEquals("name_.apk", InstallPolicy.safeName("name?.apk"));
    }
    @Test public void obpInstalledAppRecognition() {
        assertTrue(InstallPolicy.looksLikeObp("com.oddbearproductions.lightsout", "LightsOut"));
        assertTrue(InstallPolicy.looksLikeObp("org.anything", "Squares OBP"));
        assertFalse(InstallPolicy.looksLikeObp("org.example.other", "Calculator"));
    }
}
