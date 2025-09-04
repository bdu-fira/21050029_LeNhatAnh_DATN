package com.example.parkingmobiapp.service

import android.util.Log
import com.example.parkingmobiapp.utils.SharedPrefsHelper
import com.google.gson.Gson
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Service để lấy thông tin trạng thái parking từ server với authentication
 */
class ParkingStatusService(private val sharedPrefsHelper: SharedPrefsHelper) {

    companion object {
        private const val TAG = "ParkingStatusService"
        private const val REFRESH_INTERVAL = 30000L // 30 seconds
    }

    private val gson = Gson()

    // Cookie jar để lưu session cookies
    private val cookieJar = AppCookieJar()

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .cookieJar(cookieJar) // Quan trọng: Lưu session cookies
        .addInterceptor { chain ->
            val original = chain.request()
            val requestBuilder = original.newBuilder()
                .header("Accept-Encoding", "gzip")
                .header("Cache-Control", "max-age=5")
                .header("User-Agent", "ParkingApp/1.0")
            chain.proceed(requestBuilder.build())
        }
        .build()

    // Callback interfaces
    interface ParkingStatusCallback {
        fun onStatusUpdated(status: ParkingStatusData)
        fun onError(error: String)
    }

    interface QuickStatusCallback {
        fun onQuickStatusUpdated(available: Int, total: Int, hasSpace: Boolean)
        fun onError(error: String)
    }

    // ==========================================
    // DATA CLASSES
    // ==========================================

    // Response wrapper từ server
    data class ParkingApiResponse(
        val success: Boolean,
        val data: ParkingStatusData? = null,
        val server_time: String? = null,
        val cached: Boolean? = false,
        val error_fallback: Boolean? = false,
        val message: String? = null,
        val error: String? = null
    )

    // Data structure chính
    data class ParkingStatusData(
        val parking_status: ParkingInfo,
        val status_message: String,
        val last_updated: String,
        val color_indicator: String
    )

    // Thông tin parking
    data class ParkingInfo(
        val total: Int,
        val available: Int,
        val occupied: Int,
        val percentage_full: Double
    )

    // Quick status response
    data class QuickStatusResponse(
        val available: Int,
        val total: Int,
        val has_space: Boolean,
        val timestamp: Long
    )

    // Authentication data classes
    data class LoginRequest(
        val username: String,
        val password: String,
        val role: String = "admin"
    )

    data class LoginResponse(
        val success: Boolean,
        val message: String,
        val user: AuthenticationService.UserInfo? = null,
        val redirect_url: String? = null
    )

    // Coroutine job for background updates
    private var updateJob: Job? = null
    private var isRunning = false

    // ==========================================
    // AUTHENTICATION METHODS
    // ==========================================

    /**
     * Đăng nhập với server
     */
    private fun login(username: String, password: String, callback: (Boolean, String?) -> Unit) {
        val baseUrl = sharedPrefsHelper.getServerUrl()
        if (baseUrl.isEmpty()) {
            callback(false, "Chưa cấu hình địa chỉ server")
            return
        }

        val loginRequest = LoginRequest(username, password, "admin")
        val requestBody = gson.toJson(loginRequest)

        Log.d(TAG, "=== LOGIN REQUEST ===")
        Log.d(TAG, "URL: $baseUrl/api/auth/login")
        Log.d(TAG, "Request body: $requestBody")

        val request = Request.Builder()
            .url("$baseUrl/api/auth/login")
            .post(requestBody.toRequestBody("application/json".toMediaType()))
            .addHeader("Content-Type", "application/json")
            .addHeader("Accept", "application/json")
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e(TAG, "❌ Login network error: ${e.message}", e)
                callback(false, "Lỗi kết nối: ${e.message}")
            }

