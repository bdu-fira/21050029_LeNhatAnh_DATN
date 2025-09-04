// File: app/src/main/java/com/example/parkingmobiapp/utils/Constants.kt

package com.example.parkingmobiapp.utils

object Constants {
    const val NOTIFICATION_CHANNEL_ID = "parking_notifications"
    const val NOTIFICATION_CHANNEL_NAME = "Thông báo bãi đỗ xe"
    const val FOREGROUND_SERVICE_ID = 1001

    const val LOCATION_UPDATE_INTERVAL = 30000L // 30 giây
    const val FASTEST_LOCATION_UPDATE_INTERVAL = 15000L // 15 giây
    const val WRONG_POSITION_THRESHOLD = 10.0 // 10 mét
    const val NEARBY_RADIUS_DEFAULT = 500 // 500 mét

    const val SHARED_PREFS_NAME = "parking_prefs"
    const val KEY_USER_ID = "user_id"
    const val KEY_NOTIFICATIONS_ENABLED = "notifications_enabled"
    const val KEY_FIRST_RUN = "first_run"

    const val REQUEST_CODE_LOCATION_PERMISSION = 1001
    const val REQUEST_CODE_NOTIFICATION_PERMISSION = 1002

    // API endpoints
    const val API_TIMEOUT = 30L // seconds
    const val MAX_RETRY_ATTEMPTS = 3

    // Database
    const val DATABASE_VERSION = 1
    const val DATABASE_NAME = "parking_database"

    // Map settings
    const val DEFAULT_ZOOM = 15f
    const val MARKER_ICON_SIZE = 48
}