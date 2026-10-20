package io.github.antonkulaga.glucowatch.phone

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle

/** Browser-owned authentication/downloads, shown in an app-task Custom Tab when supported. */
object ExportBrowser {
    private val chromePackages = listOf("com.android.chrome", "com.chrome.beta", "com.chrome.dev", "com.chrome.canary")

    /** Google sign-in stays in Chrome. Never silently substitutes an embedded WebView. */
    fun openChrome(activity: Activity, address: String, request: Int): Boolean {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(address)).addCategory(Intent.CATEGORY_BROWSABLE)
        val chrome = chromePackages.firstOrNull { name ->
            activity.packageManager.resolveActivity(Intent(intent).setPackage(name), 0) != null
        }
        if (chrome == null) {
            android.app.AlertDialog.Builder(activity).setTitle("Install Chrome")
                .setMessage("Chrome is needed for Google sign-in to Strava on this device. Install or enable it, then return to Workouts.")
                .setPositiveButton("Get Chrome") { _, _ ->
                    runCatching { activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=com.android.chrome"))) }
                        .recoverCatching { activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=com.android.chrome"))) }
                        .onFailure { android.widget.Toast.makeText(activity, "No app store or browser is installed. Install Chrome on this device first.", android.widget.Toast.LENGTH_LONG).show() }
                }.setNegativeButton("Cancel", null).show()
            return false
        }
        return runCatching { activity.startActivityForResult(intent.setPackage(chrome).putExtras(Bundle().apply {
            putBinder("android.support.customtabs.extra.SESSION", null)
        }), request); true }.getOrDefault(false)
    }

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
            .addFlags(Intent.FLAG_ACTIVITY_NO_HISTORY)
            .addCategory(Intent.CATEGORY_BROWSABLE).putExtras(Bundle().apply {
                putBinder("android.support.customtabs.extra.SESSION", null)
                putInt("android.support.customtabs.extra.TOOLBAR_COLOR", Brand.BACKGROUND)
            })
        return runCatching { activity.startActivityForResult(intent, request); true }.getOrDefault(false)
    }
}
