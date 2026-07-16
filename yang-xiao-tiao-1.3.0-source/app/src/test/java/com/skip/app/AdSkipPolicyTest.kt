package com.skip.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdSkipPolicyTest {
    @Test
    fun permissionControllersAreIgnored() {
        assertTrue(AdSkipPolicy.isIgnoredPackage("com.android.permissioncontroller"))
        assertTrue(AdSkipPolicy.isIgnoredPackage("com.google.android.permissioncontroller.overlay"))
        assertFalse(AdSkipPolicy.isIgnoredPackage("com.example.video"))
    }

    @Test
    fun activeWindowMustStillBelongToEventPackage() {
        assertTrue(AdSkipPolicy.isExpectedWindow("com.example.video", "com.example.video"))
        assertFalse(AdSkipPolicy.isExpectedWindow("com.example.video", "com.example.bank"))
        assertFalse(
            AdSkipPolicy.isExpectedWindow(
                "com.android.permissioncontroller",
                "com.android.permissioncontroller",
            )
        )
    }

    @Test
    fun adContextIsRecognizedWithoutMatchingNormalPages() {
        assertTrue(AdSkipPolicy.containsAdContext("广告 5 秒后可关闭"))
        assertTrue(AdSkipPolicy.containsAdContext("Sponsored content"))
        assertFalse(AdSkipPolicy.containsAdContext("个人设置"))
    }

    @Test
    fun importedRulesCannotGrantPermissionsOrInstallApps() {
        assertTrue(AdSkipPolicy.isUnsafeImportedAction("允许"))
        assertTrue(AdSkipPolicy.isUnsafeImportedAction("=继续安装"))
        assertFalse(AdSkipPolicy.isUnsafeImportedAction("不允许"))
        assertFalse(AdSkipPolicy.isUnsafeImportedAction("稍后再说"))
    }
}
