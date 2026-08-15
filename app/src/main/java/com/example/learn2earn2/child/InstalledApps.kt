package com.example.learn2earn2.child

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo

data class InstalledChildApp(
    val packageName: String,
    val label: String
) {
    fun asFirebaseValue() = mapOf("packageName" to packageName, "label" to label)
}

object InstalledApps {
    /**
     * Returns apps a parent can sensibly manage. System and updated-system packages
     * are deliberately omitted, as are Learn2Earn itself and disabled packages.
     */
    @Suppress("DEPRECATION")
    fun userApps(context: Context): List<InstalledChildApp> {
        val launchIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return context.packageManager
            .queryIntentActivities(launchIntent, 0)
            .asSequence()
            .mapNotNull { it.activityInfo?.applicationInfo }
            .filter { appInfo ->
                val flags = appInfo.flags
                appInfo.enabled &&
                    appInfo.packageName != context.packageName &&
                    flags and ApplicationInfo.FLAG_SYSTEM == 0 &&
                    flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP == 0
            }
            .distinctBy { it.packageName }
            .map { appInfo ->
                InstalledChildApp(
                    packageName = appInfo.packageName,
                    label = context.packageManager.getApplicationLabel(appInfo).toString()
                )
            }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })
            .toList()
    }
}
