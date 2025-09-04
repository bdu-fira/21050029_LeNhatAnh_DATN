package com.example.parkingmobiapp.utils

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.example.parkingmobiapp.models.UserSettings
import com.example.parkingmobiapp.service.AuthenticationService
import com.example.parkingmobiapp.service.VehicleAuthenticationService

class SharedPrefsHelper(context: Context) {

    private val sharedPrefs: SharedPreferences = context.getSharedPreferences(
        "smart_parking_prefs",
        Context.MODE_PRIVATE
    )

    companion object {
        private const val KEY_VEHICLE_NOTIFICATION_ENABLED = "vehicle_notification_enabled"
        private const val KEY_SYSTEM_NOTIFICATION_ENABLED = "system_notification_enabled"
        private const val KEY_LAST_NOTIFICATION_ID = "last_notification_id"
        private const val KEY_STORED_NOTIFICATIONS = "stored_notifications"
        // User and Authentication constants (Legacy - for backward compatibility)
        private const val KEY_IS_LOGGED_IN = "is_logged_in"
        private const val KEY_USER_ID = "user_id"
        private const val KEY_USER_EMAIL = "user_email"
        private const val KEY_USER_PHONE = "user_phone"
        private const val KEY_USER_FULL_NAME = "user_full_name"
        private const val KEY_LOGIN_TIME = "login_time"
        private const val KEY_REMEMBER_LOGIN = "remember_login"

        // Authentication Session constants (For admin/guard - kept for compatibility)
        private const val KEY_AUTH_USERNAME = "auth_username"
        private const val KEY_AUTH_ROLE = "auth_role"
        private const val KEY_AUTH_NAME = "auth_name"
        private const val KEY_AUTH_SESSION_ACTIVE = "auth_session_active"
        private const val KEY_AUTH_LOGIN_TIME = "auth_login_time"

        // Vehicle Session constants (NEW - Primary authentication for mobile app)
        private const val KEY_VEHICLE_PLATE = "vehicle_plate"
        private const val KEY_VEHICLE_OWNER_NAME = "vehicle_owner_name"
        private const val KEY_VEHICLE_OWNER_PHONE = "vehicle_owner_phone"
        private const val KEY_VEHICLE_OWNER_EMAIL = "vehicle_owner_email"
        private const val KEY_VEHICLE_TYPE = "vehicle_type"
        private const val KEY_VEHICLE_BRAND = "vehicle_brand"
        private const val KEY_VEHICLE_MODEL = "vehicle_model"
        private const val KEY_VEHICLE_COLOR = "vehicle_color"
        private const val KEY_VEHICLE_SESSION_ACTIVE = "vehicle_session_active"
        private const val KEY_VEHICLE_LOGIN_TIME = "vehicle_login_time"
        private const val KEY_VEHICLE_EXPIRY_DATE = "vehicle_expiry_date"
        private const val KEY_VEHICLE_REGISTRATION_DATE = "vehicle_registration_date"

        // App Settings constants
        private const val KEY_PLATE_NUMBER = "plate_number"
        private const val KEY_NOTIFICATION_ENABLED = "notification_enabled"
        private const val KEY_VIBRATION_ENABLED = "vibration_enabled"
        private const val KEY_AUTO_REFRESH_ENABLED = "auto_refresh_enabled"
        private const val KEY_REFRESH_INTERVAL = "refresh_interval"
        private const val KEY_SELECTED_SOUND_URI = "selected_sound_uri"
        private const val KEY_LAST_UPDATE_TIME = "last_update_time"
        private const val KEY_APP_VERSION = "app_version"
        private const val KEY_FIRST_LAUNCH = "first_launch"
        private const val KEY_NOTIFICATION_COUNT = "notification_count"
        private const val KEY_LAST_NOTIFICATION_TIME = "last_notification_time"

        // Server Configuration constants
        private const val KEY_SERVER_URL = "server_url"
        private const val KEY_SERVER_CONFIGURED = "server_configured"
        private const val KEY_AUTO_REFRESH = "auto_refresh_parking"
        private const val KEY_REFRESH_INTERVAL_SECONDS = "refresh_interval_seconds"
        private const val KEY_LAST_PARKING_UPDATE = "last_parking_update"
        private const val KEY_CONNECTION_TIMEOUT = "connection_timeout"

        // Parking Cache constants
        private const val KEY_CACHED_PARKING_TOTAL = "cached_parking_total"
        private const val KEY_CACHED_PARKING_AVAILABLE = "cached_parking_available"
        private const val KEY_CACHED_PARKING_OCCUPIED = "cached_parking_occupied"
        private const val KEY_CACHED_PARKING_PERCENTAGE = "cached_parking_percentage"
        private const val KEY_CACHED_PARKING_MESSAGE = "cached_parking_message"
        private const val KEY_CACHED_PARKING_TIME = "cached_parking_time"

        // Default values
        private const val DEFAULT_SERVER_URL = "http://192.168.1.6:5000"
        private const val DEFAULT_REFRESH_INTERVAL = 30
        private const val DEFAULT_CONNECTION_TIMEOUT = 10
    }

