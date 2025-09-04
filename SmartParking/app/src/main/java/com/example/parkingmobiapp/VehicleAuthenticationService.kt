package com.example.parkingmobiapp.service

import android.util.Log
import com.example.parkingmobiapp.utils.SharedPrefsHelper
import com.google.gson.Gson
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

class VehicleAuthenticationService(private val sharedPrefsHelper: SharedPrefsHelper) {

    companion object {
        private const val TAG = "VehicleAuthService"
    }

    private val gson = Gson()
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .cookieJar(CookieJarImpl())
        .build()

    // Data classes cho vehicle authentication
    data class VehicleLoginRequest(
        val plate_number: String,
        val owner_phone: String
    )

    data class VehicleLoginResponse(
        val success: Boolean,
        val message: String,
        val vehicle_info: VehicleInfo? = null
    )

    data class VehicleInfo(
        val plate_number: String,
        val owner_name: String,
        val owner_phone: String,
        val owner_email: String?,
        val vehicle_type: String,
        val vehicle_brand: String?,
        val vehicle_model: String?,
        val vehicle_color: String?,
        val registration_date: String,
        val expiry_date: String?,
        val is_active: Boolean
    )

    interface VehicleLoginCallback {
        fun onLoginSuccess(vehicleInfo: VehicleInfo)
        fun onLoginError(error: String)
    }

    /**
     * Đăng nhập bằng biển số xe và số điện thoại
     */
    fun loginWithVehicle(plateNumber: String, phone: String, callback: VehicleLoginCallback) {
        val serverUrl = sharedPrefsHelper.getServerUrl()
        if (serverUrl.isEmpty()) {
            callback.onLoginError("Chưa cấu hình server. Vui lòng cài đặt địa chỉ server.")
            return
        }

        val loginRequest = VehicleLoginRequest(
            plate_number = plateNumber.trim().uppercase(),
            owner_phone = phone.trim()
        )

        val requestBody = gson.toJson(loginRequest)
        Log.d(TAG, "🚗 Vehicle login request: ${loginRequest.plate_number} / ${loginRequest.owner_phone}")

        val request = Request.Builder()
            .url("$serverUrl/api/mobile/vehicle-login")
            .post(requestBody.toRequestBody("application/json".toMediaType()))
            .addHeader("Content-Type", "application/json")
            .addHeader("Accept", "application/json")
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e(TAG, "❌ Vehicle login network error: ${e.message}", e)
                callback.onLoginError("Lỗi kết nối mạng: ${e.message}")
            }

            override fun onResponse(call: Call, response: Response) {
                response.use { resp ->
                    Log.d(TAG, "📱 Vehicle login response: ${resp.code}")

                    if (!resp.isSuccessful) {
                        val errorMsg = when (resp.code) {
                            401 -> "Thông tin đăng nhập không chính xác"
                            404 -> "Không tìm thấy xe đã đăng ký"
                            403 -> "Xe đã bị vô hiệu hóa"
                            500 -> "Lỗi server. Vui lòng thử lại sau"
                            else -> "Lỗi đăng nhập: ${resp.code}"
                        }
                        callback.onLoginError(errorMsg)
                        return
                    }

                    try {
                        val responseBody = resp.body?.string()
                        if (responseBody.isNullOrEmpty()) {
                            callback.onLoginError("Server trả về dữ liệu rỗng")
                            return
                        }

                        Log.d(TAG, "📄 Response body: $responseBody")
                        val loginResponse = gson.fromJson(responseBody, VehicleLoginResponse::class.java)

                        if (loginResponse.success && loginResponse.vehicle_info != null) {
                            // Lưu thông tin xe vào session
                            sharedPrefsHelper.saveVehicleSession(loginResponse.vehicle_info)

                            Log.i(TAG, "✅ Vehicle login successful: ${loginResponse.vehicle_info.plate_number}")
                            callback.onLoginSuccess(loginResponse.vehicle_info)
                        } else {
                            Log.w(TAG, "❌ Vehicle login failed: ${loginResponse.message}")
                            callback.onLoginError(loginResponse.message)
                        }

                    } catch (e: Exception) {
                        Log.e(TAG, "❌ Vehicle login parse error: ${e.message}", e)
                        callback.onLoginError("Lỗi xử lý dữ liệu: ${e.message}")
                    }
                }
            }
        })
    }

    /**
     * Kiểm tra trạng thái session xe
     */
    fun checkVehicleSession(callback: (Boolean) -> Unit) {
        val vehicleSession = sharedPrefsHelper.getVehicleSession()
        if (vehicleSession == null) {
            callback(false)
            return
        }

        val serverUrl = sharedPrefsHelper.getServerUrl()
        if (serverUrl.isEmpty()) {
            callback(false)
            return
        }

        val request = Request.Builder()
            .url("$serverUrl/api/mobile/check-vehicle-session")
            .post("".toRequestBody("application/json".toMediaType()))
            .addHeader("Content-Type", "application/json")
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.w(TAG, "Session check failed: ${e.message}")
                callback(false)
            }

            override fun onResponse(call: Call, response: Response) {
                callback(response.isSuccessful)
            }
        })
    }

    /**
     * Lấy lịch sử ra/vào của xe
     */
    fun getVehicleHistory(plateNumber: String, callback: (List<ParkingHistory>, String?) -> Unit) {
        val serverUrl = sharedPrefsHelper.getServerUrl()

        val request = Request.Builder()
            .url("$serverUrl/api/mobile/vehicle-history/$plateNumber")
            .get()
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                callback(emptyList(), "Lỗi kết nối: ${e.message}")
            }

            override fun onResponse(call: Call, response: Response) {
                response.use { resp ->
                    if (!resp.isSuccessful) {
                        callback(emptyList(), "Server error: ${resp.code}")
                        return
                    }

                    try {
                        val responseBody = resp.body?.string()
                        val jsonResponse = gson.fromJson(responseBody, Map::class.java) as Map<String, Any>

                        val success = jsonResponse["success"] as? Boolean ?: false
                        if (!success) {
                            callback(emptyList(), jsonResponse["error"] as? String ?: "Unknown error")
                            return
                        }

                        val historyList = jsonResponse["history"] as? List<Map<String, Any>> ?: emptyList()
                        val history = historyList.map { item ->
                            ParkingHistory(
                                id = (item["id"] as? Double)?.toInt() ?: 0,
                                entry_time = item["entry_time"] as? String,
                                exit_time = item["exit_time"] as? String,
                                parking_duration = (item["parking_duration"] as? Double)?.toInt() ?: 0,
                                entry_image = item["entry_image"] as? String,
                                exit_image = item["exit_image"] as? String
                            )
                        }

                        callback(history, null)

                    } catch (e: Exception) {
                        callback(emptyList(), "Parse error: ${e.message}")
                    }
                }
            }
        })
    }

    data class ParkingHistory(
        val id: Int,
        val entry_time: String?,
        val exit_time: String?,
        val parking_duration: Int,
        val entry_image: String?,
        val exit_image: String?
    )

    /**
     * Cookie Jar để lưu session
     */
    private class CookieJarImpl : CookieJar {
        private val cookies = mutableListOf<Cookie>()

        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            this.cookies.clear()
            this.cookies.addAll(cookies)
            Log.d(TAG, "🍪 Saved ${cookies.size} cookies")
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            return cookies
        }
    }
}