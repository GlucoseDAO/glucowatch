package io.github.antonkulaga.glucowatch.phone

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle

/** Browser-owned authentication/downloads, shown in an app-task Custom Tab when supported. */
object ExportBrowser {
    fun open(activity: Activity, address: String, request: Int, preferChromium: Boolean = false): Boolean {
        val generic = Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com")).addCategory(Intent.CATEGORY_BROWSABLE)
        val browsers = activity.packageManager.queryIntentActivities(generic, 0).filter {
            it.activityInfo.packageName != "org.chromium.webview_shell" && it.activityInfo.packageName != activity.packageName
        }
        val preferred = activity.packageManager.resolveActivity(generic, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
        // CareLink Personal lists Chrome/Safari; avoid selecting Firefox there merely
        // because it is the default browser. Chromium is useful on AOSP test devices.
        val compatible = if (preferChromium) listOf("com.android.chrome", "com.chrome.beta", "com.chrome.dev", "org.chromium.chrome")
            .mapNotNull { name -> browsers.firstOrNull { it.activityInfo.packageName == name } } else emptyList()
        val browser = compatible.firstOrNull { it.activityInfo.packageName == preferred } ?: compatible.firstOrNull()
            ?: browsers.firstOrNull { it.activityInfo.packageName == preferred } ?: browsers.firstOrNull() ?: return false
        // The public Custom Tabs intent protocol needs no Play Services or browser SDK.
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(address)).setPackage(browser.activityInfo.packageName)
            .addCategory(Intent.CATEGORY_BROWSABLE).putExtras(Bundle().apply {
                putBinder("android.support.customtabs.extra.SESSION", null)
                putInt("android.support.customtabs.extra.TOOLBAR_COLOR", Brand.BACKGROUND)
            })
        return runCatching { activity.startActivityForResult(intent, request); true }.getOrDefault(false)
    }
}
