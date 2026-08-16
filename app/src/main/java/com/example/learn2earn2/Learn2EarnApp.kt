package com.example.learn2earn2

import android.app.Activity
import android.app.Application
import android.os.Bundle
import com.example.learn2earn2.child.ForegroundAppTracker

class Learn2EarnApp : Application() {
    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                ForegroundAppTracker.onActivityResumed()
            }

            override fun onActivityPaused(activity: Activity) {
                ForegroundAppTracker.onActivityPaused()
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }
}
