package com.skip.app

internal object AdSkipPolicy {
    private val adContextRegex = Regex(
        pattern = "广告|推广|赞助|sponsored|advertisement|\\bad\\b",
        option = RegexOption.IGNORE_CASE,
    )

    private val ignoredPackages = setOf(
        "com.android.systemui",
        "com.android.settings",
        "com.android.permissioncontroller",
        "com.google.android.permissioncontroller",
        "com.android.packageinstaller",
        "com.google.android.packageinstaller",
        "com.android.launcher",
        "com.android.launcher3",
        "com.google.android.apps.nexuslauncher",
        "com.miui.home",
        "com.miui.securitycenter",
        "com.lbe.security.miui",
        "com.huawei.android.launcher",
        "com.huawei.systemmanager",
        "com.sec.android.app.launcher",
        "com.samsung.android.permissioncontroller",
        "com.oppo.launcher",
        "com.coloros.safecenter",
        "com.vivo.launcher",
        "com.vivo.permissionmanager",
    )

    private val unsafeImportedActions = setOf(
        "允许",
        "同意",
        "始终允许",
        "仅在使用中允许",
        "继续安装",
        "立即安装",
        "安装",
        "购买",
        "支付",
    )

    fun isIgnoredPackage(packageName: String): Boolean {
        return ignoredPackages.any { ignored ->
            packageName == ignored || packageName.startsWith("$ignored.")
        }
    }

    fun isExpectedWindow(expectedPackage: String, activePackage: String?): Boolean {
        return activePackage == expectedPackage && !isIgnoredPackage(activePackage)
    }

    fun containsAdContext(text: String): Boolean {
        return adContextRegex.containsMatchIn(text)
    }

    fun isUnsafeImportedAction(action: String): Boolean {
        val normalized = action.trim().removePrefix("=").removePrefix("+")
        return unsafeImportedActions.any { normalized.equals(it, ignoreCase = true) }
    }
}
