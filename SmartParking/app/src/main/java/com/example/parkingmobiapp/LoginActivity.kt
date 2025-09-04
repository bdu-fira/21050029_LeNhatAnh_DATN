package com.example.parkingmobiapp

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.parkingmobiapp.service.VehicleAuthenticationService
import com.example.parkingmobiapp.utils.SharedPrefsHelper
import com.example.parkingmobiapp.utils.ValidationUtils

/**
 * LoginActivity - Đăng nhập cho chủ xe đã đăng ký
 * Chức năng:
 * - Đăng nhập bằng biển số xe + số điện thoại
 * - Xác thực với database server
 * - Lưu session thông tin xe
 */
class LoginActivity : AppCompatActivity() {

    private lateinit var etPlateNumber: EditText
    private lateinit var etOwnerPhone: EditText
    private lateinit var btnLogin: Button
    private lateinit var tvServerConfig: TextView
    private lateinit var tvForgotPassword: TextView

    private lateinit var sharedPrefsHelper: SharedPrefsHelper
    private lateinit var vehicleAuthService: VehicleAuthenticationService

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_vehicle_login)

        initializeComponents()
        setupClickListeners()

        // Kiểm tra nếu đã có session xe hợp lệ
        if (sharedPrefsHelper.hasActiveVehicleSession()) {
            navigateToMain()
        }
    }

    private fun initializeComponents() {
        etPlateNumber = findViewById(R.id.et_plate_number)
        etOwnerPhone = findViewById(R.id.et_owner_phone)
        btnLogin = findViewById(R.id.btn_login)
        tvServerConfig = findViewById(R.id.tv_server_config)
        tvForgotPassword = findViewById(R.id.tv_forgot_password)

        sharedPrefsHelper = SharedPrefsHelper(this)
        vehicleAuthService = VehicleAuthenticationService(sharedPrefsHelper)
    }

    private fun setupClickListeners() {
        btnLogin.setOnClickListener {
            handleVehicleLogin()
        }

        tvServerConfig.setOnClickListener {
            showServerConfigDialog()
        }

        tvForgotPassword.setOnClickListener {
            showForgotPasswordDialog()
        }
    }

    private fun handleVehicleLogin() {
        val plateNumber = etPlateNumber.text.toString().trim()
        val ownerPhone = etOwnerPhone.text.toString().trim()

        // Validate input
        if (!validateInput(plateNumber, ownerPhone)) {
            return
        }

        // Hiển thị loading
        setLoginLoading(true)

        // Đăng nhập với vehicle service
        vehicleAuthService.loginWithVehicle(plateNumber, ownerPhone,
            object : VehicleAuthenticationService.VehicleLoginCallback {
                override fun onLoginSuccess(vehicleInfo: VehicleAuthenticationService.VehicleInfo) {
                    runOnUiThread {
                        setLoginLoading(false)

                        // Cũng lưu plate number vào SharedPrefs cho compatibility
                        sharedPrefsHelper.savePlateNumber(vehicleInfo.plate_number)

                        showToast("✅ Đăng nhập thành công!\nChào mừng ${vehicleInfo.owner_name}")

                        navigateToMain()
                    }
                }

                override fun onLoginError(error: String) {
                    runOnUiThread {
                        setLoginLoading(false)

                        val errorMsg = when {
                            error.contains("không chính xác") ->
                                "❌ Biển số hoặc số điện thoại không đúng"
                            error.contains("không tìm thấy") ->
                                "❌ Xe chưa được đăng ký trong hệ thống"
                            error.contains("vô hiệu hóa") ->
                                "❌ Xe đã bị vô hiệu hóa. Liên hệ quản lý"
                            error.contains("kết nối") ->
                                "❌ Không thể kết nối server. Kiểm tra cài đặt"
                            else -> "❌ $error"
                        }

                        showToast(errorMsg)

                        // Nếu lỗi server, hiện dialog cấu hình
                        if (error.contains("server") || error.contains("kết nối")) {
                            showServerConfigDialog()
                        }
                    }
                }
            })
    }

    private fun validateInput(plateNumber: String, ownerPhone: String): Boolean {
        var isValid = true

        // Validate plate number
        if (plateNumber.isEmpty()) {
            etPlateNumber.error = "Vui lòng nhập biển số xe"
            isValid = false
        } else if (!ValidationUtils.isValidPlateNumber(plateNumber)) {
            etPlateNumber.error = "Biển số xe không đúng định dạng"
            isValid = false
        }

        // Validate phone number
        if (ownerPhone.isEmpty()) {
            etOwnerPhone.error = "Vui lòng nhập số điện thoại"
            isValid = false
        } else if (!ValidationUtils.isValidPhone(ownerPhone)) {
            etOwnerPhone.error = "Số điện thoại không hợp lệ"
            isValid = false
        }

        return isValid
    }

    private fun setLoginLoading(loading: Boolean) {
        btnLogin.isEnabled = !loading
        btnLogin.text = if (loading) "Đang xác thực..." else "Đăng nhập"
        etPlateNumber.isEnabled = !loading
        etOwnerPhone.isEnabled = !loading
    }

    private fun showServerConfigDialog() {
        val dialogBuilder = androidx.appcompat.app.AlertDialog.Builder(this)
        val dialogView = layoutInflater.inflate(R.layout.dialog_server_config, null)

        val etServerUrl = dialogView.findViewById<EditText>(R.id.et_server_url)
        etServerUrl.setText(sharedPrefsHelper.getServerUrl())

        dialogBuilder
            .setView(dialogView)
            .setTitle("🔧 Cấu hình Server")
            .setMessage("Nhập địa chỉ server parking system:\n\n" +
                    "💡 Liên hệ quản lý để lấy địa chỉ server chính xác")
            .setPositiveButton("Lưu") { _, _ ->
                val serverUrl = etServerUrl.text.toString().trim()
                if (serverUrl.isNotEmpty() && sharedPrefsHelper.validateServerUrl(serverUrl)) {
                    sharedPrefsHelper.saveServerUrl(serverUrl)
                    showToast("✅ Đã lưu cấu hình server")
                } else {
                    showToast("❌ URL server không hợp lệ")
                }
            }
            .setNegativeButton("Hủy", null)
            .setNeutralButton("Mặc định") { _, _ ->
                etServerUrl.setText("http://192.168.1.6:5000")
            }
            .show()
    }

    private fun showForgotPasswordDialog() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("🔑 Quên thông tin đăng nhập?")
            .setMessage("Để lấy lại thông tin đăng nhập:\n\n" +
                    "1️⃣ Liên hệ quản lý bãi xe\n" +
                    "2️⃣ Cung cấp thông tin xe và giấy tờ\n" +
                    "3️⃣ Quản lý sẽ xác nhận và cung cấp thông tin\n\n" +
                    "📞 Hotline: 1900-xxxx\n" +
                    "📧 Email: support@parking.com")
            .setPositiveButton("Đã hiểu", null)
            .show()
    }

    private fun navigateToMain() {
        val intent = Intent(this, MainActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        finish()
    }

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    override fun onResume() {
        super.onResume()

        // Hiển thị trạng thái server
        if (!sharedPrefsHelper.isServerConfigured()) {
            tvServerConfig.text = "⚠️ Chưa cấu hình server"
            tvServerConfig.setTextColor(getColor(android.R.color.holo_red_dark))
        } else {
            tvServerConfig.text = "✅ Server: ${sharedPrefsHelper.getServerUrl()}"
            tvServerConfig.setTextColor(getColor(android.R.color.holo_green_dark))
        }
    }
}