    // ==========================================
    // ✅ NEW: VEHICLE SESSION METHODS (PRIMARY for Mobile App)
    // ==========================================

    /**
     * ✅ Lưu session vehicle cho mobile app
     */
    fun saveVehicleSession(vehicleInfo: VehicleAuthenticationService.VehicleInfo) {
        sharedPrefs.edit()
            .putString(KEY_VEHICLE_PLATE, vehicleInfo.plate_number)
            .putString(KEY_VEHICLE_OWNER_NAME, vehicleInfo.owner_name)
            .putString(KEY_VEHICLE_OWNER_PHONE, vehicleInfo.owner_phone)
            .putString(KEY_VEHICLE_OWNER_EMAIL, vehicleInfo.owner_email ?: "")
            .putString(KEY_VEHICLE_TYPE, vehicleInfo.vehicle_type)
            .putString(KEY_VEHICLE_BRAND, vehicleInfo.vehicle_brand ?: "")
            .putString(KEY_VEHICLE_MODEL, vehicleInfo.vehicle_model ?: "")
            .putString(KEY_VEHICLE_COLOR, vehicleInfo.vehicle_color ?: "")
            .putString(KEY_VEHICLE_EXPIRY_DATE, vehicleInfo.expiry_date ?: "")
            .putString(KEY_VEHICLE_REGISTRATION_DATE, vehicleInfo.registration_date)
            .putBoolean(KEY_VEHICLE_SESSION_ACTIVE, vehicleInfo.is_active)
            .putLong(KEY_VEHICLE_LOGIN_TIME, System.currentTimeMillis())
            .apply()

        // ✅ Tự động lưu plate number cho compatibility
        savePlateNumber(vehicleInfo.plate_number)

        Log.d("SharedPrefsHelper", "✅ Vehicle session saved: ${vehicleInfo.plate_number} - ${vehicleInfo.owner_name}")
    }

    /**
     * ✅ Lấy thông tin vehicle session
     */
    fun getVehicleSession(): VehicleSession? {
        if (!hasActiveVehicleSession()) {
            Log.d("SharedPrefsHelper", "No active vehicle session")
            return null
        }

        val plateNumber = sharedPrefs.getString(KEY_VEHICLE_PLATE, "") ?: ""
        if (plateNumber.isEmpty()) return null

        return VehicleSession(
            plateNumber = plateNumber,
            ownerName = sharedPrefs.getString(KEY_VEHICLE_OWNER_NAME, "") ?: "",
            ownerPhone = sharedPrefs.getString(KEY_VEHICLE_OWNER_PHONE, "") ?: "",
            ownerEmail = sharedPrefs.getString(KEY_VEHICLE_OWNER_EMAIL, "") ?: "",
            vehicleType = sharedPrefs.getString(KEY_VEHICLE_TYPE, "") ?: "",
            vehicleBrand = sharedPrefs.getString(KEY_VEHICLE_BRAND, "") ?: "",
            vehicleModel = sharedPrefs.getString(KEY_VEHICLE_MODEL, "") ?: "",
            vehicleColor = sharedPrefs.getString(KEY_VEHICLE_COLOR, "") ?: "",
            expiryDate = sharedPrefs.getString(KEY_VEHICLE_EXPIRY_DATE, "") ?: "",
            registrationDate = sharedPrefs.getString(KEY_VEHICLE_REGISTRATION_DATE, "") ?: "",
            loginTime = sharedPrefs.getLong(KEY_VEHICLE_LOGIN_TIME, 0)
        )
    }

