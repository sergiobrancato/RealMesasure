package com.realmeasure.app.data

import android.content.Context

/**
 * Tiny SharedPreferences wrapper for app-level flags (currently just the
 * onboarding dismissal).
 */
class AppPreferences(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("realmeasure_prefs", Context.MODE_PRIVATE)

    /** True once the user checked "Don't show again" in the tutorial. */
    var onboardingDismissed: Boolean
        get() = prefs.getBoolean(KEY_ONBOARDING_DISMISSED, false)
        set(value) {
            prefs.edit().putBoolean(KEY_ONBOARDING_DISMISSED, value).apply()
        }

    private companion object {
        const val KEY_ONBOARDING_DISMISSED = "onboarding_dismissed"
    }
}