            override fun onResponse(call: Call, response: Response) {
                response.use { resp ->
                    Log.d(TAG, "✅ Login response received")
                    Log.d(TAG, "Response code: ${resp.code}")
                    Log.d(TAG, "Response headers: ${resp.headers}")

                    if (!resp.isSuccessful) {
                        Log.e(TAG, "❌ Login failed: ${resp.code}")
                        callback(false, "Đăng nhập thất bại: ${resp.code}")
                        return
                    }

                    try {
                        val responseBody = resp.body?.string()
                        Log.d(TAG, "📄 Login response body: $responseBody")

                        if (responseBody.isNullOrEmpty()) {
                            callback(false, "Server trả về dữ liệu rỗng")
                            return
                        }

                        val loginResponse = gson.fromJson(responseBody, LoginResponse::class.java)
                        Log.d(TAG, "📊 Parsed login response: $loginResponse")

                        if (loginResponse.success && loginResponse.user != null) {
                            // Lưu thông tin đăng nhập
                            sharedPrefsHelper.saveLoginSession(loginResponse.user)
                            Log.d(TAG, "✅ Login successful for: ${loginResponse.user.username}")
                            callback(true, null)
                        } else {
                            Log.e(TAG, "❌ Login failed: ${loginResponse.message}")
                            callback(false, loginResponse.message)
                        }

                    } catch (e: Exception) {
                        Log.e(TAG, "❌ Login parse error: ${e.message}", e)
                        callback(false, "Lỗi phân tích phản hồi: ${e.message}")
                    }
                }
            }
        })
    }

    /**
     * Kiểm tra session hiện tại có hợp lệ không
     */
    private fun checkAuthStatus(callback: (Boolean) -> Unit) {
        val baseUrl = sharedPrefsHelper.getServerUrl()
        if (baseUrl.isEmpty()) {
            callback(false)
            return
        }

        val request = Request.Builder()
            .url("$baseUrl/api/auth/check")
            .get()
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e(TAG, "Auth check failed: ${e.message}")
                callback(false)
            }

            override fun onResponse(call: Call, response: Response) {
                Log.d(TAG, "Auth check response: ${response.code}")
                callback(response.isSuccessful)
            }
        })
    }

    // ==========================================
    // MAIN PARKING API METHODS
    // ==========================================

    /**
     * Method chính: Login và get parking status
     */
    fun loginAndGetParkingStatus(callback: ParkingStatusCallback) {
        // Kiểm tra đã có session chưa
        if (sharedPrefsHelper.hasActiveSession()) {
            Log.d(TAG, "✅ Already has active session, getting parking status directly")
            getParkingStatusDirect(callback)
            return
        }

        // Chưa có session -> login trước
        Log.d(TAG, "🔑 No active session, logging in first...")

        login("admin", "admin123") { success, error ->
            if (success) {
                Log.d(TAG, "✅ Login successful, now getting parking status")
                getParkingStatusDirect(callback)
            } else {
                Log.e(TAG, "❌ Login failed: $error")
                callback.onError("Đăng nhập thất bại: $error")
            }
        }
    }

    /**
     * Lấy thông tin parking status một lần (với authentication)
     */
    fun getParkingStatus(callback: ParkingStatusCallback) {
        loginAndGetParkingStatus(callback)
    }

    /**
     * Get parking status trực tiếp (sau khi đã login)
     */
    private fun getParkingStatusDirect(callback: ParkingStatusCallback) {
        val baseUrl = sharedPrefsHelper.getServerUrl()
        if (baseUrl.isEmpty()) {
            callback.onError("Chưa cấu hình địa chỉ server")
            return
        }

        val request = Request.Builder()
            .url("$baseUrl/api/mobile/parking-summary")
            .addHeader("Content-Type", "application/json")
            .addHeader("Accept", "application/json")
            .build()

        Log.d(TAG, "📡 Making authenticated request to: ${request.url}")

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e(TAG, "❌ Parking API network error: ${e.message}")
                callback.onError("Lỗi kết nối mạng: ${e.message}")
            }

            override fun onResponse(call: Call, response: Response) {
                response.use { resp ->
                    Log.d(TAG, "📡 Parking API response: ${resp.code}")
                    Log.d(TAG, "Response headers: ${resp.headers}")

                    if (!resp.isSuccessful) {
                        if (resp.code == 401) {
                            // Session expired -> retry with login
                            Log.w(TAG, "🔑 Session expired (401), retrying with login...")
                            sharedPrefsHelper.clearLoginSession()
                            loginAndGetParkingStatus(callback)
                        } else {
                            callback.onError("Server error: ${resp.code}")
                        }
                        return
                    }

                    try {
                        val responseBody = resp.body?.string()
                        Log.d(TAG, "📄 Parking response body:")
                        Log.d(TAG, responseBody ?: "NULL BODY")

                        if (responseBody.isNullOrEmpty()) {
                            callback.onError("Server trả về dữ liệu rỗng")
                            return
                        }

                        // Parse response với cấu trúc mới
                        val apiResponse = gson.fromJson(responseBody, ParkingApiResponse::class.java)
                        Log.d(TAG, "📊 Parsed parking response: $apiResponse")

                        if (apiResponse.success && apiResponse.data != null) {
                            Log.d(TAG, "✅ Parking data received: ${apiResponse.data}")
                            callback.onStatusUpdated(apiResponse.data)
                        } else {
                            val errorMsg = apiResponse.error ?: apiResponse.message ?: "Server không trả về dữ liệu hợp lệ"
                            Log.e(TAG, "❌ API returned error: $errorMsg")
                            callback.onError(errorMsg)
                        }

                    } catch (e: Exception) {
                        Log.e(TAG, "❌ Parking parse error: ${e.message}", e)
                        val responseBody = ""
                        Log.e(TAG, "Raw response was: $responseBody")
                        callback.onError("Lỗi phân tích dữ liệu: ${e.message}")
                    }
                }
            }
        })
    }

    /**
     * Lấy thông tin nhanh (chỉ số lượng chỗ trống)
     */
    fun getQuickStatus(callback: QuickStatusCallback) {
        val baseUrl = sharedPrefsHelper.getServerUrl()
        if (baseUrl.isEmpty()) {
            callback.onError("Chưa cấu hình địa chỉ server")
            return
        }

        val request = Request.Builder()
            .url("$baseUrl/api/mobile/quick-status")
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e(TAG, "Quick status network error: ${e.message}")
                callback.onError("Lỗi kết nối")
            }

            override fun onResponse(call: Call, response: Response) {
                response.use { resp ->
                    if (!resp.isSuccessful) {
                        if (resp.code == 401) {
                            // Need authentication for quick status too
                            Log.w(TAG, "Quick status needs auth, trying to login...")
                            login("admin", "admin123") { success, error ->
                                if (success) {
                                    getQuickStatus(callback)
                                } else {
                                    callback.onError("Authentication failed: $error")
                                }
                            }
                        } else {
                            callback.onError("Server error: ${resp.code}")
                        }
                        return
                    }

                    try {
                        val responseBody = resp.body?.string()
                        val quickResponse = gson.fromJson(responseBody, QuickStatusResponse::class.java)

                        callback.onQuickStatusUpdated(
                            quickResponse.available,
                            quickResponse.total,
                            quickResponse.has_space
                        )
                    } catch (e: Exception) {
                        Log.e(TAG, "Quick status parse error: ${e.message}")
                        callback.onError("Lỗi phân tích dữ liệu")
                    }
                }
            }
        })
    }

    // ==========================================
    // PERIODIC UPDATE METHODS
    // ==========================================

    /**
     * Bắt đầu cập nhật định kỳ
     */
    fun startPeriodicUpdates(callback: ParkingStatusCallback) {
        if (isRunning) {
            Log.w(TAG, "Periodic updates already running")
            return
        }

        isRunning = true
        updateJob = CoroutineScope(Dispatchers.IO).launch {
            while (isRunning) {
                try {
                    // Sử dụng suspend function để gọi API
                    val status = getParkingStatusSuspend()
                    status?.let {
                        withContext(Dispatchers.Main) {
                            callback.onStatusUpdated(it)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Periodic update error: ${e.message}")
                    withContext(Dispatchers.Main) {
                        callback.onError("Lỗi cập nhật: ${e.message}")
                    }
                }

                delay(REFRESH_INTERVAL)
            }
        }

        Log.d(TAG, "Started periodic parking status updates")
    }

    /**
     * Dừng cập nhật định kỳ
     */
    fun stopPeriodicUpdates() {
        isRunning = false
        updateJob?.cancel()
        updateJob = null
        Log.d(TAG, "Stopped periodic parking status updates")
    }

    /**
     * Suspend function để lấy parking status
     */
    private suspend fun getParkingStatusSuspend(): ParkingStatusData? {
        return withContext(Dispatchers.IO) {
            val baseUrl = sharedPrefsHelper.getServerUrl()
            if (baseUrl.isEmpty()) return@withContext null

            try {
                // Ensure we're authenticated first
                if (!sharedPrefsHelper.hasActiveSession()) {
                    val loginSuccess = loginSuspend("admin", "admin123")
                    if (!loginSuccess) return@withContext null
                }

                val request = Request.Builder()
                    .url("$baseUrl/api/mobile/parking-summary")
                    .addHeader("Content-Type", "application/json")
                    .build()

                val response = client.newCall(request).execute()
                response.use { resp ->
                    if (!resp.isSuccessful) {
                        if (resp.code == 401) {
                            // Retry with fresh login
                            sharedPrefsHelper.clearLoginSession()
                            val retryLogin = loginSuspend("admin", "admin123")
                            if (retryLogin) {
                                val retryResponse = client.newCall(request).execute()
                                retryResponse.use { retryResp ->
                                    if (retryResp.isSuccessful) {
                                        val responseBody = retryResp.body?.string()
                                        val apiResponse = gson.fromJson(responseBody, ParkingApiResponse::class.java)
                                        return@withContext if (apiResponse.success) apiResponse.data else null
                                    }
                                }
                            }
                        }
                        return@withContext null
                    }

                    val responseBody = resp.body?.string()
                    val apiResponse = gson.fromJson(responseBody, ParkingApiResponse::class.java)

                    if (apiResponse.success) {
                        return@withContext apiResponse.data
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Suspend parking status error: ${e.message}")
            }

            return@withContext null
        }
    }

    /**
     * Suspend login function
     */
    private suspend fun loginSuspend(username: String, password: String): Boolean {
        return withContext(Dispatchers.IO) {
            val baseUrl = sharedPrefsHelper.getServerUrl()
            if (baseUrl.isEmpty()) return@withContext false

            try {
                val loginRequest = LoginRequest(username, password, "admin")
                val requestBody = gson.toJson(loginRequest)

                val request = Request.Builder()
                    .url("$baseUrl/api/auth/login")
                    .post(requestBody.toRequestBody("application/json".toMediaType()))
                    .addHeader("Content-Type", "application/json")
                    .build()

                val response = client.newCall(request).execute()
                response.use { resp ->
                    if (!resp.isSuccessful) return@withContext false

                    val responseBody = resp.body?.string()
                    val loginResponse = gson.fromJson(responseBody, LoginResponse::class.java)

                    if (loginResponse.success && loginResponse.user != null) {
                        sharedPrefsHelper.saveLoginSession(loginResponse.user)
                        return@withContext true
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Suspend login error: ${e.message}")
            }

            return@withContext false
        }
    }

    // ==========================================
    // DETAILED PARKING METHODS
    // ==========================================

    /**
     * Kiểm tra ô đỗ cụ thể
     */
    fun checkSpecificSpace(spaceId: Int, callback: (Boolean, String?) -> Unit) {
        val baseUrl = sharedPrefsHelper.getServerUrl()
        if (baseUrl.isEmpty()) {
            callback(false, "Chưa cấu hình server")
            return
        }

        val request = Request.Builder()
            .url("$baseUrl/api/parking/check-space/$spaceId")
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                callback(false, "Lỗi kết nối: ${e.message}")
            }

            override fun onResponse(call: Call, response: Response) {
                response.use { resp ->
                    if (!resp.isSuccessful) {
                        if (resp.code == 401) {
                            // Retry with authentication
                            login("admin", "admin123") { success, error ->
                                if (success) {
                                    checkSpecificSpace(spaceId, callback)
                                } else {
                                    callback(false, "Authentication failed: $error")
                                }
                            }
                        } else {
                            callback(false, "Server error: ${resp.code}")
                        }
                        return
                    }

                    try {
                        val responseBody = resp.body?.string()
                        val result = gson.fromJson(responseBody, SpaceCheckResponse::class.java)

                        if (result.success) {
                            callback(result.data.is_available, null)
                        } else {
                            callback(false, result.error)
                        }
                    } catch (e: Exception) {
                        callback(false, "Lỗi phân tích dữ liệu")
                    }
                }
            }
        })
    }

    /**
     * Lấy danh sách ô đỗ trống
     */
    fun getEmptySpaces(callback: (List<Int>, String?) -> Unit) {
        val baseUrl = sharedPrefsHelper.getServerUrl()
        if (baseUrl.isEmpty()) {
            callback(emptyList(), "Chưa cấu hình server")
            return
        }

        val request = Request.Builder()
            .url("$baseUrl/api/parking/empty-spaces")
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                callback(emptyList(), "Lỗi kết nối: ${e.message}")
            }

            override fun onResponse(call: Call, response: Response) {
                response.use { resp ->
                    if (!resp.isSuccessful) {
                        if (resp.code == 401) {
                            // Retry with authentication
                            login("admin", "admin123") { success, error ->
                                if (success) {
                                    getEmptySpaces(callback)
                                } else {
                                    callback(emptyList(), "Authentication failed: $error")
                                }
                            }
                        } else {
                            callback(emptyList(), "Server error: ${resp.code}")
                        }
                        return
                    }

                    try {
                        val responseBody = resp.body?.string()
                        val result = gson.fromJson(responseBody, EmptySpacesResponse::class.java)

                        if (result.success) {
                            val spaceIds = result.data.empty_spaces.map { it.space_id + 1 } // Convert to 1-based
                            callback(spaceIds, null)
                        } else {
                            callback(emptyList(), result.error)
                        }
                    } catch (e: Exception) {
                        callback(emptyList(), "Lỗi phân tích dữ liệu")
                    }
                }
            }
        })
    }

    // ==========================================
    // RESPONSE DATA CLASSES
    // ==========================================

    private data class SpaceCheckResponse(
        val success: Boolean,
        val data: SpaceData,
        val error: String?
    )

    private data class SpaceData(
        val space_id: Int,
        val status: String,
        val is_available: Boolean
    )

    private data class EmptySpacesResponse(
        val success: Boolean,
        val data: EmptySpacesData,
        val error: String?
    )

    private data class EmptySpacesData(
        val empty_spaces: List<EmptySpace>,
        val count: Int
    )

    private data class EmptySpace(
        val space_id: Int,
        val status: String
    )

    // ==========================================
    // COOKIE JAR CLASS
    // ==========================================

    /**
     * Cookie Jar để lưu session cookies
     */
    private class AppCookieJar : CookieJar {
        private val cookies = mutableListOf<Cookie>()

        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            Log.d(TAG, "💾 Saving ${cookies.size} cookies from ${url.host}")
            this.cookies.clear()
            this.cookies.addAll(cookies)
            cookies.forEach { cookie ->
                Log.d(TAG, "🍪 Cookie: ${cookie.name} = ${cookie.value}")
            }
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            Log.d(TAG, "📤 Loading ${cookies.size} cookies for ${url.host}")
            return cookies
        }
    }
}