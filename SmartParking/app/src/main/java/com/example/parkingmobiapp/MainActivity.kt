package com.example.parkingmobiapp

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import android.widget.ProgressBar
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.parkingmobiapp.models.NotificationItem
import com.example.parkingmobiapp.models.ParkingStatus
import com.example.parkingmobiapp.service.ParkingStatusService
import com.example.parkingmobiapp.services.PushNotificationService
import com.example.parkingmobiapp.services.WebSocketManager
import com.example.parkingmobiapp.utils.SharedPrefsHelper
import java.text.SimpleDateFormat
import java.util.*
import android.app.AlertDialog
import android.util.Log
import android.os.Handler
import android.os.Looper
import android.animation.ObjectAnimator
import android.content.Context
import android.widget.*
import com.example.parkingmobiapp.models.VehicleNotification
import org.json.JSONArray
import org.json.JSONObject
import kotlin.apply
import kotlin.text.clear

/**
 * ✅ FIXED MainActivity with Enhanced WebSocket Integration
 */
class MainActivity : AppCompatActivity() {

    private val recentNotifications = mutableListOf<NotificationItem>()
    private lateinit var notificationsContainer: LinearLayout
    private lateinit var noNotificationsView: LinearLayout

    companion object {
        private const val TAG = "MainActivity"
        private const val PERMISSION_REQUEST_CODE = 1001
        private const val MAX_VISIBLE_NOTIFICATIONS = 3
        private const val WEBSOCKET_RECONNECT_DELAY = 5000L
    }

    // ========== UI COMPONENTS ==========
    private lateinit var tvUserPlate: TextView
    private lateinit var tvCurrentStatus: TextView
    private lateinit var tvAvailableSpaces: TextView
    private lateinit var tvLastUpdate: TextView
    private lateinit var ivStatusIcon: ImageView
    private lateinit var llNotificationsContainer: LinearLayout
    private lateinit var llNoNotifications: LinearLayout
    private lateinit var viewConnectionIndicator: View
    private lateinit var tvConnectionStatus: TextView

    // Parking Status UI Components
    private lateinit var tvParkingTotal: TextView
    private lateinit var tvParkingAvailable: TextView
    private lateinit var tvParkingOccupied: TextView
    private lateinit var tvParkingPercentage: TextView
    private lateinit var tvParkingStatusMessage: TextView
    private lateinit var viewParkingIndicator: View
    private lateinit var btnRefreshParking: Button
    private lateinit var llParkingInfo: LinearLayout
    private lateinit var progressParkingOccupancy: ProgressBar

    // Optional WebSocket UI (safe initialization)
    private var tvWebSocketStatus: TextView? = null
    private var btnTestWebSocket: Button? = null
    private var btnReconnectWebSocket: Button? = null

    // ========== SERVICES & UTILITIES ==========
    private lateinit var sharedPrefsHelper: SharedPrefsHelper
    private lateinit var notificationService: PushNotificationService
    private lateinit var parkingStatusService: ParkingStatusService
    private lateinit var webSocketManager: WebSocketManager

    // ========== STATE VARIABLES ==========
    private var isParkingServiceActive = false
    private var currentParkingStatus = ParkingStatus.UNKNOWN
    private val notificationsList = mutableListOf<NotificationItem>()
    private var isWebSocketConnected = false
    private var lastDataUpdateTime = 0L

    // Auto-refresh handler
    private val refreshHandler = Handler(Looper.getMainLooper())
    private var refreshRunnable: Runnable? = null

    // ========== LIFECYCLE METHODS ==========

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // BƯỚC 1: Set layout TRƯỚC TIÊN
        setContentView(R.layout.activity_main)

        // BƯỚC 2: Khởi tạo components SAU KHI có layout
        try {
            initializeComponents()
            initializeServices() // Tách riêng
            setupClickListeners()
            checkPermissions()
            initializeWebSocket()
            setupWebSocketCallbacks()
            loadNotificationsFromStorage()
            checkUserAuthentication()

            if (sharedPrefsHelper.isUserLoggedIn()) {
                loadUserData()
                startRealTimeUpdates()
            } else {
                navigateToLogin()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in onCreate", e)
            showToast("Lỗi khởi tạo: ${e.message}")
        }
    }

    private fun initializeServices() {
        sharedPrefsHelper = SharedPrefsHelper(this)
        notificationService = PushNotificationService()
        parkingStatusService = ParkingStatusService(sharedPrefsHelper)
    }

    override fun onResume() {
        super.onResume()
        Log.d(TAG, "🔄 MainActivity onResume")

        try {
            if (::webSocketManager.isInitialized) {
                Log.d(TAG, "🔌 Resuming WebSocket connection...")

                if (!webSocketManager.isConnected()) {
                    Log.d(TAG, "⚡ WebSocket not connected, reconnecting...")
                    webSocketManager.connect()
                } else {
                    Log.d(TAG, "✅ WebSocket already connected")
                }

                webSocketManager.sendHeartbeat()
                webSocketManager.reportAppState("foreground")
                webSocketManager.requestParkingStatus()
            } else {
                Log.w(TAG, "⚠️ WebSocket manager not initialized")
            }

            if (sharedPrefsHelper.isUserLoggedIn() && !isParkingServiceActive) {
                Log.d(TAG, "⚡ Resuming parking updates...")
                startRealTimeUpdates()
            }

        } catch (e: Exception) {
            Log.e(TAG, "❌ Error in onResume", e)
        }
    }

