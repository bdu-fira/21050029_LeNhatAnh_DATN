package com.example.parkingmobiapp.service

import android.util.Log
import com.example.parkingmobiapp.utils.SharedPrefsHelper
import com.google.gson.Gson
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

class AuthenticationService(private val sharedPrefsHelper: SharedPrefsHelper) {

    companion object {
        private const val TAG = "AuthService"
    }

    private val gson = Gson()
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .cookieJar(AppCookieJar()) // Quan trọng: Lưu cookie session
        .build()

    // Interface cho callback
    interface LoginCallback {
        fun onLoginSuccess(userInfo: UserInfo)
        fun onLoginError(error: String)
    }

    // Data classes
    data class LoginRequest(
        val username: String,
        val password: String,
        val role: String = "admin"
    )

    data class LoginResponse(
        val success: Boolean,
        val message: String,
        val user: UserInfo? = null,
        val redirect_url: String? = null
    )

    data class UserInfo(
        val username: String,
        val role: String,
        val name: String
    )

    /**
     * Đăng nhập với server
     */
    fun login(username: String, password: String, callback: LoginCallback) {
        val baseUrl = sharedPrefsHelper.getServerUrl()
        if (baseUrl.isEmpty()) {
            callback.onLoginError("Chưa cấu hình địa chỉ server")
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
                callback.onLoginError("Lỗi kết nối: ${e.message}")
            }

            override fun onResponse(call: Call, response: Response) {
                response.use { resp ->
                    Log.d(TAG, "✅ Login response received")
                    Log.d(TAG, "Response code: ${resp.code}")
                    Log.d(TAG, "Response headers: ${resp.headers}")

                    if (!resp.isSuccessful) {
                        Log.e(TAG, "❌ Login failed: ${resp.code}")
                        callback.onLoginError("Đăng nhập thất bại: ${resp.code}")
                        return
                    }

                    try {
                        val responseBody = resp.body?.string()
                        Log.d(TAG, "📄 Login response body: $responseBody")

                        if (responseBody.isNullOrEmpty()) {
                            callback.onLoginError("Server trả về dữ liệu rỗng")
                            return
                        }

                        val loginResponse = gson.fromJson(responseBody, LoginResponse::class.java)
                        Log.d(TAG, "📊 Parsed login response: $loginResponse")

                        if (loginResponse.success && loginResponse.user != null) {
                            // Lưu thông tin đăng nhập
                            sharedPrefsHelper.saveLoginSession(loginResponse.user)
                            Log.d(TAG, "✅ Login successful for: ${loginResponse.user.username}")
                            callback.onLoginSuccess(loginResponse.user)
                        } else {
                            Log.e(TAG, "❌ Login failed: ${loginResponse.message}")
                            callback.onLoginError(loginResponse.message)
                        }

                    } catch (e: Exception) {
                        Log.e(TAG, "❌ Login parse error: ${e.message}", e)
                        callback.onLoginError("Lỗi phân tích phản hồi: ${e.message}")
                    }
                }
            }
        })
    }

    /**
     * Kiểm tra trạng thái đăng nhập hiện tại
     */
    fun checkAuthStatus(callback: (Boolean) -> Unit) {
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
                callback(response.isSuccessful)
            }
        })
    }

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