    /**
     * ✅ Kiểm tra có vehicle session hợp lệ không (PRIMARY authentication check)
     */
    fun hasActiveVehicleSession(): Boolean {
        val isActive = sharedPrefs.getBoolean(KEY_VEHICLE_SESSION_ACTIVE, false)
        val loginTime = sharedPrefs.getLong(KEY_VEHICLE_LOGIN_TIME, 0)
        val plateNumber = sharedPrefs.getString(KEY_VEHICLE_PLATE, "") ?: ""

        if (plateNumber.isEmpty() || !isActive) {
            Log.d("SharedPrefsHelper", "Vehicle session inactive or missing plate")
            return false
        }

        // Session expire sau 24 giờ (24 * 60 * 60 * 1000 = 86400000 ms)
        val currentTime = System.currentTimeMillis()
        val sessionExpired = (currentTime - loginTime) > 86400000L

        val hasValidSession = isActive && !sessionExpired

        Log.d("SharedPrefsHelper", "Vehicle session check: plate=$plateNumber, active=$isActive, expired=$sessionExpired, valid=$hasValidSession")

        if (sessionExpired) {
            Log.w("SharedPrefsHelper", "Vehicle session expired, clearing...")
            clearVehicleSession()
            return false
        }

        return hasValidSession
    }

    /**
     * ✅ Xóa vehicle session
     */
    fun clearVehicleSession() {
        sharedPrefs.edit()
            .remove(KEY_VEHICLE_PLATE)
            .remove(KEY_VEHICLE_OWNER_NAME)
            .remove(KEY_VEHICLE_OWNER_PHONE)
            .remove(KEY_VEHICLE_OWNER_EMAIL)
            .remove(KEY_VEHICLE_TYPE)
            .remove(KEY_VEHICLE_BRAND)
            .remove(KEY_VEHICLE_MODEL)
            .remove(KEY_VEHICLE_COLOR)
            .remove(KEY_VEHICLE_EXPIRY_DATE)
            .remove(KEY_VEHICLE_REGISTRATION_DATE)
            .putBoolean(KEY_VEHICLE_SESSION_ACTIVE, false)
            .remove(KEY_VEHICLE_LOGIN_TIME)
            .apply()

        Log.d("SharedPrefsHelper", "🗑️ Vehicle session cleared")
    }

    /**
     * ✅ Kiểm tra xe có đang trong bãi không
     */
    fun isVehicleInParking(): Boolean {
        // Implement logic to check if vehicle is currently parked
        // This could be based on last entry/exit records
        return false // Default implementation
    }

    /**
     * ✅ Get formatted vehicle info for display
     */
    fun getVehicleDisplayInfo(): String {
        val session = getVehicleSession() ?: return "Chưa đăng nhập"

        return buildString {
            append("👤 ${session.ownerName}\n")
            append("🚗 ${session.plateNumber}\n")

            val vehicleDesc = mutableListOf<String>()
            if (session.vehicleBrand.isNotEmpty()) vehicleDesc.add(session.vehicleBrand)
            if (session.vehicleModel.isNotEmpty()) vehicleDesc.add(session.vehicleModel)
            if (session.vehicleColor.isNotEmpty()) vehicleDesc.add(session.vehicleColor)

            if (vehicleDesc.isNotEmpty()) {
                append("🏷️ ${session.vehicleType} - ${vehicleDesc.joinToString(" ")}")
            } else {
                append("🏷️ ${session.vehicleType}")
            }
        }
    }

    // ==========================================
    // USER LOGIN METHODS (Legacy - for backward compatibility)
    // ==========================================

    fun saveUserLogin(userId: String, email: String, phone: String, fullName: String, rememberLogin: Boolean = true) {
        sharedPrefs.edit()
            .putBoolean(KEY_IS_LOGGED_IN, true)
            .putString(KEY_USER_ID, userId)
            .putString(KEY_USER_EMAIL, email)
            .putString(KEY_USER_PHONE, phone)
            .putString(KEY_USER_FULL_NAME, fullName)
            .putLong(KEY_LOGIN_TIME, System.currentTimeMillis())
            .putBoolean(KEY_REMEMBER_LOGIN, rememberLogin)
            .apply()
    }

    fun isUserLoggedIn(): Boolean {
        // ✅ For mobile app, check vehicle session instead
        return hasActiveVehicleSession()
    }

    fun getUserId(): String {
        // ✅ Return vehicle plate as user ID for mobile app
        return getVehicleSession()?.plateNumber ?: sharedPrefs.getString(KEY_USER_ID, "") ?: ""
    }

    fun getUserEmail(): String {
        return getVehicleSession()?.ownerEmail ?: sharedPrefs.getString(KEY_USER_EMAIL, "") ?: ""
    }

    fun getUserPhone(): String {
        return getVehicleSession()?.ownerPhone ?: sharedPrefs.getString(KEY_USER_PHONE, "") ?: ""
    }

    fun getUserFullName(): String {
        return getVehicleSession()?.ownerName ?: sharedPrefs.getString(KEY_USER_FULL_NAME, "") ?: ""
    }

    fun getLoginTime(): Long {
        return getVehicleSession()?.loginTime ?: sharedPrefs.getLong(KEY_LOGIN_TIME, 0)
    }

