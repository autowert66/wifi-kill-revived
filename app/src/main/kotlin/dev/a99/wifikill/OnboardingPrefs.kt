package dev.a99.wifikill

import android.content.Context

/**
 * Tracks whether the first-launch onboarding was ever completed. Completion
 * also records that the user accepted the fair-use confirmation on the
 * disclaimer page, since that page gates the only path to the final page.
 *
 * A version counter (rather than a boolean) lets a future content change
 * re-show the tour once, exactly like the initial rollout after an update.
 */
object OnboardingPrefs {

    /** Bump to re-show the onboarding tour once for everyone after an update. */
    const val CURRENT_VERSION = 1

    private const val FILE = "onboarding"
    private const val KEY_COMPLETED_VERSION = "completed_version"

    fun completedVersion(context: Context): Int =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getInt(KEY_COMPLETED_VERSION, 0)

    fun isCompleted(context: Context): Boolean =
        completedVersion(context) >= CURRENT_VERSION

    fun markCompleted(context: Context) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_COMPLETED_VERSION, CURRENT_VERSION)
            .apply()
    }
}
