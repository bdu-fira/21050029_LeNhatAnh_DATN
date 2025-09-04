package com.example.parkingmobiapp

import android.app.AlertDialog
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.parkingmobiapp.utils.SharedPrefsHelper

/**
 * ✅ Simple SettingsActivity - Fixed all errors
 */
class SettingsActivity : AppCompatActivity() {

    // UI Components
    private lateinit var ivBackButton: ImageView
    private lateinit var tvOwnerName: TextView
    private lateinit var tvCurrentPlateNumber: TextView
    private lateinit var viewRegistrationStatus: View
    private lateinit var tvRegistrationStatus: TextView
    private lateinit var switchEnableNotifications: Switch
    private lateinit var switchEnableVibration: Switch
    private lateinit var switchAutoRefresh: Switch
    private lateinit var llNotificationSound: LinearLayout
    private lateinit var tvCurrentSound: TextView
    private lateinit var llRefreshInterval: LinearLayout
    private lateinit var tvRefreshInterval: TextView
    private lateinit var llClearData: LinearLayout

    // Utility
    private lateinit var sharedPrefsHelper: SharedPrefsHelper

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        try {
            setContentView(R.layout.activity_settings)

            // Initialize helper
            sharedPrefsHelper = SharedPrefsHelper(this)

            // Initialize UI components
            initializeViews()

            // Setup click listeners
            setupClickListeners()

            // Load current settings
            loadCurrentSettings()

            Toast.makeText(this, "⚙️ Cài đặt đã mở", Toast.LENGTH_SHORT).show()

        } catch (e: Exception) {
            Toast.makeText(this, "❌ Lỗi khởi tạo: ${e.message}", Toast.LENGTH_LONG).show()
            e.printStackTrace()
            finish()
        }
    }

    private fun initializeViews() {
        try {
            // Header
            ivBackButton = findViewById(R.id.iv_back_button)

            // Vehicle Info (Read-only from server)
            tvOwnerName = findViewById(R.id.tv_owner_name)
            tvCurrentPlateNumber = findViewById(R.id.tv_current_plate_number)
            viewRegistrationStatus = findViewById(R.id.view_registration_status)
            tvRegistrationStatus = findViewById(R.id.tv_registration_status)

            // Notification Settings
            switchEnableNotifications = findViewById(R.id.switch_enable_notifications)
            switchEnableVibration = findViewById(R.id.switch_enable_vibration)
            llNotificationSound = findViewById(R.id.ll_notification_sound)
            tvCurrentSound = findViewById(R.id.tv_current_sound)

            // App Settings
            switchAutoRefresh = findViewById(R.id.switch_auto_refresh)
            llRefreshInterval = findViewById(R.id.ll_refresh_interval)
            tvRefreshInterval = findViewById(R.id.tv_refresh_interval)

            // About Section
            llClearData = findViewById(R.id.ll_clear_data)

        } catch (e: Exception) {
            throw Exception("Error initializing views: ${e.message}")
        }
    }

    private fun setupClickListeners() {
        try {
            // Back button
            ivBackButton.setOnClickListener {
                finish()
            }

            // Vehicle info is read-only from server - no edit functionality

            // Notification settings
            switchEnableNotifications.setOnCheckedChangeListener { _, isChecked ->
                sharedPrefsHelper.setNotificationEnabled(isChecked)
                updateNotificationStatus(isChecked)
                Toast.makeText(this, if (isChecked) "✅ Bật thông báo" else "❌ Tắt thông báo", Toast.LENGTH_SHORT).show()
            }

            switchEnableVibration.setOnCheckedChangeListener { _, isChecked ->
                sharedPrefsHelper.setVibrationEnabled(isChecked)
                Toast.makeText(this, if (isChecked) "📳 Bật rung" else "🔇 Tắt rung", Toast.LENGTH_SHORT).show()
            }

            llNotificationSound.setOnClickListener {
                showSoundSelectionDialog()
            }

            // App settings
            switchAutoRefresh.setOnCheckedChangeListener { _, isChecked ->
                sharedPrefsHelper.setAutoRefreshEnabled(isChecked)
                llRefreshInterval.visibility = if (isChecked) View.VISIBLE else View.GONE
                Toast.makeText(this, if (isChecked) "🔄 Bật tự động làm mới" else "⏸️ Tắt tự động làm mới", Toast.LENGTH_SHORT).show()
            }

            llRefreshInterval.setOnClickListener {
                showRefreshIntervalDialog()
            }

            // About section
            llClearData.setOnClickListener {
                showClearDataDialog()
            }

        } catch (e: Exception) {
            Toast.makeText(this, "❌ Lỗi setup controls: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun loadCurrentSettings() {
        try {
            // Load vehicle info
            loadVehicleInformation()

            // Load notification settings
            switchEnableNotifications.isChecked = sharedPrefsHelper.isNotificationEnabled()
            switchEnableVibration.isChecked = sharedPrefsHelper.isVibrationEnabled()

            // Load app settings
            switchAutoRefresh.isChecked = sharedPrefsHelper.isAutoRefreshEnabled()

            // Update UI based on settings
            updateNotificationStatus(sharedPrefsHelper.isNotificationEnabled())
            updateRefreshIntervalDisplay()

            // Show/hide refresh interval based on auto refresh setting
            llRefreshInterval.visibility = if (sharedPrefsHelper.isAutoRefreshEnabled()) View.VISIBLE else View.GONE

        } catch (e: Exception) {
            Toast.makeText(this, "❌ Lỗi tải cài đặt: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun loadVehicleInformation() {
        try {
            // Try to get vehicle session first (from server cache)
            val vehicleSession = sharedPrefsHelper.getVehicleSession()

            if (vehicleSession != null) {
                // Display cached vehicle information (same format as MainActivity)
                tvOwnerName.text = vehicleSession.ownerName.uppercase()
                tvCurrentPlateNumber.text = "(${vehicleSession.plateNumber})"
                updateRegistrationStatus(true, "Đã đăng ký với hệ thống")

            } else {
                // Fallback to legacy plate number
                val plateNumber = sharedPrefsHelper.getPlateNumber()
                val userName = sharedPrefsHelper.getUserFullName()

                if (plateNumber.isNotEmpty()) {
                    tvOwnerName.text = if (userName.isNotEmpty()) userName.uppercase() else "NGƯỜI DÙNG"
                    tvCurrentPlateNumber.text = "($plateNumber)"
                    updateRegistrationStatus(true, "Đã lưu biển số")
                } else {
                    tvOwnerName.text = "CHƯA ĐĂNG KÝ"
                    tvCurrentPlateNumber.text = ""
                    updateRegistrationStatus(false, "Chưa đăng ký biển số")
                }
            }

        } catch (e: Exception) {
            Toast.makeText(this, "❌ Lỗi tải thông tin xe: ${e.message}", Toast.LENGTH_SHORT).show()
            tvOwnerName.text = "LỖI TẢI DỮ LIỆU"
            tvCurrentPlateNumber.text = ""
            updateRegistrationStatus(false, "Lỗi tải thông tin")
        }
    }

    private fun updateRegistrationStatus(isRegistered: Boolean, statusText: String) {
        try {
            val colorResource = if (isRegistered) R.color.success_color else R.color.error_color
            val color = ContextCompat.getColor(this, colorResource)

            viewRegistrationStatus.backgroundTintList = ContextCompat.getColorStateList(this, colorResource)
            tvRegistrationStatus.text = statusText
            tvRegistrationStatus.setTextColor(color)

        } catch (e: Exception) {
            Toast.makeText(this, "❌ Lỗi cập nhật trạng thái: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateNotificationStatus(enabled: Boolean) {
        try {
            // Enable/disable dependent notification settings
            switchEnableVibration.isEnabled = enabled
            llNotificationSound.isEnabled = enabled

            // Update UI appearance
            val alpha = if (enabled) 1.0f else 0.5f
            switchEnableVibration.alpha = alpha
            llNotificationSound.alpha = alpha

        } catch (e: Exception) {
            Toast.makeText(this, "❌ Lỗi cập nhật trạng thái thông báo", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateRefreshIntervalDisplay() {
        try {
            val intervalSeconds = sharedPrefsHelper.getRefreshInterval()
            val displayText = when {
                intervalSeconds < 60 -> "$intervalSeconds giây"
                intervalSeconds < 3600 -> "${intervalSeconds / 60} phút"
                else -> "${intervalSeconds / 3600} giờ"
            }
            tvRefreshInterval.text = displayText

        } catch (e: Exception) {
            tvRefreshInterval.text = "30 giây"
        }
    }

    // ========== DIALOG METHODS ==========

    // Removed showChangePlateDialog() - Vehicle info is read-only from server

    private fun showSoundSelectionDialog() {
        try {
            val soundOptions = arrayOf(
                "🔔 Mặc định",
                "📢 Chuông 1",
                "📯 Chuông 2",
                "🎵 Nhạc 1",
                "🎶 Nhạc 2",
                "🔇 Không âm thanh"
            )

            var selectedIndex = 0 // Default selection

            AlertDialog.Builder(this)
                .setTitle("🔊 Chọn âm thanh thông báo")
                .setSingleChoiceItems(soundOptions, selectedIndex) { _, which ->
                    selectedIndex = which
                }
                .setPositiveButton("💾 Lưu") { _, _ ->
                    val selectedSound = soundOptions[selectedIndex]
                    tvCurrentSound.text = selectedSound
                    sharedPrefsHelper.setSelectedSoundUri(selectedSound)
                    Toast.makeText(this, "✅ Đã lưu âm thanh: $selectedSound", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("❌ Hủy", null)
                .show()

        } catch (e: Exception) {
            Toast.makeText(this, "❌ Lỗi chọn âm thanh", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showRefreshIntervalDialog() {
        try {
            val intervalOptions = arrayOf(
                "15 giây",
                "30 giây",
                "1 phút",
                "2 phút",
                "5 phút"
            )

            val intervalValues = arrayOf(15, 30, 60, 120, 300)
            val currentInterval = sharedPrefsHelper.getRefreshInterval()

            // Find current selection
            var selectedIndex = intervalValues.indexOf(currentInterval)
            if (selectedIndex == -1) selectedIndex = 1 // Default to 30 seconds

            AlertDialog.Builder(this)
                .setTitle("⏱️ Tần suất cập nhật")
                .setSingleChoiceItems(intervalOptions, selectedIndex) { _, which ->
                    selectedIndex = which
                }
                .setPositiveButton("💾 Lưu") { _, _ ->
                    val newInterval = intervalValues[selectedIndex]
                    sharedPrefsHelper.setRefreshInterval(newInterval)
                    updateRefreshIntervalDisplay()
                    Toast.makeText(this, "✅ Đã lưu tần suất: ${intervalOptions[selectedIndex]}", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("❌ Hủy", null)
                .show()

        } catch (e: Exception) {
            Toast.makeText(this, "❌ Lỗi chọn tần suất cập nhật", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showClearDataDialog() {
        try {
            AlertDialog.Builder(this)
                .setTitle("🗑️ Xóa dữ liệu ứng dụng")
                .setMessage("⚠️ Hành động này sẽ xóa:\n\n• Cache dữ liệu đỗ xe\n• Lịch sử thông báo\n• Cài đặt tùy chỉnh\n\nThông tin tài khoản sẽ được giữ lại.")
                .setIcon(android.R.drawable.ic_dialog_alert)
                .setPositiveButton("🗑️ Xóa") { _, _ ->
                    clearAppData()
                }
                .setNegativeButton("❌ Hủy", null)
                .show()

        } catch (e: Exception) {
            Toast.makeText(this, "❌ Lỗi hiển thị dialog xóa dữ liệu", Toast.LENGTH_SHORT).show()
        }
    }

    // ========== HELPER METHODS ==========

    // Removed validatePlateNumber() and savePlateNumber() - Vehicle info is read-only

    private fun clearAppData() {
        try {
            // Clear cache but keep user login
            sharedPrefsHelper.clearCache()

            Toast.makeText(this, "✅ Đã xóa dữ liệu ứng dụng", Toast.LENGTH_SHORT).show()

            // Optionally restart the activity to reflect changes
            recreate()

        } catch (e: Exception) {
            Toast.makeText(this, "❌ Lỗi xóa dữ liệu: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    // ========== LIFECYCLE METHODS ==========

    override fun onBackPressed() {
        super.onBackPressed()
        finish()
    }

    override fun onResume() {
        super.onResume()
        // Refresh vehicle information in case it was updated
        loadVehicleInformation()
    }
}