    fun shouldRememberLogin(): Boolean {
        return sharedPrefs.getBoolean(KEY_REMEMBER_LOGIN, true)
    }

    fun logout() {
        // ✅ Clear both legacy and vehicle sessions
        clearVehicleSession()

        sharedPrefs.edit()
            .putBoolean(KEY_IS_LOGGED_IN, false)
            .remove(KEY_USER_ID)
            .remove(KEY_USER_EMAIL)
            .remove(KEY_USER_PHONE)
            .remove(KEY_USER_FULL_NAME)
            .remove(KEY_LOGIN_TIME)
            .apply()

        // Also clear auth session
        clearLoginSession()
    }

    fun updateUserProfile(email: String, phone: String, fullName: String) {
        // ✅ For vehicle sessions, this should be read-only
        // Vehicle info should only be updated via admin panel
        Log.w("SharedPrefsHelper", "Vehicle info is read-only for mobile users")
    }

    // ==========================================
    // AUTHENTICATION SESSION METHODS (Legacy - for admin/guard compatibility)
    // ==========================================

    /**
     * ✅ Lưu session đăng nhập từ AuthenticationService
     */
    fun saveLoginSession(userInfo: AuthenticationService.UserInfo) {
        sharedPrefs.edit()
            .putString(KEY_AUTH_USERNAME, userInfo.username)
            .putString(KEY_AUTH_ROLE, userInfo.role)
            .putString(KEY_AUTH_NAME, userInfo.name)
            .putBoolean(KEY_AUTH_SESSION_ACTIVE, true)
            .putLong(KEY_AUTH_LOGIN_TIME, System.currentTimeMillis())
            .apply()

        Log.d("SharedPrefsHelper", "✅ Admin/Guard session saved for: ${userInfo.username}")
    }

    /**
     * ✅ Kiểm tra có admin/guard session hợp lệ không
     */
    fun hasActiveSession(): Boolean {
        val isActive = sharedPrefs.getBoolean(KEY_AUTH_SESSION_ACTIVE, false)
        val loginTime = sharedPrefs.getLong(KEY_AUTH_LOGIN_TIME, 0)
        val currentTime = System.currentTimeMillis()

        // Session expire sau 8 giờ (8 * 60 * 60 * 1000 = 28800000 ms)
        val sessionExpired = (currentTime - loginTime) > 28800000L

        val hasValidSession = isActive && !sessionExpired

        Log.d("SharedPrefsHelper", "Admin session check: active=$isActive, expired=$sessionExpired, valid=$hasValidSession")

        return hasValidSession
    }

    /**
     * ✅ Lấy thông tin user từ admin session
     */
    fun getSessionUser(): AuthenticationService.UserInfo? {
        if (!hasActiveSession()) {
            Log.d("SharedPrefsHelper", "No active admin session")
            return null
        }

        val username = sharedPrefs.getString(KEY_AUTH_USERNAME, "") ?: ""
        val role = sharedPrefs.getString(KEY_AUTH_ROLE, "") ?: ""
        val name = sharedPrefs.getString(KEY_AUTH_NAME, "") ?: ""

        return if (username.isNotEmpty()) {
            AuthenticationService.UserInfo(username, role, name)
        } else {
            Log.d("SharedPrefsHelper", "Admin session data incomplete")
            null
        }
    }

    /**
     * ✅ Xóa admin session đăng nhập
     */
    fun clearLoginSession() {
        sharedPrefs.edit()
            .remove(KEY_AUTH_USERNAME)
            .remove(KEY_AUTH_ROLE)
            .remove(KEY_AUTH_NAME)
            .putBoolean(KEY_AUTH_SESSION_ACTIVE, false)
            .remove(KEY_AUTH_LOGIN_TIME)
            .apply()

        Log.d("SharedPrefsHelper", "🗑️ Admin/Guard session cleared")
    }

    // ==========================================
    // PLATE NUMBER METHODS
    // ==========================================

    fun savePlateNumber(plateNumber: String) {
        sharedPrefs.edit().putString(KEY_PLATE_NUMBER, plateNumber.trim().uppercase()).apply()
        Log.d("SharedPrefsHelper", "Plate number saved: $plateNumber")
    }

    fun getPlateNumber(): String {
        // ✅ Prioritize vehicle session plate number
        return getVehicleSession()?.plateNumber ?: sharedPrefs.getString(KEY_PLATE_NUMBER, "") ?: ""
    }

    // ==========================================
    // APP SETTINGS METHODS
    // ==========================================

    fun setNotificationEnabled(enabled: Boolean) {
        sharedPrefs.edit().putBoolean(KEY_NOTIFICATION_ENABLED, enabled).apply()
    }

