package ru.scooblydoon.thunbudget

import android.content.SharedPreferences

/** Android persistence adapter; queue data stays local in app-private preferences. */
class SharedPreferencesNotificationQueueStore(
    private val preferences: SharedPreferences,
    private val key: String = "tbank_notification_review_queue_v1"
) : NotificationQueueStore {
    override fun read(): String? = preferences.getString(key, null)
    override fun write(value: String): Boolean =
        preferences.edit().putString(key, value).commit()
}
