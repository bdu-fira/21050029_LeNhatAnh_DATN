package com.example.parkingmobiapp

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.parkingmobiapp.models.NotificationItem
import com.example.parkingmobiapp.utils.SharedPrefsHelper
import java.text.SimpleDateFormat
import java.util.*
import android.content.Context
import android.util.Log
import org.json.JSONArray

/**
 * NotificationHistoryActivity - Màn hình lịch sử thông báo
 * FIXED: Đã sửa lỗi TODO() trong getNotificationIconAndColor và getNotificationTypeText
 */
class NotificationHistoryActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "NotificationHistory"
    }

    // UI Components
    private lateinit var tvNotificationCount: TextView
    private lateinit var ivClearAll: ImageView
    private lateinit var rvNotifications: RecyclerView
    private lateinit var llEmptyState: LinearLayout
    private lateinit var llLoadingState: LinearLayout
    private lateinit var btnRefreshNotifications: Button
    private lateinit var llMarkAllRead: LinearLayout
    private lateinit var llExportNotifications: LinearLayout

    // Filter Chips
    private lateinit var chipFilterAll: TextView
    private lateinit var chipFilterWrongPosition: TextView
    private lateinit var chipFilterSpaceAvailable: TextView
    private lateinit var chipFilterSystem: TextView

    // Adapter and Data
    private lateinit var notificationAdapter: NotificationHistoryAdapter
    private val allNotifications = mutableListOf<NotificationItem>()
    private val filteredNotifications = mutableListOf<NotificationItem>()
    private var currentFilter = NotificationFilter.ALL

    // Utilities
    private lateinit var sharedPrefsHelper: SharedPrefsHelper

    // Filter enum
    enum class NotificationFilter {
        ALL, WRONG_POSITION, SPACE_AVAILABLE, SYSTEM
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_notification_history)

        initializeComponents()
        setupRecyclerView()
        setupClickListeners()
        loadNotifications()
    }

    /**
     * Khởi tạo các UI components
     */
    private fun initializeComponents() {
        try {
            // Header
            tvNotificationCount = findViewById(R.id.tv_notification_count)
            ivClearAll = findViewById(R.id.iv_clear_all)

            // Filter chips
            chipFilterAll = findViewById(R.id.chip_filter_all)
            chipFilterWrongPosition = findViewById(R.id.chip_filter_wrong_position)
            chipFilterSpaceAvailable = findViewById(R.id.chip_filter_space_available)
            chipFilterSystem = findViewById(R.id.chip_filter_system)

            // Main content
            rvNotifications = findViewById(R.id.rv_notifications)
            llEmptyState = findViewById(R.id.ll_empty_state)
            llLoadingState = findViewById(R.id.ll_loading_state)
            btnRefreshNotifications = findViewById(R.id.btn_refresh_notifications)

            // Bottom actions
            llMarkAllRead = findViewById(R.id.ll_mark_all_read)
            llExportNotifications = findViewById(R.id.ll_export_notifications)

            // Initialize SharedPrefs
            sharedPrefsHelper = SharedPrefsHelper(this)

            Log.d(TAG, "✅ Components initialized successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing components", e)
            showToast("Lỗi khởi tạo giao diện")
        }
    }

    /**
     * Setup RecyclerView
     */
    private fun setupRecyclerView() {
        notificationAdapter = NotificationHistoryAdapter(
            notifications = filteredNotifications,
            onItemClick = { notification ->
                handleNotificationClick(notification)
            },
            onItemLongClick = { notification ->
                showNotificationActions(notification)
            }
        )

        rvNotifications.apply {
            layoutManager = LinearLayoutManager(this@NotificationHistoryActivity)
            adapter = notificationAdapter
            setHasFixedSize(true)
        }
    }

    /**
     * Setup click listeners
     */
    private fun setupClickListeners() {
        // Back button
        findViewById<ImageView>(R.id.iv_back_button).setOnClickListener {
            onBackPressed()
        }

        // Clear all notifications
        ivClearAll.setOnClickListener {
            showClearAllDialog()
        }

        // Filter chips
        chipFilterAll.setOnClickListener { applyFilter(NotificationFilter.ALL) }
        chipFilterWrongPosition.setOnClickListener { applyFilter(NotificationFilter.WRONG_POSITION) }
        chipFilterSpaceAvailable.setOnClickListener { applyFilter(NotificationFilter.SPACE_AVAILABLE) }
        chipFilterSystem.setOnClickListener { applyFilter(NotificationFilter.SYSTEM) }

        // Empty state refresh
        btnRefreshNotifications.setOnClickListener {
            loadNotifications()
        }

        // Bottom actions
        llMarkAllRead.setOnClickListener {
            markAllAsRead()
        }

        llExportNotifications.setOnClickListener {
            exportNotifications()
        }
    }

    /**
     * Load notifications from storage
     */
    private fun loadNotifications() {
        showLoadingState(true)

        try {
            // Load từ SharedPreferences
            val sharedPrefs = getSharedPreferences("notifications", Context.MODE_PRIVATE)
            val notificationsJson = sharedPrefs.getString("notification_list", "[]") ?: "[]"
            val notificationsList = JSONArray(notificationsJson)

            allNotifications.clear()

            // Parse all notifications
            for (i in 0 until notificationsList.length()) {
                try {
                    val json = notificationsList.getJSONObject(i)

                    val notification = NotificationItem(
                        id = json.optInt("id", i),
                        title = json.optString("title", "Thông báo"),
                        message = json.optString("message", ""),
                        timestamp = json.optLong("timestamp", System.currentTimeMillis()),
                        type = NotificationItem.Type.fromString(
                            json.optString("type", "SYSTEM_UPDATE")
                        ),
                        isRead = json.optBoolean("is_read", false),
                        priority = NotificationItem.Priority.NORMAL,
                        data = mapOf(
                            "plate_number" to json.optString("plate_number", ""),
                            "owner_name" to json.optString("owner_name", ""),
                            "action" to json.optString("action", ""),
                            "parking_duration" to json.optString("parking_duration", "")
                        )
                    )

                    allNotifications.add(notification)
                } catch (e: Exception) {
                    Log.e(TAG, "Error parsing notification at index $i", e)
                }
            }

            // Thêm sample notifications nếu cần
            if (allNotifications.isEmpty()) {
                addSampleNotifications()
            }

            showLoadingState(false)
            applyFilter(currentFilter)
            updateNotificationCount()

            Log.d(TAG, "✅ Loaded ${allNotifications.size} notifications")

        } catch (e: Exception) {
            Log.e(TAG, "Error loading notifications", e)
            addSampleNotifications()
            showLoadingState(false)
        }
    }

    private fun addSampleNotifications() {
        // Chỉ thêm sample data nếu cần demo
       /** val sampleNotifications = listOf(
            NotificationItem.createSpaceAvailableNotification(5, 20),
            NotificationItem.createSystemMaintenanceNotification("Bảo trì định kỳ", "30 phút"),
            NotificationItem.createParkingFullNotification(20, "14:30"),
            NotificationItem(
                id = 4,
                title = "🚗 Xe vào bãi",
                message = "Xe 60A-12345 vừa vào bãi đỗ",
                timestamp = System.currentTimeMillis() - 3600000,
                type = NotificationItem.Type.VEHICLE_ENTERED,
                priority = NotificationItem.Priority.NORMAL,
                isRead = false
            ),
            NotificationItem(
                id = 5,
                title = "🚗 Xe rời bãi",
                message = "Xe 60A-54321 đã rời bãi sau 2 giờ",
                timestamp = System.currentTimeMillis() - 7200000,
                type = NotificationItem.Type.VEHICLE_EXITED,
                priority = NotificationItem.Priority.NORMAL,
                isRead = true
            )
        )

        allNotifications.addAll(sampleNotifications)*/
    }

    private fun applyFilter(filter: NotificationFilter) {
        currentFilter = filter

        filteredNotifications.clear()

        when (filter) {
            NotificationFilter.ALL -> {
                filteredNotifications.addAll(allNotifications)
            }
            NotificationFilter.WRONG_POSITION -> {
                filteredNotifications.addAll(
                    allNotifications.filter {
                        it.type == NotificationItem.Type.WRONG_POSITION ||
                                it.type == NotificationItem.Type.OVERSTAY_WARNING ||
                                it.type == NotificationItem.Type.SECURITY_ALERT
                    }
                )
            }
            NotificationFilter.SPACE_AVAILABLE -> {
                filteredNotifications.addAll(
                    allNotifications.filter {
                        it.type == NotificationItem.Type.SPACE_AVAILABLE ||
                                it.type == NotificationItem.Type.SPACE_FULL ||
                                it.type == NotificationItem.Type.PARKING_FULL ||
                                it.type == NotificationItem.Type.SPACE_LIMITED ||
                                it.type == NotificationItem.Type.VEHICLE_ENTERED ||
                                it.type == NotificationItem.Type.VEHICLE_EXITED
                    }
                )
            }
            NotificationFilter.SYSTEM -> {
                filteredNotifications.addAll(
                    allNotifications.filter {
                        it.type == NotificationItem.Type.SYSTEM_UPDATE ||
                                it.type == NotificationItem.Type.POSITION_CORRECT ||
                                it.type == NotificationItem.Type.SYSTEM_MAINTENANCE ||
                                it.type == NotificationItem.Type.CONNECTION_LOST ||
                                it.type == NotificationItem.Type.CONNECTION_RESTORED
                    }
                )
            }
        }

        // Sort by timestamp (newest first)
        filteredNotifications.sortByDescending { it.timestamp }

        updateFilterChips()
        updateUI()
    }

    /**
     * Update filter chip appearances
     */
    private fun updateFilterChips() {
        val chips = listOf(chipFilterAll, chipFilterWrongPosition, chipFilterSpaceAvailable, chipFilterSystem)
        val filters = listOf(NotificationFilter.ALL, NotificationFilter.WRONG_POSITION, NotificationFilter.SPACE_AVAILABLE, NotificationFilter.SYSTEM)

        chips.forEachIndexed { index, chip ->
            if (filters[index] == currentFilter) {
                // Selected state
                chip.setBackgroundResource(R.drawable.chip_selected)
                chip.setTextColor(ContextCompat.getColor(this, R.color.text_on_primary))
            } else {
                // Unselected state
                chip.setBackgroundResource(R.drawable.chip_unselected)
                chip.setTextColor(ContextCompat.getColor(this, R.color.secondary_text))
            }
        }
    }

    /**
     * Update UI based on filtered notifications
     */
    private fun updateUI() {
        if (filteredNotifications.isEmpty()) {
            showEmptyState(true)
        } else {
            showEmptyState(false)
            notificationAdapter.notifyDataSetChanged()
        }
    }

    /**
     * Update notification count display
     */
    private fun updateNotificationCount() {
        val count = allNotifications.size
        tvNotificationCount.text = if (count == 0) {
            "Không có thông báo"
        } else {
            "$count thông báo"
        }
    }

    /**
     * Show/hide loading state
     */
    private fun showLoadingState(show: Boolean) {
        llLoadingState.visibility = if (show) View.VISIBLE else View.GONE
        rvNotifications.visibility = if (show) View.GONE else View.VISIBLE
        llEmptyState.visibility = View.GONE
    }

    /**
     * Show/hide empty state
     */
    private fun showEmptyState(show: Boolean) {
        llEmptyState.visibility = if (show) View.VISIBLE else View.GONE
        rvNotifications.visibility = if (show) View.GONE else View.VISIBLE
    }

    /**
     * Handle notification item click
     */
    private fun handleNotificationClick(notification: NotificationItem) {
        // Mark as read
        if (!notification.isRead) {
            notification.isRead = true
            notificationAdapter.notifyDataSetChanged()
            saveNotificationReadStatus(notification)
            showToast("Đã đánh dấu đã đọc")
        }

        // Handle based on notification type
        when (notification.type) {
            NotificationItem.Type.WRONG_POSITION,
            NotificationItem.Type.VEHICLE_ENTERED,
            NotificationItem.Type.VEHICLE_EXITED -> {
                // Navigate to MainActivity with info
                val intent = Intent(this, MainActivity::class.java).apply {
                    putExtra("action", "view_notification")
                    putExtra("notification_id", notification.id)
                }
                startActivity(intent)
            }
            NotificationItem.Type.SPACE_AVAILABLE,
            NotificationItem.Type.PARKING_FULL -> {
                // Navigate to MainActivity with space info
                val intent = Intent(this, MainActivity::class.java).apply {
                    putExtra("action", "view_spaces")
                    putExtra("notification_id", notification.id)
                }
                startActivity(intent)
            }
            else -> {
                // Default action - show details dialog
                showNotificationDetailsDialog(notification)
            }
        }
    }

    private fun showNotificationDetailsDialog(notification: NotificationItem) {
        AlertDialog.Builder(this)
            .setTitle(notification.title)
            .setMessage(notification.message)
            .setPositiveButton("OK", null)
            .show()
    }

    private fun saveNotificationReadStatus(notification: NotificationItem) {
        try {
            // Update read status in SharedPreferences
            val sharedPrefs = getSharedPreferences("notifications", Context.MODE_PRIVATE)
            val notificationsJson = sharedPrefs.getString("notification_list", "[]") ?: "[]"
            val notificationsList = JSONArray(notificationsJson)

            for (i in 0 until notificationsList.length()) {
                val json = notificationsList.getJSONObject(i)
                if (json.optInt("id") == notification.id) {
                    json.put("is_read", true)
                    break
                }
            }

            sharedPrefs.edit()
                .putString("notification_list", notificationsList.toString())
                .apply()
        } catch (e: Exception) {
            Log.e(TAG, "Error saving read status", e)
        }
    }

    /**
     * Show notification actions on long click
     */
    private fun showNotificationActions(notification: NotificationItem) {
        val actions = arrayOf(
            if (notification.isRead) "Đánh dấu chưa đọc" else "Đánh dấu đã đọc",
            "Chia sẻ",
            "Xóa"
        )

        AlertDialog.Builder(this)
            .setTitle(notification.title)
            .setItems(actions) { _, which ->
                when (which) {
                    0 -> toggleReadStatus(notification)
                    1 -> shareNotification(notification)
                    2 -> deleteNotification(notification)
                }
            }
            .show()
    }

    /**
     * Toggle read status of notification
     */
    private fun toggleReadStatus(notification: NotificationItem) {
        notification.isRead = !notification.isRead
        notificationAdapter.notifyDataSetChanged()
        saveNotificationReadStatus(notification)

        val message = if (notification.isRead) "Đã đánh dấu đã đọc" else "Đã đánh dấu chưa đọc"
        showToast(message)
    }

    /**
     * Share notification content
     */
    private fun shareNotification(notification: NotificationItem) {
        val shareText = """
            ${notification.title}
            
            ${notification.message}
            
            Thời gian: ${formatTimestamp(notification.timestamp)}
            
            Từ ứng dụng Smart Parking
        """.trimIndent()

        val shareIntent = Intent().apply {
            action = Intent.ACTION_SEND
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, shareText)
        }

        startActivity(Intent.createChooser(shareIntent, "Chia sẻ thông báo"))
    }

    /**
     * Delete single notification
     */
    private fun deleteNotification(notification: NotificationItem) {
        AlertDialog.Builder(this)
            .setTitle("Xóa thông báo")
            .setMessage("Bạn có chắc chắn muốn xóa thông báo này?")
            .setPositiveButton("Xóa") { _, _ ->
                allNotifications.remove(notification)
                applyFilter(currentFilter)
                updateNotificationCount()
                showToast("Đã xóa thông báo")
                // TODO: Delete from database
            }
            .setNegativeButton("Hủy", null)
            .show()
    }

    /**
     * Show clear all notifications dialog
     */
    private fun showClearAllDialog() {
        AlertDialog.Builder(this)
            .setTitle("Xóa tất cả thông báo")
            .setMessage("Bạn có chắc chắn muốn xóa tất cả thông báo? Hành động này không thể hoàn tác.")
            .setIcon(R.drawable.ic_delete)
            .setPositiveButton("Xóa tất cả") { _, _ ->
                clearAllNotifications()
            }
            .setNegativeButton("Hủy", null)
            .show()
    }

    /**
     * Clear all notifications
     */
    private fun clearAllNotifications() {
        allNotifications.clear()
        applyFilter(currentFilter)
        updateNotificationCount()

        // Clear from SharedPreferences
        val sharedPrefs = getSharedPreferences("notifications", Context.MODE_PRIVATE)
        sharedPrefs.edit()
            .putString("notification_list", "[]")
            .apply()

        showToast("Đã xóa tất cả thông báo")
    }

    /**
     * Mark all notifications as read
     */
    private fun markAllAsRead() {
        var changedCount = 0
        allNotifications.forEach { notification ->
            if (!notification.isRead) {
                notification.isRead = true
                changedCount++
            }
        }

        if (changedCount > 0) {
            notificationAdapter.notifyDataSetChanged()

            // Save to SharedPreferences
            try {
                val sharedPrefs = getSharedPreferences("notifications", Context.MODE_PRIVATE)
                val notificationsJson = sharedPrefs.getString("notification_list", "[]") ?: "[]"
                val notificationsList = JSONArray(notificationsJson)

                for (i in 0 until notificationsList.length()) {
                    val json = notificationsList.getJSONObject(i)
                    json.put("is_read", true)
                }

                sharedPrefs.edit()
                    .putString("notification_list", notificationsList.toString())
                    .apply()
            } catch (e: Exception) {
                Log.e(TAG, "Error marking all as read", e)
            }

            showToast("Đã đánh dấu $changedCount thông báo là đã đọc")
        } else {
            showToast("Tất cả thông báo đã được đọc")
        }
    }

    /**
     * Export notifications to file
     */
    private fun exportNotifications() {
        if (allNotifications.isEmpty()) {
            showToast("Không có thông báo để xuất")
            return
        }

        try {
            val exportText = buildString {
                appendLine("LỊCH SỬ THÔNG BÁO SMART PARKING")
                appendLine("=".repeat(40))
                appendLine("Thời gian xuất: ${SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(Date())}")
                appendLine("Tổng số thông báo: ${allNotifications.size}")
                appendLine()

                allNotifications.sortedByDescending { it.timestamp }.forEach { notification ->
                    appendLine("${formatTimestamp(notification.timestamp)}")
                    appendLine("${notification.title}")
                    appendLine("${notification.message}")
                    appendLine("Trạng thái: ${if (notification.isRead) "Đã đọc" else "Chưa đọc"}")
                    appendLine("Loại: ${getNotificationTypeText(notification.type)}")
                    appendLine("-".repeat(30))
                }
            }

            val shareIntent = Intent().apply {
                action = Intent.ACTION_SEND
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, exportText)
                putExtra(Intent.EXTRA_SUBJECT, "Lịch sử thông báo Smart Parking")
            }

            startActivity(Intent.createChooser(shareIntent, "Xuất danh sách thông báo"))

        } catch (e: Exception) {
            showToast("Lỗi khi xuất dữ liệu: ${e.message}")
        }
    }

    /**
     * Get notification type text - FIXED: Implemented all cases
     */
    private fun getNotificationTypeText(type: NotificationItem.Type): String {
        return when (type) {
            NotificationItem.Type.WRONG_POSITION -> "Đỗ sai vị trí"
            NotificationItem.Type.SPACE_AVAILABLE -> "Có chỗ trống"
            NotificationItem.Type.SPACE_FULL -> "Hết chỗ đỗ"
            NotificationItem.Type.POSITION_CORRECT -> "Đỗ đúng vị trí"
            NotificationItem.Type.OVERSTAY_WARNING -> "Cảnh báo quá thời gian"
            NotificationItem.Type.SYSTEM_UPDATE -> "Cập nhật hệ thống"
            NotificationItem.Type.PARKING_FULL -> "Bãi đỗ đầy"
            NotificationItem.Type.SPACE_LIMITED -> "Sắp hết chỗ"
            NotificationItem.Type.SYSTEM_MAINTENANCE -> "Bảo trì hệ thống"
            NotificationItem.Type.CONNECTION_LOST -> "Mất kết nối"
            NotificationItem.Type.CONNECTION_RESTORED -> "Đã kết nối lại"
            NotificationItem.Type.URGENT_ALERT -> "Cảnh báo khẩn cấp"
            NotificationItem.Type.VEHICLE_ENTERED -> "Xe vào bãi"
            NotificationItem.Type.VEHICLE_EXITED -> "Xe rời bãi"
            NotificationItem.Type.PAYMENT_DUE -> "Thanh toán đến hạn"
            NotificationItem.Type.PAYMENT_SUCCESS -> "Thanh toán thành công"
            NotificationItem.Type.SECURITY_ALERT -> "Cảnh báo an ninh"
            NotificationItem.Type.WEATHER_ALERT -> "Cảnh báo thời tiết"
            NotificationItem.Type.PROMOTION -> "Khuyến mãi"
            NotificationItem.Type.NEWS -> "Tin tức"
            NotificationItem.Type.REMINDER -> "Nhắc nhở"
        }
    }

    /**
     * Format timestamp to readable string
     */
    private fun formatTimestamp(timestamp: Long): String {
        val date = Date(timestamp)
        val now = Date()
        val diff = now.time - timestamp

        return when {
            diff < 60000 -> "Vừa xong" // Less than 1 minute
            diff < 3600000 -> "${diff / 60000} phút trước" // Less than 1 hour
            diff < 86400000 -> "${diff / 3600000} giờ trước" // Less than 1 day
            diff < 604800000 -> "${diff / 86400000} ngày trước" // Less than 1 week
            else -> SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(date)
        }
    }

    /**
     * Show toast message
     */
    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    /**
     * RecyclerView Adapter for notification history
     */
    inner class NotificationHistoryAdapter(
        private val notifications: List<NotificationItem>,
        private val onItemClick: (NotificationItem) -> Unit,
        private val onItemLongClick: (NotificationItem) -> Unit
    ) : RecyclerView.Adapter<NotificationHistoryAdapter.ViewHolder>() {

        inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val ivIcon: ImageView = itemView.findViewById(R.id.iv_notification_icon)
            val tvTitle: TextView = itemView.findViewById(R.id.tv_notification_title)
            val tvMessage: TextView = itemView.findViewById(R.id.tv_notification_message)
            val tvTime: TextView = itemView.findViewById(R.id.tv_notification_time)
            val viewUnread: View = itemView.findViewById(R.id.view_unread_indicator)
            val llPriority: LinearLayout = itemView.findViewById(R.id.ll_priority_indicator)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.notification_item, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val notification = notifications[position]

            holder.tvTitle.text = notification.title
            holder.tvMessage.text = notification.message
            holder.tvTime.text = formatTimestamp(notification.timestamp)

            // Set icon based on type - FIXED: Now handles all cases
            val (iconRes, iconColor) = getNotificationIconAndColor(notification.type)
            holder.ivIcon.setImageResource(iconRes)
            holder.ivIcon.setColorFilter(
                ContextCompat.getColor(this@NotificationHistoryActivity, iconColor)
            )

            // Show/hide unread indicator
            holder.viewUnread.visibility = if (notification.isRead) View.GONE else View.VISIBLE

            // Show/hide priority indicator
            if (notification.priority == NotificationItem.Priority.HIGH ||
                notification.priority == NotificationItem.Priority.URGENT) {
                holder.llPriority.visibility = View.VISIBLE
            } else {
                holder.llPriority.visibility = View.GONE
            }

            // Set item click listeners
            holder.itemView.setOnClickListener {
                onItemClick(notification)
            }

            holder.itemView.setOnLongClickListener {
                onItemLongClick(notification)
                true
            }
        }

        override fun getItemCount(): Int = notifications.size

        /**
         * Get notification icon and color - FIXED: All cases implemented
         */
        private fun getNotificationIconAndColor(type: NotificationItem.Type): Pair<Int, Int> {
            return when (type) {
                // Parking related
                NotificationItem.Type.WRONG_POSITION ->
                    R.drawable.ic_notifications to R.color.error_color
                NotificationItem.Type.POSITION_CORRECT ->
                    R.drawable.ic_parking_status to R.color.success_color
                NotificationItem.Type.OVERSTAY_WARNING ->
                    R.drawable.ic_notifications to R.color.warning_color

                // Space availability
                NotificationItem.Type.SPACE_AVAILABLE ->
                    R.drawable.ic_available_spaces to R.color.success_color
                NotificationItem.Type.SPACE_FULL,
                NotificationItem.Type.PARKING_FULL ->
                    R.drawable.ic_parking_status to R.color.error_color
                NotificationItem.Type.SPACE_LIMITED ->
                    R.drawable.ic_parking_status to R.color.warning_color

                // Vehicle movement
                NotificationItem.Type.VEHICLE_ENTERED ->
                    R.drawable.ic_notification to R.color.success_color
                NotificationItem.Type.VEHICLE_EXITED ->
                    R.drawable.ic_notification to R.color.info_color

                // System related
                NotificationItem.Type.SYSTEM_UPDATE ->
                    R.drawable.ic_settings to R.color.info_color
                NotificationItem.Type.SYSTEM_MAINTENANCE ->
                    R.drawable.ic_settings to R.color.warning_color
                NotificationItem.Type.CONNECTION_LOST ->
                    R.drawable.ic_notification to R.color.error_color
                NotificationItem.Type.CONNECTION_RESTORED ->
                    R.drawable.ic_notification to R.color.success_color

                // Payment related
                NotificationItem.Type.PAYMENT_DUE ->
                    R.drawable.ic_notification to R.color.warning_color
                NotificationItem.Type.PAYMENT_SUCCESS ->
                    R.drawable.ic_notification to R.color.success_color

                // Alerts
                NotificationItem.Type.URGENT_ALERT ->
                    R.drawable.ic_notification to R.color.error_color
                NotificationItem.Type.SECURITY_ALERT ->
                    R.drawable.ic_notification to R.color.error_color
                NotificationItem.Type.WEATHER_ALERT ->
                    R.drawable.ic_notification to R.color.warning_color

                // Other
                NotificationItem.Type.PROMOTION ->
                    R.drawable.ic_notification to R.color.success_color
                NotificationItem.Type.NEWS ->
                    R.drawable.ic_notification to R.color.info_color
                NotificationItem.Type.REMINDER ->
                    R.drawable.ic_notification to R.color.primary_color
            }
        }
    }
}