    fun isNotificationEnabled(): Boolean {
        return sharedPrefs.getBoolean(KEY_NOTIFICATION_ENABLED, true)
    }

    fun setVibrationEnabled(enabled: Boolean) {
        sharedPrefs.edit().putBoolean(KEY_VIBRATION_ENABLED, enabled).apply()
    }

    fun isVibrationEnabled(): Boolean {
        return sharedPrefs.getBoolean(KEY_VIBRATION_ENABLED, true)
    }

    fun setAutoRefreshEnabled(enabled: Boolean) {
        sharedPrefs.edit().putBoolean(KEY_AUTO_REFRESH_ENABLED, enabled).apply()
    }

    fun isAutoRefreshEnabled(): Boolean {
        return sharedPrefs.getBoolean(KEY_AUTO_REFRESH_ENABLED, true)
    }

    fun setRefreshInterval(intervalSeconds: Int) {
        sharedPrefs.edit().putInt(KEY_REFRESH_INTERVAL, intervalSeconds).apply()
    }

    fun getRefreshInterval(): Int {
        return sharedPrefs.getInt(KEY_REFRESH_INTERVAL, 30)
    }

    fun setSelectedSoundUri(uri: String?) {
        sharedPrefs.edit().putString(KEY_SELECTED_SOUND_URI, uri).apply()
    }

    fun getSelectedSoundUri(): String? {
        return sharedPrefs.getString(KEY_SELECTED_SOUND_URI, null)
    }

    fun saveLastUpdateTime(timestamp: Long) {
        sharedPrefs.edit().putLong(KEY_LAST_UPDATE_TIME, timestamp).apply()
    }

    fun getLastUpdateTime(): Long {
        return sharedPrefs.getLong(KEY_LAST_UPDATE_TIME, 0)
    }

    fun saveAppVersion(version: String) {
        sharedPrefs.edit().putString(KEY_APP_VERSION, version).apply()
    }

    fun getAppVersion(): String {
        return sharedPrefs.getString(KEY_APP_VERSION, "") ?: ""
    }

    fun setFirstLaunch(isFirst: Boolean) {
        sharedPrefs.edit().putBoolean(KEY_FIRST_LAUNCH, isFirst).apply()
    }

    fun isFirstLaunch(): Boolean {
        return sharedPrefs.getBoolean(KEY_FIRST_LAUNCH, true)
    }

    fun resetNotificationCount() {
        sharedPrefs.edit().putInt(KEY_NOTIFICATION_COUNT, 0).apply()
    }

    fun getLastNotificationTime(): Long {
        return sharedPrefs.getLong(KEY_LAST_NOTIFICATION_TIME, 0)
    }

    // ==========================================
    // SERVER CONFIGURATION METHODS
    // ==========================================

    /**
     * ✅ Lấy server URL với default IP
     */
    fun getServerUrl(): String {
        return sharedPrefs.getString(KEY_SERVER_URL, DEFAULT_SERVER_URL) ?: DEFAULT_SERVER_URL
    }

    fun saveServerUrl(url: String) {
        val cleanUrl = url.trim().removeTrailingSlash()
        sharedPrefs.edit()
            .putString(KEY_SERVER_URL, cleanUrl)
            .putBoolean(KEY_SERVER_CONFIGURED, true)
            .apply()
        Log.d("SharedPrefsHelper", "Server URL saved: $cleanUrl")
    }

    /**
     * Kiểm tra server URL đã được cấu hình chưa
     */
    fun isServerConfigured(): Boolean {
        val url = getServerUrl()
        val configured = sharedPrefs.getBoolean(KEY_SERVER_CONFIGURED, false)

        // Consider default URL as configured for mobile app
        return configured || url.isNotEmpty()
    }

