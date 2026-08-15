package com.example.learn2earn2.child

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Restores local child enforcement after reboot or an app update. */
class ChildBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED &&
            intent?.action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            return
        }
        val prefs = context.getSharedPreferences("learn2earn_child", Context.MODE_PRIVATE)
        if (prefs.getString(ChildLockService.PARENT_ID, null).isNullOrBlank() ||
            prefs.getString(ChildLockService.CHILD_ID, null).isNullOrBlank()
        ) {
            return
        }
        ChildLockService.start(context)
    }
}