    override fun onPause() {
        super.onPause()
        Log.d(TAG, "🔄 MainActivity onPause")

        try {
            if (::webSocketManager.isInitialized) {
                webSocketManager.reportAppState("background")
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error in onPause", e)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "🔄 MainActivity onDestroy")

        try {
            cleanupWebSocket()
            stopParkingStatusUpdates()
            refreshRunnable?.let { refreshHandler.removeCallbacks(it) }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error in onDestroy", e)
        }
    }

    private fun createNotificationView(notification: NotificationItem): View {
        // Sử dụng LayoutInflater từ context
        val inflater = LayoutInflater.from(this)
        val view = inflater.inflate(R.layout.item_notification_small, null)

        val iconView = view.findViewById<ImageView>(R.id.iv_notification_icon)
        val titleView = view.findViewById<TextView>(R.id.tv_notification_title)
        val messageView = view.findViewById<TextView>(R.id.tv_notification_message)
        val timeView = view.findViewById<TextView>(R.id.tv_notification_time)
        val unreadIndicator = view.findViewById<View>(R.id.view_unread_indicator)

        // Set icon based on type
        when (notification.type) {
            NotificationItem.Type.VEHICLE_ENTERED -> {
                iconView.setImageResource(R.drawable.ic_notification) // Thay bằng icon thực tế
                iconView.setColorFilter(ContextCompat.getColor(this, R.color.success_color))
            }
            NotificationItem.Type.VEHICLE_EXITED -> {
                iconView.setImageResource(R.drawable.ic_notification) // Thay bằng icon thực tế
                iconView.setColorFilter(ContextCompat.getColor(this, R.color.info_color))
            }
            else -> {
                iconView.setImageResource(R.drawable.ic_notification)
            }
        }

        // Set text values
        titleView.text = notification.title
        messageView.text = notification.message
        timeView.text = notification.getFormattedTime()
        unreadIndicator.visibility = if (notification.isRead) View.GONE else View.VISIBLE

        // Add click listener
        view.setOnClickListener {
            notification.isRead = true
            unreadIndicator.visibility = View.GONE
            // Có thể mở chi tiết nếu cần
            showToast("Đã đọc: ${notification.title}")
        }

        return view
    }

    // ========== INITIALIZATION METHODS ==========

    private fun initializeComponents() {
        Log.d(TAG, "🔧 Initializing UI components...")

        try {
            // Required UI components - CHỈ KHỞI TẠO MỘT LẦN
            tvUserPlate = findViewById(R.id.tv_user_plate)
            tvCurrentStatus = findViewById(R.id.tv_current_status)
            tvAvailableSpaces = findViewById(R.id.tv_available_spaces)
            tvLastUpdate = findViewById(R.id.tv_last_update)
            ivStatusIcon = findViewById(R.id.iv_status_icon)
            llNotificationsContainer = findViewById(R.id.ll_notifications_container)
            llNoNotifications = findViewById(R.id.ll_no_notifications)
            viewConnectionIndicator = findViewById(R.id.view_connection_indicator)
            tvConnectionStatus = findViewById(R.id.tv_connection_status)

            // Parking Status UI
            tvParkingTotal = findViewById(R.id.tv_parking_total)
            tvParkingAvailable = findViewById(R.id.tv_parking_available)
            tvParkingOccupied = findViewById(R.id.tv_parking_occupied)
            tvParkingPercentage = findViewById(R.id.tv_parking_percentage)
            tvParkingStatusMessage = findViewById(R.id.tv_parking_status_message)
            viewParkingIndicator = findViewById(R.id.view_parking_indicator)
            btnRefreshParking = findViewById(R.id.btn_refresh_parking)
            llParkingInfo = findViewById(R.id.ll_parking_info)
            progressParkingOccupancy = findViewById(R.id.progress_parking_occupancy)

            Log.d(TAG, "✅ Required UI components initialized")

            initializeOptionalWebSocketUI()

            // Initialize services
            sharedPrefsHelper = SharedPrefsHelper(this)
            notificationService = PushNotificationService()
            parkingStatusService = ParkingStatusService(sharedPrefsHelper)

            Log.d(TAG, "✅ Services initialized successfully")

        } catch (e: Exception) {
            Log.e(TAG, "❌ Error initializing UI components", e)
            showToast("Lỗi khởi tạo giao diện: ${e.message}")
        }
    }

    private fun initializeOptionalWebSocketUI() {
        try {
            val webSocketStatusId = resources.getIdentifier("tv_websocket_status", "id", packageName)
            if (webSocketStatusId != 0) {
                tvWebSocketStatus = findViewById(webSocketStatusId)
                Log.d(TAG, "✅ WebSocket status TextView initialized")
            } else {
                Log.d(TAG, "ℹ️ WebSocket status TextView not found in layout (optional)")
            }

            val testWebSocketId = resources.getIdentifier("btn_test_websocket", "id", packageName)
            if (testWebSocketId != 0) {
                btnTestWebSocket = findViewById(testWebSocketId)
                Log.d(TAG, "✅ Test WebSocket button initialized")
            } else {
                Log.d(TAG, "ℹ️ Test WebSocket button not found in layout (optional)")
            }

            val reconnectWebSocketId = resources.getIdentifier("btn_reconnect_websocket", "id", packageName)
            if (reconnectWebSocketId != 0) {
                btnReconnectWebSocket = findViewById(reconnectWebSocketId)
                Log.d(TAG, "✅ Reconnect WebSocket button initialized")
            } else {
                Log.d(TAG, "ℹ️ Reconnect WebSocket button not found in layout (optional)")
            }

        } catch (e: Exception) {
            Log.w(TAG, "⚠️ Error initializing optional WebSocket UI components", e)
            tvWebSocketStatus = null
            btnTestWebSocket = null
            btnReconnectWebSocket = null
        }
    }

    private fun initializeWebSocket() {
        try {
            Log.d(TAG, "🔌 Initializing WebSocket Manager...")

            if (!sharedPrefsHelper.isServerConfigured()) {
                Log.w(TAG, "⚠️ Server not configured, using default settings")
                if (sharedPrefsHelper.getServerUrl().isEmpty()) {
                    sharedPrefsHelper.setServerUrl("http://192.168.1.6:5000")
                    Log.d(TAG, "🔧 Set default server URL: http://192.168.1.6:5000")
                }
            }

            webSocketManager = WebSocketManager.getInstance(this, sharedPrefsHelper)
            webSocketManager.initializeNotifications()

            Log.d(TAG, "✅ WebSocket Manager initialized successfully")
            Log.d(TAG, "🌐 Server URL: ${sharedPrefsHelper.getServerUrl()}")

        } catch (e: Exception) {
            Log.e(TAG, "❌ Error initializing WebSocket Manager", e)
            showToast("⚠️ Lỗi khởi tạo kết nối real-time: ${e.message}")
        }
    }

    private fun setupClickListeners() {
        try {
            Log.d(TAG, "🔧 Setting up click listeners...")

            findViewById<ImageView>(R.id.iv_settings).setOnClickListener {
                showSettingsMenu()
            }

            findViewById<LinearLayout>(R.id.ll_parking_history).setOnClickListener {
                showParkingHistory()
            }

            findViewById<LinearLayout>(R.id.ll_report_issue).setOnClickListener {
                showReportIssue()
            }

            findViewById<TextView>(R.id.tv_view_all_notifications).setOnClickListener {
                openNotificationHistory()
            }

            findViewById<LinearLayout>(R.id.ll_settings).setOnClickListener {
                showSettingsMenu()
            }

            btnRefreshParking.setOnClickListener {
                refreshParkingData()
            }

            llParkingInfo.setOnClickListener {
                showParkingDetails()
            }

            Log.d(TAG, "✅ Required click listeners set up")

            setupOptionalWebSocketClickListeners()

            Log.d(TAG, "✅ All click listeners setup completed")

        } catch (e: Exception) {
            Log.e(TAG, "❌ Error setting up click listeners", e)
        }
    }

    private fun setupOptionalWebSocketClickListeners() {
        try {
            btnTestWebSocket?.setOnClickListener {
                testWebSocketConnection()
            }
            if (btnTestWebSocket != null) {
                Log.d(TAG, "✅ Test WebSocket button listener set")
            }

            btnReconnectWebSocket?.setOnClickListener {
                reconnectWebSocket()
            }
            if (btnReconnectWebSocket != null) {
                Log.d(TAG, "✅ Reconnect WebSocket button listener set")
            }

        } catch (e: Exception) {
            Log.w(TAG, "⚠️ Error setting up optional WebSocket click listeners", e)
        }
    }

    private fun showReportIssueDialog() {
        try {
            Log.d(TAG, "⚠️ Opening report issue dialog...")

            val options = arrayOf(
                "🚗 Xe bị chặn lối ra",
                "🔧 Thiết bị bị hỏng",
                "🧹 Khu vực bẩn",
                "🚫 Vị trí bị chiếm dụng",
                "📱 Khác..."
            )

            AlertDialog.Builder(this)
                .setTitle("⚠️ Báo cáo sự cố")
                .setItems(options) { _, which ->
                    val selectedIssue = options[which]
                    handleReportIssue(selectedIssue)
                }
                .setNegativeButton("Hủy", null)
                .show()

        } catch (e: Exception) {
            Log.e(TAG, "Error showing report dialog", e)
            showToast("❌ Lỗi khi mở form báo cáo")
        }
    }

    private fun handleReportIssue(issue: String) {
        try {
            Log.d(TAG, "📝 Reporting issue: $issue")

            // TODO: Send report to server
            // reportIssueToServer(issue)

            showToast("✅ Đã gửi báo cáo: $issue")

        } catch (e: Exception) {
            Log.e(TAG, "Error handling report", e)
            showToast("❌ Lỗi khi gửi báo cáo")
        }
    }

    // ========== WEBSOCKET METHODS ==========

    private fun startRealTimeUpdates() {
        try {
            Log.d(TAG, "🚀 Starting real-time updates...")

            if (!::webSocketManager.isInitialized) {
                Log.w(TAG, "⚠️ WebSocket manager not initialized, initializing now...")
                initializeWebSocket()
            }

            setupWebSocketCallbacks()
            connectWebSocket()
            startHttpPollingBackup()

        } catch (e: Exception) {
            Log.e(TAG, "❌ Error starting real-time updates", e)
            startHttpPollingBackup()
            showToast("⚠️ Chỉ sử dụng cập nhật HTTP do lỗi WebSocket")
        }
    }

    private fun addNotificationToRecentList(vehicleNotification: VehicleNotification) {
        try {
            // Tạo unique ID dựa trên plate number và action
            val uniqueId = "${vehicleNotification.plateNumber}_${vehicleNotification.action}_${System.currentTimeMillis() / 10000}".hashCode()

            // Convert VehicleNotification to NotificationItem
            val notificationItem = NotificationItem(
                id = uniqueId, // Sử dụng unique ID
                title = vehicleNotification.title,
                message = buildNotificationMessage(vehicleNotification),
                timestamp = System.currentTimeMillis(),
                type = if (vehicleNotification.action == "entry")
                    NotificationItem.Type.VEHICLE_ENTERED
                else
                    NotificationItem.Type.VEHICLE_EXITED,
                priority = NotificationItem.Priority.HIGH,
                isRead = false,
                isImportant = true,
                data = mapOf(
                    "plate_number" to vehicleNotification.plateNumber,
                    "owner_name" to (vehicleNotification.ownerName ?: ""),
                    "action" to vehicleNotification.action,
                    "parking_duration" to (vehicleNotification.parkingDuration?.toString() ?: ""),
                    "timestamp" to vehicleNotification.timestamp
                )
            )

            // Kiểm tra duplicate trước khi thêm
            val isDuplicate = recentNotifications.any { existing ->
                existing.data["plate_number"] == notificationItem.data["plate_number"] &&
                        existing.data["action"] == notificationItem.data["action"] &&
                        Math.abs(existing.timestamp - notificationItem.timestamp) < 10000 // 10 giây
            }

            if (!isDuplicate) {
                // Thêm vào đầu danh sách
                recentNotifications.add(0, notificationItem)

                // Giữ tối đa 10 notifications gần nhất (tăng từ 5 lên 10)
                if (recentNotifications.size > 10) {
                    recentNotifications.removeAt(recentNotifications.size - 1)
                }

                // Cập nhật UI
                updateNotificationsUI()

                Log.d(TAG, "✅ Added notification to recent list: ${notificationItem.title}")
            } else {
                Log.d(TAG, "⚠️ Notification already exists, skipping duplicate")
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error adding notification to list", e)
        }
    }

    private fun buildNotificationMessage(notification: VehicleNotification): String {
        return buildString {
            append("Xe ${notification.plateNumber}")

            if (!notification.ownerName.isNullOrEmpty()) {
                append(" của ${notification.ownerName}")
            }

            append(" đã ${if (notification.action == "entry") "vào" else "ra khỏi"} bãi đỗ")

            if (notification.action == "exit" && notification.parkingDuration != null) {
                val hours = notification.parkingDuration / 60
                val minutes = notification.parkingDuration % 60
                append(" sau ")
                if (hours > 0) {
                    append("${hours}h ${minutes}p")
                } else {
                    append("${minutes} phút")
                }
            }

            append(" lúc ${notification.timestamp}")
        }
    }

    private fun setupWebSocketCallbacks() {
        try {
            Log.d(TAG, "🔡 Setting up WebSocket callbacks...")

            webSocketManager.registerParkingStatusCallback("main_activity") { status ->
                Log.d(TAG, "📊 ✅ Received parking status update via WebSocket")
                Log.d(TAG, "📈 Data: ${status.parking_status.available}/${status.parking_status.total} available")

                runOnUiThread {
                    try {
                        updateParkingUIWithRealTimeData(status)
                    } catch (e: Exception) {
                        Log.e(TAG, "❌ Error updating UI with real-time data", e)
                    }
                }
            }

            // FIXED: Chỉ đăng ký vehicle callback một lần và xử lý đúng cách
            webSocketManager.registerVehicleCallback("main_vehicle") { notification ->
                Log.d(TAG, "🚗 Received vehicle notification: ${notification.title}")

                runOnUiThread {
                    // Kiểm tra xem notification này đã tồn tại chưa
                    val existingNotification = recentNotifications.find {
                        it.data["plate_number"] == notification.plateNumber &&
                                Math.abs(it.timestamp - System.currentTimeMillis()) < 5000 // Trong vòng 5 giây
                    }

                    if (existingNotification == null) {
                        // Chỉ thêm nếu chưa có notification tương tự gần đây
                        addNotificationToRecentList(notification)
                        saveNotificationToStorage(notification)
                    } else {
                        Log.d(TAG, "⚠️ Duplicate notification detected, skipping...")
                    }
                }
            }

            webSocketManager.registerConnectionCallback("main_activity") { connected ->
                Log.d(TAG, "🔌 ✅ Connection status changed: $connected")

                runOnUiThread {
                    try {
                        updateWebSocketConnectionStatus(connected)
                    } catch (e: Exception) {
                        Log.e(TAG, "❌ Error updating connection status", e)
                    }
                }
            }

            // REMOVED: Không đăng ký notification callback chung để tránh duplicate
            // webSocketManager.registerNotificationCallback() đã bị xóa

            Log.d(TAG, "✅ WebSocket callbacks registered successfully")

        } catch (e: Exception) {
            Log.e(TAG, "❌ Error setting up WebSocket callbacks", e)
        }
    }


    private fun connectWebSocket() {
        try {
            if (!sharedPrefsHelper.isServerConfigured()) {
                Log.w(TAG, "⚠️ Server not configured for WebSocket")
                showServerConfigDialog()
                return
            }

            val serverUrl = sharedPrefsHelper.getServerUrl()
            Log.d(TAG, "🔌 Connecting WebSocket to: $serverUrl")

            webSocketManager.connect()
            updateWebSocketConnectionStatus(false, "Đang kết nối...")

        } catch (e: Exception) {
            Log.e(TAG, "❌ Error connecting WebSocket", e)
            updateWebSocketConnectionStatus(false, "Lỗi kết nối")
        }
    }

    private fun updateParkingUIWithRealTimeData(status: ParkingStatusService.ParkingStatusData) {
        try {
            val parkingInfo = status.parking_status
            lastDataUpdateTime = System.currentTimeMillis()

            Log.d(TAG, "📊 ✅ Updating UI with REAL-TIME WebSocket data")
            Log.d(TAG, "📈 Available: ${parkingInfo.available}/${parkingInfo.total}")
            Log.d(TAG, "📊 Percentage: ${parkingInfo.percentage_full}%")

            tvParkingTotal.text = parkingInfo.total.toString()
            tvParkingAvailable.text = parkingInfo.available.toString()
            tvParkingOccupied.text = parkingInfo.occupied.toString()
            tvParkingPercentage.text = "${parkingInfo.percentage_full.toInt()}%"

            tvParkingStatusMessage.text = "🔴 LIVE: ${status.status_message}"

            ObjectAnimator.ofInt(progressParkingOccupancy, "progress", parkingInfo.percentage_full.toInt())
                .setDuration(1000)
                .start()

            val colorResource = when {
                parkingInfo.available == 0 -> R.color.parking_full
                parkingInfo.available <= 3 -> R.color.parking_warning
                parkingInfo.available <= 8 -> R.color.parking_occupied
                else -> R.color.parking_available
            }

            viewParkingIndicator.setBackgroundTintList(
                ContextCompat.getColorStateList(this, colorResource)
            )

            tvAvailableSpaces.text = if (parkingInfo.available > 0) {
                "${parkingInfo.available} chỗ trống"
            } else {
                "Hết chỗ đỗ"
            }

            val currentTime = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
            tvLastUpdate.text = "🔴 LIVE: $currentTime"

            cacheParkingData(status)
            llParkingInfo.visibility = View.VISIBLE

            Log.d(TAG, "✅ Real-time UI update completed successfully")

        } catch (e: Exception) {
            Log.e(TAG, "❌ Error updating real-time UI", e)
            showToast("Lỗi cập nhật giao diện real-time")
        }
    }

    private fun updateWebSocketConnectionStatus(connected: Boolean, customMessage: String? = null) {
        try {
            isWebSocketConnected = connected
            Log.d(TAG, "🔌 Updating connection status: connected=$connected, message='$customMessage'")

            if (connected) {
                viewConnectionIndicator.setBackgroundTintList(
                    ContextCompat.getColorStateList(this, R.color.success_color)
                )
                tvConnectionStatus.text = customMessage ?: "🔴 LIVE"
                tvConnectionStatus.setTextColor(ContextCompat.getColor(this, R.color.success_color))

                tvWebSocketStatus?.let { statusView ->
                    statusView.text = "WebSocket: Connected"
                    statusView.setTextColor(ContextCompat.getColor(this, R.color.success_color))
                }

            } else {
                viewConnectionIndicator.setBackgroundTintList(
                    ContextCompat.getColorStateList(this, R.color.warning_color)
                )
                tvConnectionStatus.text = customMessage ?: "OFFLINE"
                tvConnectionStatus.setTextColor(ContextCompat.getColor(this, R.color.warning_color))

                tvWebSocketStatus?.let { statusView ->
                    statusView.text = customMessage ?: "WebSocket: Disconnected"
                    statusView.setTextColor(ContextCompat.getColor(this, R.color.error_color))
                }

                if (customMessage == null) {
                    scheduleWebSocketReconnect()
                }
            }

            Log.d(TAG, "✅ WebSocket connection status updated: $connected")

        } catch (e: Exception) {
            Log.e(TAG, "❌ Error updating connection status", e)
        }
    }

    private fun handleRealTimeNotification(notification: NotificationItem) {
        try {
            Log.d(TAG, "📱 ✅ Handling real-time notification: ${notification.title}")

            addNotificationToUI(notification)

            if (notification.isImportant) {
                showToast("📱 ${notification.title}")
            }

            when (notification.type) {
                NotificationItem.Type.PARKING_FULL -> {
                    showParkingFullAlert()
                }
                NotificationItem.Type.SPACE_AVAILABLE -> {
                    showSpaceAvailableAlert()
                }
                NotificationItem.Type.URGENT_ALERT -> {
                    showUrgentAlert(notification)
                }
                else -> {
                    Log.d(TAG, "📱 Handled notification type: ${notification.type}")
                }
            }

            updateNotificationBadge()

            Log.d(TAG, "✅ Real-time notification handled successfully")

        } catch (e: Exception) {
            Log.e(TAG, "❌ Error handling real-time notification", e)
        }
    }

    private fun scheduleWebSocketReconnect() {
        Log.d(TAG, "⏰ Scheduling WebSocket reconnection...")

        refreshHandler.postDelayed({
            try {
                if (!isWebSocketConnected && ::webSocketManager.isInitialized) {
                    Log.d(TAG, "⚡ Attempting scheduled WebSocket reconnection...")
                    updateWebSocketConnectionStatus(false, "Đang kết nối lại...")
                    webSocketManager.connect()
                } else {
                    Log.d(TAG, "ℹ️ Skipping reconnect - already connected or manager not initialized")
                }
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error during scheduled reconnect", e)
            }
        }, WEBSOCKET_RECONNECT_DELAY)
    }

    // ========== DATA REFRESH METHODS ==========

    private fun refreshAllData() {
        try {
            Log.d(TAG, "⚡ Refreshing all data...")

            if (isWebSocketConnected && ::webSocketManager.isInitialized) {
                Log.d(TAG, "📡 Requesting data via WebSocket...")
                webSocketManager.requestParkingStatus()
                showToast("📡 Requesting real-time data...")
            } else {
                Log.d(TAG, "🌐 Falling back to HTTP request...")
                refreshParkingDataHttp()
                showToast("⚡ Loading data via HTTP...")
            }

        } catch (e: Exception) {
            Log.e(TAG, "❌ Error refreshing all data", e)
            refreshParkingDataHttp()
        }
    }

    private fun refreshParkingData() {
        if (isWebSocketConnected && ::webSocketManager.isInitialized) {
            Log.d(TAG, "📡 Refreshing parking data via WebSocket...")
            webSocketManager.requestParkingStatus()
            showToast("📡 Real-time data requested")
        } else {
            Log.d(TAG, "🌐 Refreshing parking data via HTTP...")
            refreshParkingDataHttp()
        }
    }

    private fun refreshParkingDataHttp() {
        if (!sharedPrefsHelper.isServerConfigured()) {
            showServerConfigDialog()
            return
        }

        Log.d(TAG, "🌐 Starting HTTP parking data refresh...")
        showParkingLoading(true)
        updateConnectionStatus(false)

        parkingStatusService.loginAndGetParkingStatus(object : ParkingStatusService.ParkingStatusCallback {
            override fun onStatusUpdated(status: ParkingStatusService.ParkingStatusData) {
                runOnUiThread {
                    try {
                        Log.d(TAG, "✅ HTTP parking data received")
                        updateParkingUIWithHttpData(status)
                        cacheParkingData(status)
                        updateConnectionStatus(true)
                        showParkingLoading(false)
                        showToast("✅ Dữ liệu cập nhật qua HTTP")
                    } catch (e: Exception) {
                        Log.e(TAG, "❌ Error processing HTTP data", e)
                        handleParkingDataError("Lỗi xử lý dữ liệu HTTP")
                    }
                }
            }

            override fun onError(error: String) {
                runOnUiThread {
                    Log.e(TAG, "❌ HTTP parking data error: $error")
                    handleParkingDataError(error)
                    loadCachedParkingStatus()
                }
            }
        })
    }

    private fun updateParkingUIWithHttpData(status: ParkingStatusService.ParkingStatusData) {
        try {
            val parkingInfo = status.parking_status

            Log.d(TAG, "🌐 ✅ Updating UI with HTTP data")
            Log.d(TAG, "📈 Available: ${parkingInfo.available}/${parkingInfo.total}")

            tvParkingTotal.text = parkingInfo.total.toString()
            tvParkingAvailable.text = parkingInfo.available.toString()
            tvParkingOccupied.text = parkingInfo.occupied.toString()
            tvParkingPercentage.text = "${parkingInfo.percentage_full.toInt()}%"

            tvParkingStatusMessage.text = "📡 HTTP: ${status.status_message}"

            progressParkingOccupancy.progress = parkingInfo.percentage_full.toInt()

            val colorResource = when {
                parkingInfo.available == 0 -> R.color.parking_full
                parkingInfo.available <= 3 -> R.color.parking_warning
                parkingInfo.available <= 8 -> R.color.parking_occupied
                else -> R.color.parking_available
            }

            viewParkingIndicator.setBackgroundTintList(
                ContextCompat.getColorStateList(this, colorResource)
            )

            tvAvailableSpaces.text = if (parkingInfo.available > 0) {
                "${parkingInfo.available} chỗ trống"
            } else {
                "Hết chỗ đỗ"
            }

            val currentTime = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
            tvLastUpdate.text = "📡 HTTP: $currentTime"

            llParkingInfo.visibility = View.VISIBLE

            Log.d(TAG, "✅ HTTP UI update completed")

        } catch (e: Exception) {
            Log.e(TAG, "❌ Error updating HTTP UI", e)
        }
    }

    private fun startHttpPollingBackup() {
        if (!isWebSocketConnected) {
            Log.d(TAG, "⚡ Starting HTTP polling backup...")
            startPeriodicHttpUpdates()
        } else {
            Log.d(TAG, "ℹ️ Skipping HTTP polling - WebSocket is connected")
        }
    }

    private fun startPeriodicHttpUpdates() {
        if (isParkingServiceActive) {
            Log.d(TAG, "ℹ️ HTTP polling already active")
            return
        }

        try {
            Log.d(TAG, "⚡ Starting periodic HTTP updates...")

            parkingStatusService.startPeriodicUpdates(object : ParkingStatusService.ParkingStatusCallback {
                override fun onStatusUpdated(status: ParkingStatusService.ParkingStatusData) {
                    runOnUiThread {
                        if (!isWebSocketConnected) {
                            Log.d(TAG, "📊 HTTP periodic update received")
                            updateParkingUIWithHttpData(status)
                            cacheParkingData(status)
                        } else {
                            Log.d(TAG, "ℹ️ Skipping HTTP update - WebSocket is now connected")
                        }
                    }
                }

                override fun onError(error: String) {
                    runOnUiThread {
                        if (!isWebSocketConnected) {
                            Log.e(TAG, "❌ HTTP periodic update error: $error")
                            handleParkingDataError(error)
                        }
                    }
                }
            })

            isParkingServiceActive = true
            Log.d(TAG, "✅ HTTP polling backup started successfully")

        } catch (e: Exception) {
            Log.e(TAG, "❌ Error starting HTTP polling", e)
        }
    }

    // ========== WEBSOCKET CONTROLS ==========

    private fun testWebSocketConnection() {
        try {
            Log.d(TAG, "🧪 Testing WebSocket connection...")

            if (!::webSocketManager.isInitialized) {
                showToast("❌ WebSocket manager chưa được khởi tạo")
                return
            }

            val isConnected = webSocketManager.isConnected()
            val isHealthy = webSocketManager.isConnectionHealthy()
            val testResult = webSocketManager.testConnection()

            val message = """
                🔌 Connected: $isConnected
                💚 Healthy: $isHealthy
                🧪 Test: $testResult
                🌐 Server: ${sharedPrefsHelper.getServerUrl()}
            """.trimIndent()

            showToast(message)
            Log.d(TAG, "🧪 WebSocket test completed: connected=$isConnected, healthy=$isHealthy, test=$testResult")

            showWebSocketStatusDialog()

        } catch (e: Exception) {
            Log.e(TAG, "❌ Error testing WebSocket", e)
            showToast("Lỗi test WebSocket: ${e.message}")
        }
    }

    private fun reconnectWebSocket() {
        try {
            Log.d(TAG, "⚡ Force WebSocket reconnection requested")

            if (!::webSocketManager.isInitialized) {
                showToast("❌ WebSocket manager chưa được khởi tạo")
                return
            }

            showToast("⚡ Đang kết nối lại WebSocket...")
            updateWebSocketConnectionStatus(false, "Đang kết nối lại...")

            webSocketManager.forceReconnect()

        } catch (e: Exception) {
            Log.e(TAG, "❌ Error reconnecting WebSocket", e)
            showToast("Lỗi kết nối lại: ${e.message}")
        }
    }

    private fun showWebSocketStatusDialog() {
        try {
            if (!::webSocketManager.isInitialized) {
                showToast("❌ WebSocket manager chưa được khởi tạo")
                return
            }

            val status = webSocketManager.getDetailedStatus()
            val connectionInfo = webSocketManager.getConnectionInfo()

            val message = buildString {
                appendLine("🔌 WebSocket Status Details:")
                appendLine("")
                appendLine("Connection: ${if (isWebSocketConnected) "✅ Connected" else "❌ Disconnected"}")
                appendLine("Server: ${sharedPrefsHelper.getServerUrl()}")
                appendLine("Health: ${if (webSocketManager.isConnectionHealthy()) "💚 Healthy" else "💔 Unhealthy"}")
                appendLine("Socket ID: ${connectionInfo["socket_id"]}")
                appendLine("")
                appendLine("Callbacks Registered:")
                val callbacks = status["callbacks"] as? Map<*, *>
                callbacks?.forEach { (key, value) ->
                    appendLine("• $key: $value")
                }
                appendLine("")
                appendLine("Last Update: ${SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())}")
            }

            AlertDialog.Builder(this)
                .setTitle("WebSocket Status")
                .setMessage(message)
                .setPositiveButton("OK", null)
                .setNeutralButton("Test") { _, _ -> testWebSocketConnection() }
                .setNegativeButton("Reconnect") { _, _ -> reconnectWebSocket() }
                .show()

        } catch (e: Exception) {
            Log.e(TAG, "❌ Error showing status dialog", e)
            showToast("Lỗi hiển thị trạng thái WebSocket")
        }
    }

    // ========== CLEANUP METHODS ==========

    private fun cleanupWebSocket() {
        try {
            if (::webSocketManager.isInitialized) {
                Log.d(TAG, "🧹 Cleaning up WebSocket resources...")

                webSocketManager.unregisterParkingStatusCallback("main_activity")
                webSocketManager.unregisterConnectionCallback("main_activity")
                webSocketManager.unregisterNotificationCallback("main_activity")

                Log.d(TAG, "✅ WebSocket callbacks unregistered")
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error cleaning up WebSocket", e)
        }
    }

    // ========== UTILITY METHODS ==========

    private fun checkUserAuthentication() {
        if (!sharedPrefsHelper.isUserLoggedIn()) {
            Log.w(TAG, "⚠️ User not authenticated, navigating to login")
            navigateToLogin()
            return
        }
        updateUserInfo()
    }

    private fun loadUserData() {
        val vehicleSession = sharedPrefsHelper.getVehicleSession()
        if (vehicleSession != null) {
            tvUserPlate.text = vehicleSession.getDisplayName()
            loadNotificationHistory()
            Log.d(TAG, "✅ User data loaded: ${vehicleSession.plateNumber}")
        } else {
            val userName = sharedPrefsHelper.getUserFullName()
            tvUserPlate.text = if (userName.isNotEmpty()) {
                "Xin chào $userName\nChưa có thông tin xe"
            } else {
                "Chưa đăng nhập"
            }
            Log.d(TAG, "⚠️ No vehicle session found")
        }
    }

    private fun cacheParkingData(status: ParkingStatusService.ParkingStatusData) {
        try {
            sharedPrefsHelper.cacheParkingStatus(
                status.parking_status.total,
                status.parking_status.available,
                status.parking_status.occupied,
                status.parking_status.percentage_full,
                status.status_message
            )
            Log.d(TAG, "✅ Parking data cached successfully")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error caching parking data", e)
        }
    }

    private fun loadCachedParkingStatus() {
        try {
            val cachedData = sharedPrefsHelper.getCachedParkingStatus()
            if (cachedData != null) {
                Log.d(TAG, "📦 Loading cached parking status")
                val status = ParkingStatusService.ParkingStatusData(
                    parking_status = ParkingStatusService.ParkingInfo(
                        total = cachedData.total,
                        available = cachedData.available,
                        occupied = cachedData.occupied,
                        percentage_full = cachedData.percentage
                    ),
                    status_message = cachedData.message,
                    last_updated = "Cached: ${SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(cachedData.cacheTime))}",
                    color_indicator = "gray"
                )
                updateParkingUIWithHttpData(status)
            } else {
                Log.d(TAG, "📦 No cached parking data available")
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error loading cached data", e)
        }
    }

    private fun showParkingLoading(loading: Boolean) {
        try {
            if (loading) {
                tvParkingStatusMessage.text = "⚡ Đang tải dữ liệu..."
                progressParkingOccupancy.isIndeterminate = true
            } else {
                progressParkingOccupancy.isIndeterminate = false
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error showing loading state", e)
        }
    }

    private fun handleParkingDataError(error: String) {
        Log.e(TAG, "❌ Parking data error: $error")
        showToast("❌ $error")
        updateConnectionStatus(false)
        showParkingLoading(false)
    }

    private fun updateConnectionStatus(connected: Boolean) {
        try {
            if (connected) {
                viewConnectionIndicator.setBackgroundTintList(
                    ContextCompat.getColorStateList(this, R.color.success_color)
                )
                tvConnectionStatus.text = "HTTP OK"
            } else {
                viewConnectionIndicator.setBackgroundTintList(
                    ContextCompat.getColorStateList(this, R.color.error_color)
                )
                tvConnectionStatus.text = "HTTP ERROR"
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error updating basic connection status", e)
        }
    }

    private fun stopParkingStatusUpdates() {
        isParkingServiceActive = false
        try {
            parkingStatusService.stopPeriodicUpdates()
            Log.d(TAG, "✅ Parking status updates stopped")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error stopping parking updates", e)
        }
    }

    // ========== NOTIFICATION HANDLING ==========

    private fun addNotificationToUI(notification: NotificationItem) {
        try {
            notificationsList.add(0, notification)
            if (notificationsList.size > MAX_VISIBLE_NOTIFICATIONS) {
                notificationsList.removeAt(notificationsList.size - 1)
            }
            updateNotificationsUI()
            Log.d(TAG, "✅ Notification added to UI: ${notification.title}")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error adding notification to UI", e)
        }
    }

    private fun updateNotificationsUI() {
        runOnUiThread {
            notificationsContainer.removeAllViews()

            if (recentNotifications.isEmpty()) {
                noNotificationsView.visibility = View.VISIBLE
                notificationsContainer.visibility = View.GONE
            } else {
                noNotificationsView.visibility = View.GONE
                notificationsContainer.visibility = View.VISIBLE

                // Hiển thị tối đa 3 notifications gần nhất
                recentNotifications.take(3).forEach { notification ->
                    val notificationView = createNotificationView(notification)
                    notificationsContainer.addView(notificationView)
                }
            }
        }
    }

    private fun updateNotificationBadge() {
        try {
            val unreadCount = notificationsList.count { !it.isRead }
            Log.d(TAG, "📱 Notification badge updated: $unreadCount unread")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error updating notification badge", e)
        }
    }

    private fun loadNotificationHistory() {
        try {
            Log.d(TAG, "📱 Loading notification history...")

            val sampleNotifications = listOf(
                NotificationItem.createSpaceAvailableNotification(5, 20),
                NotificationItem.createSystemMaintenanceNotification("Bảo trì định kỳ", "30 phút"),
                NotificationItem.createParkingFullNotification(20, "14:30")
            )

            notificationsList.clear()
            notificationsList.addAll(sampleNotifications)
            updateNotificationsUI()

        } catch (e: Exception) {
            Log.e(TAG, "❌ Error loading notification history", e)
        }
    }

    private fun sendTestNotification() {
        try {
            val testNotification = NotificationItem(
                id = System.currentTimeMillis().toInt(),
                title = "🧪 Test Notification",
                message = "Đây là thông báo test từ ${SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())}",
                timestamp = System.currentTimeMillis(),
                type = NotificationItem.Type.SYSTEM_UPDATE,
                priority = NotificationItem.Priority.NORMAL
            )

            notificationService.showNotification(this, testNotification.title, testNotification.message)
            addNotificationToUI(testNotification)

            showToast("📱 Test notification sent")
            Log.d(TAG, "✅ Test notification sent successfully")

        } catch (e: Exception) {
            Log.e(TAG, "❌ Error sending test notification", e)
            showToast("Lỗi gửi test notification")
        }
    }

    // ========== ALERT DIALOGS ==========

    private fun showParkingFullAlert() {
        try {
            AlertDialog.Builder(this)
                .setTitle("🚫 Bãi đỗ xe đã đầy")
                .setMessage("Hiện tại không còn chỗ đỗ trống. Vui lòng chờ hoặc tìm bãi khác.")
                .setPositiveButton("OK", null)
                .setNeutralButton("Xem chi tiết") { _, _ -> showParkingDetails() }
                .show()
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error showing parking full alert", e)
        }
    }

    private fun showSpaceAvailableAlert() {
        try {
            val available = tvParkingAvailable.text.toString().toIntOrNull() ?: 0
            AlertDialog.Builder(this)
                .setTitle("✅ Có chỗ đỗ trống!")
                .setMessage("Hiện có $available chỗ đỗ trống. Nhanh tay đến bãi xe!")
                .setPositiveButton("OK", null)
                .setNeutralButton("Xem vị trí") { _, _ -> showParkingDetails() }
                .show()
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error showing space available alert", e)
        }
    }

    private fun showUrgentAlert(notification: NotificationItem) {
        try {
            AlertDialog.Builder(this)
                .setTitle("🚨 ${notification.title}")
                .setMessage(notification.message)
                .setPositiveButton("OK", null)
                .setIcon(android.R.drawable.ic_dialog_alert)
                .show()
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error showing urgent alert", e)
        }
    }

    private fun showParkingDetails() {
        try {
            val total = tvParkingTotal.text.toString().toIntOrNull() ?: 0
            val available = tvParkingAvailable.text.toString().toIntOrNull() ?: 0
            val occupied = tvParkingOccupied.text.toString().toIntOrNull() ?: 0
            val percentage = tvParkingPercentage.text.toString().replace("%", "").toIntOrNull() ?: 0

            val message = buildString {
                appendLine("📊 Chi tiết bãi đỗ xe:")
                appendLine("")
                appendLine("🏭️ Tổng số chỗ: $total")
                appendLine("✅ Chỗ trống: $available")
                appendLine("🚗 Đã có xe: $occupied")
                appendLine("📈 Tỷ lệ sử dụng: $percentage%")
                appendLine("")
                appendLine("⚡ Cập nhật: ${tvLastUpdate.text}")
                appendLine("🔌 Kết nối: ${if (isWebSocketConnected) "Real-time" else "HTTP"}")
            }

            AlertDialog.Builder(this)
                .setTitle("Chi tiết bãi đỗ xe")
                .setMessage(message)
                .setPositiveButton("OK", null)
                .setNeutralButton("Làm mới") { _, _ -> refreshParkingData() }
                .show()

        } catch (e: Exception) {
            Log.e(TAG, "❌ Error showing parking details", e)
        }
    }

    private fun showServerConfigDialog() {
        try {
            val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_server_config, null)
            val etServerUrl = dialogView.findViewById<EditText>(R.id.et_server_url)

            etServerUrl.setText(sharedPrefsHelper.getServerUrl())

            AlertDialog.Builder(this)
                .setTitle("Cấu hình Server")
                .setView(dialogView)
                .setPositiveButton("Lưu") { _, _ ->
                    val newUrl = etServerUrl.text.toString().trim()
                    if (newUrl.isNotEmpty()) {
                        sharedPrefsHelper.setServerUrl(newUrl)
                        showToast("✅ Đã lưu URL server: $newUrl")

                        if (::webSocketManager.isInitialized) {
                            webSocketManager.forceReconnect()
                        }
                    }
                }
                .setNegativeButton("Hủy", null)
                .show()

        } catch (e: Exception) {
            Log.e(TAG, "❌ Error showing server config dialog", e)
            showToast("Lỗi hiển thị dialog cấu hình server")
        }
    }

    private fun showSettingsMenu() {
        try {
            val options = arrayOf(
                "⚙️ Cài đặt ứng dụng",
                "🔧 Cấu hình server",
                "🔌 Trạng thái WebSocket",
                "📱 Test notification",
                "🧹 Xóa cache",
                "🚪 Đăng xuất"
            )

            AlertDialog.Builder(this)
                .setTitle("Cài đặt")
                .setItems(options) { _, which ->
                    when (which) {
                        0 -> openAppSettings()
                        1 -> showServerConfigDialog()
                        2 -> showWebSocketStatusDialog()
                        3 -> sendTestNotification()
                        4 -> clearCache()
                        5 -> logout()
                    }
                }
                .show()

        } catch (e: Exception) {
            Log.e(TAG, "❌ Error showing settings menu", e)
        }
    }

    private fun showParkingHistory() {
        try {
            Log.d(TAG, "📋 Opening parking history...")
            val intent = Intent(this, ParkingHistoryActivity::class.java)
            startActivity(intent)
            showToast("📋 Lịch sử đỗ xe")
        } catch (e: Exception) {
            Log.e(TAG, "Error opening parking history", e)
            showToast("❌ Lỗi khi mở lịch sử đỗ xe")
        }
    }

    private fun showReportIssue() {
        try {
            Log.d(TAG, "⚠️ Opening report issue...")
            val intent = Intent(this, ReportIssueActivity::class.java)
            startActivity(intent)
            showToast("⚠️ Báo cáo sự cố")
        } catch (e: Exception) {
            Log.e(TAG, "Error opening report issue", e)
            showToast("❌ Lỗi khi mở form báo cáo")
        }
    }

    // ========== NAVIGATION AND SYSTEM ==========

    private fun openAppSettings() {
        try {
            val intent = Intent(this, SettingsActivity::class.java)
            startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error opening app settings", e)
            showToast("Không thể mở cài đặt ứng dụng")
        }
    }

    private fun openNotificationHistory() {
        try {
            val intent = Intent(this, NotificationHistoryActivity::class.java)
            startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error opening notification history", e)
            showToast("Không thể mở lịch sử thông báo")
        }
    }

    private fun clearCache() {
        try {
            sharedPrefsHelper.clearCache()
            showToast("✅ Đã xóa cache")
            refreshAllData()
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error clearing cache", e)
            showToast("Lỗi xóa cache")
        }
    }

    private fun logout() {
        try {
            AlertDialog.Builder(this)
                .setTitle("Đăng xuất")
                .setMessage("Bạn có chắc chắn muốn đăng xuất?")
                .setPositiveButton("Đăng xuất") { _, _ ->
                    performLogout()
                }
                .setNegativeButton("Hủy", null)
                .show()
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error showing logout dialog", e)
        }
    }

    private fun performLogout() {
        try {
            if (::webSocketManager.isInitialized) {
                webSocketManager.disconnect()
            }

            sharedPrefsHelper.logout()
            navigateToLogin()

        } catch (e: Exception) {
            Log.e(TAG, "❌ Error performing logout", e)
        }
    }

    private fun navigateToLogin() {
        try {
            val intent = Intent(this, LoginActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            startActivity(intent)
            finish()
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error navigating to login", e)
        }
    }

    private fun updateUserInfo() {
        try {
            val userName = sharedPrefsHelper.getUserFullName()
            if (userName.isNotEmpty()) {
                Log.d(TAG, "✅ User info updated for: $userName")
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error updating user info", e)
        }
    }

    private fun checkPermissions() {
        try {
            val permissions = arrayOf(
                Manifest.permission.POST_NOTIFICATIONS,
                Manifest.permission.INTERNET,
                Manifest.permission.ACCESS_NETWORK_STATE
            )

            val missingPermissions = permissions.filter {
                ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
            }

            if (missingPermissions.isNotEmpty()) {
                ActivityCompat.requestPermissions(
                    this,
                    missingPermissions.toTypedArray(),
                    PERMISSION_REQUEST_CODE
                )
            }

        } catch (e: Exception) {
            Log.e(TAG, "❌ Error checking permissions", e)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == PERMISSION_REQUEST_CODE) {
            val allGranted = grantResults.all { it == PackageManager.PERMISSION_GRANTED }
            if (allGranted) {
                Log.d(TAG, "✅ All permissions granted")
                showToast("✅ Đã cấp quyền thành công")
            } else {
                Log.w(TAG, "⚠️ Some permissions denied")
                showToast("⚠️ Một số quyền bị từ chối")
            }
        }
    }

    private fun showToast(message: String) {
        try {
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error showing toast", e)
        }
    }

    private fun saveNotificationToStorage(vehicleNotification: VehicleNotification) {
        try {
            val sharedPrefs = getSharedPreferences("notifications", Context.MODE_PRIVATE)

            // Lấy danh sách notifications hiện tại
            val existingJson = sharedPrefs.getString("notification_list", "[]") ?: "[]"
            val notificationsList = JSONArray(existingJson)

            // Tạo JSON object cho notification mới
            val notificationJson = JSONObject().apply {
                put("id", vehicleNotification.id)
                put("title", vehicleNotification.title)
                put("message", buildNotificationMessage(vehicleNotification))
                put("plate_number", vehicleNotification.plateNumber)
                put("owner_name", vehicleNotification.ownerName ?: "")
                put("action", vehicleNotification.action)
                put("timestamp", System.currentTimeMillis())
                put("parking_duration", vehicleNotification.parkingDuration ?: 0)
                put("is_read", false)
                put("type", if (vehicleNotification.action == "entry") "VEHICLE_ENTERED" else "VEHICLE_EXITED")
            }

            // Thêm vào đầu danh sách
            val newList = JSONArray()
            newList.put(notificationJson)

            // Copy existing notifications (giữ tối đa 100)
            for (i in 0 until minOf(notificationsList.length(), 99)) {
                newList.put(notificationsList.getJSONObject(i))
            }

            // Lưu lại
            sharedPrefs.edit()
                .putString("notification_list", newList.toString())
                .apply()

            Log.d(TAG, "✅ Saved notification to storage")

        } catch (e: Exception) {
            Log.e(TAG, "Error saving notification to storage", e)
        }
    }

    // Load notifications từ storage khi app khởi động
    private fun loadNotificationsFromStorage() {
        try {
            val sharedPrefs = getSharedPreferences("notifications", Context.MODE_PRIVATE)
            val notificationsJson = sharedPrefs.getString("notification_list", "[]") ?: "[]"
            val notificationsList = JSONArray(notificationsJson)

            recentNotifications.clear()

            // Load tối đa 5 notifications gần nhất
            for (i in 0 until minOf(notificationsList.length(), 5)) {
                val json = notificationsList.getJSONObject(i)

                val notification = NotificationItem(
                    id = json.optInt("id"),
                    title = json.optString("title"),
                    message = json.optString("message"),
                    timestamp = json.optLong("timestamp"),
                    type = NotificationItem.Type.fromString(json.optString("type", "SYSTEM_UPDATE")),
                    isRead = json.optBoolean("is_read", false),
                    priority = NotificationItem.Priority.HIGH,
                    data = mapOf(
                        "plate_number" to json.optString("plate_number"),
                        "owner_name" to json.optString("owner_name"),
                        "action" to json.optString("action")
                    )
                )

                recentNotifications.add(notification)
            }

            updateNotificationsUI()
            Log.d(TAG, "✅ Loaded ${recentNotifications.size} notifications from storage")

        } catch (e: Exception) {
            Log.e(TAG, "Error loading notifications from storage", e)
        }
    }
}