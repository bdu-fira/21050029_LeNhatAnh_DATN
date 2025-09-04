// File: app/src/main/java/com/example/parkingmobiapp/utils/AppConstants.kt
package com.example.parkingmobiapp.utils

object AppConstants {

    // App Information
    const val APP_VERSION = "1.0.0"
    const val APP_NAME = "Smart Parking"

    // Server Configuration
    const val BASE_URL = "http://192.168.1.6:5000"
    const val WEBSOCKET_URL = "ws://192.168.1.6:5000"

    // API Endpoints
    const val ENDPOINT_LOGIN = "/api/auth/login"
    const val ENDPOINT_PARKING_STATUS = "/api/mobile/parking-summary"
    const val ENDPOINT_QUICK_STATUS = "/api/mobile/quick-status"
    const val ENDPOINT_VEHICLE_AUTH = "/api/auth/vehicle"

    // Network Configuration
    const val CONNECTION_TIMEOUT = 15000L // 15 seconds
    const val READ_TIMEOUT = 30000L // 30 seconds
    const val WRITE_TIMEOUT = 15000L // 15 seconds

    // Refresh Intervals
    const val DEFAULT_REFRESH_INTERVAL = 30 // seconds
    const val MIN_REFRESH_INTERVAL = 10 // seconds
    const val MAX_REFRESH_INTERVAL = 300 // 5 minutes

    // WebSocket Configuration
    const val WEBSOCKET_RECONNECT_DELAY = 5000L // 5 seconds
    const val WEBSOCKET_MAX_RECONNECT_ATTEMPTS = 10
    const val WEBSOCKET_PING_INTERVAL = 30000L // 30 seconds

    // Notification Configuration
    const val NOTIFICATION_CHANNEL_DEFAULT = "default_channel"
    const val NOTIFICATION_CHANNEL_URGENT = "urgent_channel"
    const val NOTIFICATION_CHANNEL_PARKING = "parking_channel"
    const val NOTIFICATION_CHANNEL_SYSTEM = "system_channel"

    // Cache Configuration
    const val CACHE_EXPIRY_TIME = 300000L // 5 minutes
    const val MAX_CACHE_SIZE = 50 // max items

    // Session Configuration
    const val SESSION_TIMEOUT = 86400000L // 24 hours for vehicle session
    const val ADMIN_SESSION_TIMEOUT = 28800000L // 8 hours for admin session

    // Parking Configuration
    const val MAX_PARKING_SPACES = 100
    const val DEFAULT_PARKING_SPACES = 50

    // Validation Rules
    const val MIN_PLATE_LENGTH = 6
    const val MAX_PLATE_LENGTH = 12
    const val MIN_PASSWORD_LENGTH = 6

    // Error Messages
    const val ERROR_NETWORK = "Lỗi kết nối mạng"
    const val ERROR_SERVER = "Lỗi server"
    const val ERROR_AUTHENTICATION = "Lỗi xác thực"
    const val ERROR_INVALID_DATA = "Dữ liệu không hợp lệ"
    const val ERROR_TIMEOUT = "Hết thời gian chờ"

    // Success Messages
    const val SUCCESS_LOGIN = "Đăng nhập thành công"
    const val SUCCESS_LOGOUT = "Đăng xuất thành công"
    const val SUCCESS_UPDATE = "Cập nhật thành công"

    // Default Values
    const val DEFAULT_PLATE_NUMBER = ""
    const val DEFAULT_USER_NAME = "Người dùng"
    const val DEFAULT_NOTIFICATION_ENABLED = true
    const val DEFAULT_VIBRATION_ENABLED = true
    const val DEFAULT_AUTO_REFRESH_ENABLED = true

    // Regex Patterns
    const val PLATE_NUMBER_PATTERN = "^[0-9]{2}[A-Z]{1,2}[0-9]{3,5}$"
    const val EMAIL_PATTERN = "^[A-Za-z0-9+_.-]+@([A-Za-z0-9.-]+\\.[A-Za-z]{2,})$"
    const val PHONE_PATTERN = "^[0-9]{10,11}$"

    // Date Formats
    const val DATE_FORMAT_DISPLAY = "dd/MM/yyyy HH:mm"
    const val DATE_FORMAT_API = "yyyy-MM-dd'T'HH:mm:ss"
    const val DATE_FORMAT_SHORT = "HH:mm"

    // Keys for Intent extras
    const val EXTRA_PLATE_NUMBER = "plate_number"
    const val EXTRA_NOTIFICATION_ID = "notification_id"
    const val EXTRA_FROM_NOTIFICATION = "from_notification"
    const val EXTRA_ACTION_TYPE = "action_type"

    // Shared Preferences Keys (Public constants)
    const val PREF_FIRST_LAUNCH = "first_launch"
    const val PREF_TUTORIAL_SHOWN = "tutorial_shown"
    const val PREF_LAST_VERSION = "last_version"

    // Feature Flags
    const val FEATURE_WEBSOCKET_ENABLED = true
    const val FEATURE_PUSH_NOTIFICATIONS_ENABLED = true
    const val FEATURE_AUTO_REFRESH_ENABLED = true
    const val FEATURE_OFFLINE_MODE_ENABLED = true



    // Vehicle Types
    val VEHICLE_TYPES = listOf(
        "Xe máy",
        "Xe hơi",
        "Xe tải nhỏ",
        "Xe khác"
    )

    // Notification Types Priority
    val HIGH_PRIORITY_NOTIFICATIONS = setOf(
        "PARKING_FULL",
        "SPACE_AVAILABLE",
        "WRONG_POSITION",
        "URGENT_ALERT",
        "PAYMENT_DUE"
    )

    // Color Resources Names (for dynamic theming)
    const val COLOR_PRIMARY = "colorPrimary"
    const val COLOR_SUCCESS = "success_color"
    const val COLOR_WARNING = "warning_color"
    const val COLOR_ERROR = "error_color"
    const val COLOR_INFO = "info_color"

    // Backup Configuration
    const val BACKUP_MAX_ITEMS = 1000
    const val BACKUP_RETENTION_DAYS = 30
}