    /**
     * Validate server URL có hợp lệ không
     */
    fun validateServerUrl(url: String): Boolean {
        if (url.isBlank()) return false

        return try {
            val cleanUrl = url.trim().removeTrailingSlash()
            cleanUrl.startsWith("http://") || cleanUrl.startsWith("https://")
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Lưu cài đặt tự động refresh parking
     */
    fun setAutoRefreshParking(enabled: Boolean) {
        sharedPrefs.edit().putBoolean(KEY_AUTO_REFRESH, enabled).apply()
    }

    /**
     * Lưu interval refresh (giây) với validation
     */
    fun setRefreshIntervalSeconds(seconds: Int) {
        val validInterval = when {
            seconds < 10 -> 10  // Minimum 10 seconds
            seconds > 300 -> 300 // Maximum 5 minutes
            else -> seconds
        }
        sharedPrefs.edit().putInt(KEY_REFRESH_INTERVAL_SECONDS, validInterval).apply()
    }

    /**
     * Lấy interval refresh
     */
    fun getRefreshIntervalSeconds(): Int {
        return sharedPrefs.getInt(KEY_REFRESH_INTERVAL_SECONDS, 60)
    }

    /**
     * Lưu thời điểm cập nhật parking cuối
     */
    fun setLastParkingUpdate(timestamp: Long) {
        sharedPrefs.edit().putLong(KEY_LAST_PARKING_UPDATE, timestamp).apply()
    }

    /**
     * Lấy thời điểm cập nhật parking cuối
     */
    fun getLastParkingUpdate(): Long {
        return sharedPrefs.getLong(KEY_LAST_PARKING_UPDATE, 0L)
    }

    /**
     * Kiểm tra cần cập nhật parking không (dựa trên interval)
     */
    fun shouldUpdateParking(): Boolean {
        if (!isAutoRefreshEnabled()) return false

        val lastUpdate = getLastParkingUpdate()
        val currentTime = System.currentTimeMillis()
        val intervalMs = getRefreshIntervalSeconds() * 1000L

        return (currentTime - lastUpdate) >= intervalMs
    }

    // ==========================================
    // PARKING CACHE METHODS
    // ==========================================

    /**
     * ✅ Cache parking status data
     */
    fun cacheParkingStatus(total: Int, available: Int, occupied: Int, percentage: Double, message: String) {
        sharedPrefs.edit()
            .putInt(KEY_CACHED_PARKING_TOTAL, total)
            .putInt(KEY_CACHED_PARKING_AVAILABLE, available)
            .putInt(KEY_CACHED_PARKING_OCCUPIED, occupied)
            .putFloat(KEY_CACHED_PARKING_PERCENTAGE, percentage.toFloat())
            .putString(KEY_CACHED_PARKING_MESSAGE, message)
            .putLong(KEY_CACHED_PARKING_TIME, System.currentTimeMillis())
            .apply()

        Log.d("SharedPrefsHelper", "📦 Cached parking data: $available/$total available")
    }

    /**
     * ✅ Get cached parking status
     */
    fun getCachedParkingStatus(): ParkingCache? {
        val cacheTime = sharedPrefs.getLong(KEY_CACHED_PARKING_TIME, 0L)

        // Cache chỉ valid trong 5 phút
        if (System.currentTimeMillis() - cacheTime > 300000) {
            Log.d("SharedPrefsHelper", "Parking cache expired")
            return null
        }

        return ParkingCache(
            total = sharedPrefs.getInt(KEY_CACHED_PARKING_TOTAL, 0),
            available = sharedPrefs.getInt(KEY_CACHED_PARKING_AVAILABLE, 0),
            occupied = sharedPrefs.getInt(KEY_CACHED_PARKING_OCCUPIED, 0),
            percentage = sharedPrefs.getFloat(KEY_CACHED_PARKING_PERCENTAGE, 0f).toDouble(),
            message = sharedPrefs.getString(KEY_CACHED_PARKING_MESSAGE, "") ?: "",
            cacheTime = cacheTime
        )
    }

    /**
     * Xóa cache parking
     */
    fun clearParkingCache() {
        sharedPrefs.edit()
            .remove(KEY_CACHED_PARKING_TOTAL)
            .remove(KEY_CACHED_PARKING_AVAILABLE)
            .remove(KEY_CACHED_PARKING_OCCUPIED)
            .remove(KEY_CACHED_PARKING_PERCENTAGE)
            .remove(KEY_CACHED_PARKING_MESSAGE)
            .remove(KEY_CACHED_PARKING_TIME)
            .apply()
    }

    // ==========================================
    // NETWORK SETTINGS
    // ==========================================

    /**
     * Lưu timeout kết nối (giây)
     */
    fun setConnectionTimeout(seconds: Int) {
        val validTimeout = when {
            seconds < 5 -> 5
            seconds > 60 -> 60
            else -> seconds
        }
        sharedPrefs.edit().putInt(KEY_CONNECTION_TIMEOUT, validTimeout).apply()
    }

    /**
     * Lấy timeout kết nối
     */
    fun getConnectionTimeout(): Int {
        return sharedPrefs.getInt(KEY_CONNECTION_TIMEOUT, DEFAULT_CONNECTION_TIMEOUT)
    }

    // ==========================================
    // USER SETTINGS HELPER METHODS
    // ==========================================

    fun getUserSettings(): UserSettings {
        return UserSettings(
            plateNumber = getPlateNumber(),
            notificationsEnabled = isNotificationEnabled(),
            soundEnabled = true,
            vibrationEnabled = isVibrationEnabled(),
            autoRefreshEnabled = isAutoRefreshEnabled(),
            refreshIntervalSeconds = getRefreshInterval(),
            selectedSoundUri = getSelectedSoundUri()
        )
    }

    fun saveUserSettings(settings: UserSettings) {
        sharedPrefs.edit()
            .putString(KEY_PLATE_NUMBER, settings.plateNumber)
            .putBoolean(KEY_NOTIFICATION_ENABLED, settings.notificationsEnabled)
            .putBoolean(KEY_VIBRATION_ENABLED, settings.vibrationEnabled)
            .putBoolean(KEY_AUTO_REFRESH_ENABLED, settings.autoRefreshEnabled)
            .putInt(KEY_REFRESH_INTERVAL, settings.refreshIntervalSeconds)
            .putString(KEY_SELECTED_SOUND_URI, settings.selectedSoundUri)
            .apply()
    }

    // ==========================================
    // UTILITY METHODS
    // ==========================================

    /**
     * Validate và clean server URL
     */
    private fun String.removeTrailingSlash(): String {
        return if (this.endsWith("/")) {
            this.substring(0, this.length - 1)
        } else {
            this
        }
    }

    /**
     * Lấy full API URL
     */
    fun getApiUrl(endpoint: String): String {
        val baseUrl = getServerUrl()
        val cleanEndpoint = if (endpoint.startsWith("/")) endpoint else "/$endpoint"
        return "$baseUrl$cleanEndpoint"
    }

    /**
     * Reset tất cả cài đặt về mặc định
     */
    fun resetToDefaults() {
        sharedPrefs.edit()
            .putString(KEY_SERVER_URL, DEFAULT_SERVER_URL)
            .putBoolean(KEY_SERVER_CONFIGURED, true) // Default to configured for mobile
            .putInt(KEY_REFRESH_INTERVAL_SECONDS, DEFAULT_REFRESH_INTERVAL)
            .putInt(KEY_CONNECTION_TIMEOUT, DEFAULT_CONNECTION_TIMEOUT)
            .putBoolean(KEY_AUTO_REFRESH_ENABLED, true)
            .putBoolean(KEY_NOTIFICATION_ENABLED, true)
            .putBoolean(KEY_VIBRATION_ENABLED, true)
            .apply()
        clearParkingCache()
    }

    /**
     * Clear all data (for debugging)
     */
    fun clearAllData() {
        sharedPrefs.edit().clear().apply()
        Log.d("SharedPrefsHelper", "🗑️ All data cleared")
    }

    /**
     * ✅ UPDATED: Configuration summary with vehicle info
     */
    fun getConfigurationSummary(): Map<String, Any> {
        val vehicleSession = getVehicleSession()

        return mapOf(
            "server_url" to getServerUrl(),
            "server_configured" to isServerConfigured(),
            "auto_refresh" to isAutoRefreshEnabled(),
            "refresh_interval" to getRefreshIntervalSeconds(),
            "notifications_enabled" to isNotificationEnabled(),
            "has_cached_data" to (getCachedParkingStatus() != null),
            "last_update" to getLastParkingUpdate(),
            "user_logged_in" to isUserLoggedIn(),
            "plate_registered" to getPlateNumber().isNotEmpty(),
            "auth_session_active" to hasActiveSession(),
            "vehicle_session_active" to hasActiveVehicleSession(),
            "vehicle_plate" to (vehicleSession?.plateNumber ?: ""),
            "vehicle_owner" to (vehicleSession?.ownerName ?: "")
        )
    }

    /**
     * Debug: Print all stored values
     */
    fun debugPrintAll() {
        Log.d("SharedPrefsHelper", "=== DEBUG: ALL STORED VALUES ===")
        val all = sharedPrefs.all
        for ((key, value) in all) {
            Log.d("SharedPrefsHelper", "$key = $value")
        }
        Log.d("SharedPrefsHelper", "=== END DEBUG ===")
    }

    fun setServerUrl(url: String) {
        saveServerUrl(url)
    }
    fun clearCache() {
        clearParkingCache()

        // Clear other cache data
        sharedPrefs.edit()
            .remove(KEY_LAST_UPDATE_TIME)
            .remove(KEY_LAST_PARKING_UPDATE)
            .remove(KEY_LAST_NOTIFICATION_TIME)
            .apply()

        Log.d("SharedPrefsHelper", "🗑️ Cache cleared")
    }


    fun setUserLoggedIn(loggedIn: Boolean) {
        if (loggedIn) {
            // If setting as logged in, ensure we have either vehicle session or legacy login
            val hasVehicleSession = hasActiveVehicleSession()
            val hasLegacyLogin = sharedPrefs.getBoolean(KEY_IS_LOGGED_IN, false)

            if (!hasVehicleSession && !hasLegacyLogin) {
                // Create a default session for testing
                Log.d("SharedPrefsHelper", "Creating default session for testing")
                sharedPrefs.edit()
                    .putBoolean(KEY_IS_LOGGED_IN, true)
                    .putString(KEY_USER_ID, "test_user")
                    .putString(KEY_USER_FULL_NAME, "Test User")
                    .putLong(KEY_LOGIN_TIME, System.currentTimeMillis())
                    .apply()
            }
        } else {
            logout()
        }
    }



    // ==========================================
    // DATA CLASSES
    // ==========================================

    /**
     * Data class for parking cache
     */
    data class ParkingCache(
        val total: Int,
        val available: Int,
        val occupied: Int,
        val percentage: Double,
        val message: String,
        val cacheTime: Long
    ) {
        fun isExpired(): Boolean {
            return System.currentTimeMillis() - cacheTime > 300000 // 5 minutes
        }

        fun getAgeMinutes(): Int {
            return ((System.currentTimeMillis() - cacheTime) / 60000).toInt()
        }
    }

    /**
     * ✅ NEW: Data class for vehicle session (mobile app primary authentication)
     */
    data class VehicleSession(
        val plateNumber: String,
        val ownerName: String,
        val ownerPhone: String,
        val ownerEmail: String,
        val vehicleType: String,
        val vehicleBrand: String,
        val vehicleModel: String,
        val vehicleColor: String,
        val expiryDate: String,
        val registrationDate: String,
        val loginTime: Long
    ) {
        fun isExpired(): Boolean {
            return System.currentTimeMillis() - loginTime > 86400000L // 24 hours
        }

        fun getDisplayName(): String {
            return "$ownerName ($plateNumber)"
        }

        fun getVehicleDescription(): String {
            val parts = mutableListOf<String>()
            if (vehicleBrand.isNotEmpty()) parts.add(vehicleBrand)
            if (vehicleModel.isNotEmpty()) parts.add(vehicleModel)
            if (vehicleColor.isNotEmpty()) parts.add(vehicleColor)

            return if (parts.isNotEmpty()) {
                "$vehicleType - ${parts.joinToString(" ")}"
            } else {
                vehicleType
            }
        }

        fun getShortInfo(): String {
            return "$ownerName - $plateNumber"
        }

        fun getDetailedInfo(): String {
            return buildString {
                appendLine("👤 Chủ xe: $ownerName")
                appendLine("🚗 Biển số: $plateNumber")
                appendLine("📞 SĐT: $ownerPhone")
                if (ownerEmail.isNotEmpty()) {
                    appendLine("📧 Email: $ownerEmail")
                }
                appendLine("🏷️ Loại xe: $vehicleType")
                if (vehicleBrand.isNotEmpty()) {
                    appendLine("🏭 Hãng: $vehicleBrand")
                }
                if (vehicleModel.isNotEmpty()) {
                    appendLine("🚙 Mẫu xe: $vehicleModel")
                }
                if (vehicleColor.isNotEmpty()) {
                    appendLine("🎨 Màu sắc: $vehicleColor")
                }
                if (registrationDate.isNotEmpty()) {
                    appendLine("📅 Đăng ký: $registrationDate")
                }
                if (expiryDate.isNotEmpty()) {
                    appendLine("⏰ Hết hạn: $expiryDate")
                }
            }.trim()
        }

        fun isNearExpiry(daysThreshold: Int = 30): Boolean {
            if (expiryDate.isEmpty()) return false

            return try {
                val expiryTimestamp = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).parse(expiryDate)?.time ?: 0
                val currentTime = System.currentTimeMillis()
                val threshold = daysThreshold * 24 * 60 * 60 * 1000L // Convert days to milliseconds

                (expiryTimestamp - currentTime) <= threshold && expiryTimestamp > currentTime
            } catch (e: Exception) {
                false
            }
        }

        fun isExpiredRegistration(): Boolean {
            if (expiryDate.isEmpty()) return false

            return try {
                val expiryTimestamp = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).parse(expiryDate)?.time ?: 0
                expiryTimestamp < System.currentTimeMillis()
            } catch (e: Exception) {
                false
            }
        }

        fun getLoginDurationHours(): Long {
            return (System.currentTimeMillis() - loginTime) / (60 * 60 * 1000) // Convert to hours
        }
    }



}