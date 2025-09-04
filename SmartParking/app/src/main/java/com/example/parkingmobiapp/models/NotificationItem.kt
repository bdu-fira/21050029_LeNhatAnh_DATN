package com.example.parkingmobiapp.models

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import java.text.SimpleDateFormat
import java.util.*

@Parcelize
data class NotificationItem(
    val id: Int,
    val title: String,
    val message: String,
    val timestamp: Long,
    val type: Type,
    var isRead: Boolean = false,
    var isImportant: Boolean = false,
    val data: Map<String, String> = emptyMap(),
    val actionUrl: String? = null,
    val expiryTime: Long? = null,
    val priority: Priority = Priority.NORMAL
) : Parcelable {

    enum class Type(val displayName: String, val emoji: String, val colorRes: String) {
        PARKING_FULL("Bãi đỗ đầy", "🚫", "error_color"),
        SPACE_AVAILABLE("Có chỗ trống", "✅", "success_color"),
        SPACE_LIMITED("Sắp hết chỗ", "⚠️", "warning_color"),
        SPACE_FULL("Hết chỗ đỗ", "🚫", "error_color"), // ✅ THÊM dòng này
        WRONG_POSITION("Đỗ sai vị trí", "❌", "error_color"),
        POSITION_CORRECT("Đỗ đúng vị trí", "✅", "success_color"),
        OVERSTAY_WARNING("Đỗ quá giờ", "⏰", "warning_color"),
        SYSTEM_UPDATE("Cập nhật hệ thống", "🔄", "info_color"),
        SYSTEM_MAINTENANCE("Bảo trì hệ thống", "🔧", "warning_color"),
        CONNECTION_LOST("Mất kết nối", "📡", "error_color"),
        CONNECTION_RESTORED("Kết nối phục hồi", "📶", "success_color"),
        URGENT_ALERT("Cảnh báo khẩn cấp", "🚨", "error_color"),
        VEHICLE_ENTERED("Xe vào bãi", "🚗", "info_color"),
        VEHICLE_EXITED("Xe ra bãi", "🚙", "info_color"),
        PAYMENT_DUE("Cần thanh toán", "💰", "warning_color"),
        PAYMENT_SUCCESS("Thanh toán thành công", "✅", "success_color"),
        SECURITY_ALERT("Cảnh báo an ninh", "🔒", "error_color"),
        WEATHER_ALERT("Cảnh báo thời tiết", "🌧️", "warning_color"),
        PROMOTION("Khuyến mãi", "🎉", "info_color"),
        NEWS("Tin tức", "📰", "info_color"),
        REMINDER("Nhắc nhở", "⏰", "info_color");

        companion object {
            fun fromString(value: String): Type {
                return values().find { it.name.equals(value, ignoreCase = true) } ?: SYSTEM_UPDATE
            }
        }
    }

    enum class Priority(val value: Int, val displayName: String) {
        LOW(1, "Thấp"),
        NORMAL(2, "Bình thường"),
        HIGH(3, "Cao"),
        URGENT(4, "Khẩn cấp");

        companion object {
            fun fromInt(value: Int): Priority {
                return values().find { it.value == value } ?: NORMAL
            }
        }
    }

    // Helper methods
    fun getFormattedTime(): String {
        val date = Date(timestamp)
        val now = Date()
        val diff = now.time - timestamp

        return when {
            diff < 60 * 1000 -> "Vừa xong"
            diff < 60 * 60 * 1000 -> "${diff / (60 * 1000)} phút trước"
            diff < 24 * 60 * 60 * 1000 -> "${diff / (60 * 60 * 1000)} giờ trước"
            else -> SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()).format(date)
        }
    }

    fun getDetailedTime(): String {
        return SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault()).format(Date(timestamp))
    }

    fun isExpired(): Boolean {
        return expiryTime?.let { System.currentTimeMillis() > it } ?: false
    }

    fun getTimeUntilExpiry(): String? {
        return expiryTime?.let { expiry ->
            val remaining = expiry - System.currentTimeMillis()
            when {
                remaining <= 0 -> "Đã hết hạn"
                remaining < 60 * 1000 -> "Hết hạn trong ${remaining / 1000}s"
                remaining < 60 * 60 * 1000 -> "Hết hạn trong ${remaining / (60 * 1000)}m"
                remaining < 24 * 60 * 60 * 1000 -> "Hết hạn trong ${remaining / (60 * 60 * 1000)}h"
                else -> "Hết hạn trong ${remaining / (24 * 60 * 60 * 1000)} ngày"
            }
        }
    }

    fun shouldShowBadge(): Boolean {
        return !isRead && !isExpired() && priority != Priority.LOW
    }

    fun getDisplayTitle(): String {
        val priorityPrefix = when (priority) {
            Priority.URGENT -> "🚨 "
            Priority.HIGH -> "⚡ "
            else -> ""
        }
        return "$priorityPrefix$title"
    }

    fun getShortMessage(maxLength: Int = 50): String {
        return if (message.length <= maxLength) {
            message
        } else {
            "${message.take(maxLength)}..."
        }
    }

    fun hasAction(): Boolean {
        return !actionUrl.isNullOrEmpty()
    }

    fun getDataValue(key: String): String? {
        return data[key]
    }

    // Predefined notification creators
    companion object {
        fun createParkingFullNotification(totalSpaces: Int = 0, lastAvailableTime: String = ""): NotificationItem {
            val message = if (totalSpaces > 0) {
                "Tất cả $totalSpaces chỗ đỗ đã có xe"
            } else {
                "Bãi đỗ xe hiện tại đã đầy"
            }

            val data = mutableMapOf<String, String>().apply {
                if (totalSpaces > 0) put("total_spaces", totalSpaces.toString())
                if (lastAvailableTime.isNotEmpty()) put("last_available_time", lastAvailableTime)
                put("availability", "0")
                put("status", "full")
            }

            return NotificationItem(
                id = System.currentTimeMillis().toInt(),
                title = "🚫 Bãi đỗ xe đã đầy",
                message = message,
                timestamp = System.currentTimeMillis(),
                type = Type.PARKING_FULL,
                isImportant = true,
                priority = Priority.HIGH,
                data = data,
                expiryTime = System.currentTimeMillis() + (60 * 60 * 1000) // Expires in 1 hour
            )
        }

        fun createSpaceAvailableNotification(availableSpaces: Int, totalSpaces: Int = 0): NotificationItem {
            val message = if (totalSpaces > 0) {
                "Hiện có $availableSpaces/$totalSpaces chỗ đỗ trống"
            } else {
                "Hiện có $availableSpaces chỗ đỗ trống"
            }

            val data = mutableMapOf<String, String>().apply {
                put("available_spaces", availableSpaces.toString())
                if (totalSpaces > 0) put("total_spaces", totalSpaces.toString())
                put("status", "available")
                put("percentage", ((totalSpaces - availableSpaces) * 100 / totalSpaces).toString())
            }

            return NotificationItem(
                id = System.currentTimeMillis().toInt(),
                title = "✅ Có chỗ đỗ trống!",
                message = message,
                timestamp = System.currentTimeMillis(),
                type = Type.SPACE_AVAILABLE,
                isImportant = true,
                priority = Priority.HIGH,
                data = data,
                expiryTime = System.currentTimeMillis() + (30 * 60 * 1000) // Expires in 30 minutes
            )
        }

        fun createSpaceLimitedNotification(availableSpaces: Int, totalSpaces: Int): NotificationItem {
            val percentage = ((totalSpaces - availableSpaces) * 100 / totalSpaces)
            val message = "Chỉ còn $availableSpaces chỗ trống ($percentage% đã sử dụng)"

            val data = mapOf(
                "available_spaces" to availableSpaces.toString(),
                "total_spaces" to totalSpaces.toString(),
                "percentage" to percentage.toString(),
                "status" to "limited"
            )

            return NotificationItem(
                id = System.currentTimeMillis().toInt(),
                title = "⚠️ Sắp hết chỗ đỗ",
                message = message,
                timestamp = System.currentTimeMillis(),
                type = Type.SPACE_LIMITED,
                isImportant = true,
                priority = Priority.HIGH,
                data = data,
                expiryTime = System.currentTimeMillis() + (20 * 60 * 1000) // Expires in 20 minutes
            )
        }

        fun createSystemMaintenanceNotification(maintenanceType: String, duration: String = ""): NotificationItem {
            val message = if (duration.isNotEmpty()) {
                "Hệ thống đang bảo trì: $maintenanceType (Dự kiến: $duration)"
            } else {
                "Hệ thống đang bảo trì: $maintenanceType"
            }

            val data = mutableMapOf<String, String>().apply {
                put("maintenance_type", maintenanceType)
                if (duration.isNotEmpty()) put("duration", duration)
                put("status", "maintenance")
            }

            return NotificationItem(
                id = System.currentTimeMillis().toInt(),
                title = "🔧 Bảo trì hệ thống",
                message = message,
                timestamp = System.currentTimeMillis(),
                type = Type.SYSTEM_MAINTENANCE,
                priority = Priority.NORMAL,
                data = data
            )
        }

        fun createUrgentAlertNotification(alertType: String, alertMessage: String): NotificationItem {
            val data = mapOf(
                "alert_type" to alertType,
                "alert_level" to "urgent",
                "requires_action" to "true"
            )

            return NotificationItem(
                id = System.currentTimeMillis().toInt(),
                title = "🚨 Cảnh báo khẩn cấp",
                message = "$alertType: $alertMessage",
                timestamp = System.currentTimeMillis(),
                type = Type.URGENT_ALERT,
                isImportant = true,
                priority = Priority.URGENT,
                data = data,
                expiryTime = System.currentTimeMillis() + (4 * 60 * 60 * 1000) // Expires in 4 hours
            )
        }

        fun createVehicleStatusNotification(plateNumber: String, action: String, location: String = ""): NotificationItem {
            val actionType = when (action.lowercase()) {
                "enter", "entered" -> Type.VEHICLE_ENTERED
                "exit", "exited" -> Type.VEHICLE_EXITED
                else -> Type.SYSTEM_UPDATE
            }

            val title = when (actionType) {
                Type.VEHICLE_ENTERED -> "🚗 Xe vào bãi"
                Type.VEHICLE_EXITED -> "🚙 Xe ra bãi"
                else -> "🚗 Cập nhật xe"
            }

            val message = if (location.isNotEmpty()) {
                "Xe $plateNumber đã ${if (action == "enter") "vào" else "ra"} bãi tại $location"
            } else {
                "Xe $plateNumber đã ${if (action == "enter") "vào" else "ra"} bãi"
            }

            val data = mutableMapOf<String, String>().apply {
                put("plate_number", plateNumber)
                put("action", action)
                if (location.isNotEmpty()) put("location", location)
                put("vehicle_status", action)
            }

            return NotificationItem(
                id = System.currentTimeMillis().toInt(),
                title = title,
                message = message,
                timestamp = System.currentTimeMillis(),
                type = actionType,
                priority = Priority.NORMAL,
                data = data,
                expiryTime = System.currentTimeMillis() + (24 * 60 * 60 * 1000) // Expires in 24 hours
            )
        }

        fun createPaymentNotification(amount: Double, status: String, plateNumber: String = ""): NotificationItem {
            val paymentType = when (status.lowercase()) {
                "due", "pending" -> Type.PAYMENT_DUE
                "success", "completed" -> Type.PAYMENT_SUCCESS
                else -> Type.SYSTEM_UPDATE
            }

            val title = when (paymentType) {
                Type.PAYMENT_DUE -> "💰 Cần thanh toán"
                Type.PAYMENT_SUCCESS -> "✅ Thanh toán thành công"
                else -> "💳 Thông tin thanh toán"
            }

            val formattedAmount = String.format("%,.0f", amount)
            val message = if (plateNumber.isNotEmpty()) {
                when (paymentType) {
                    Type.PAYMENT_DUE -> "Xe $plateNumber cần thanh toán ${formattedAmount}đ"
                    Type.PAYMENT_SUCCESS -> "Đã thanh toán ${formattedAmount}đ cho xe $plateNumber"
                    else -> "Thông tin thanh toán: ${formattedAmount}đ"
                }
            } else {
                when (paymentType) {
                    Type.PAYMENT_DUE -> "Cần thanh toán ${formattedAmount}đ"
                    Type.PAYMENT_SUCCESS -> "Đã thanh toán ${formattedAmount}đ thành công"
                    else -> "Thông tin thanh toán: ${formattedAmount}đ"
                }
            }

            val data = mutableMapOf<String, String>().apply {
                put("amount", amount.toString())
                put("status", status)
                if (plateNumber.isNotEmpty()) put("plate_number", plateNumber)
                put("currency", "VND")
            }

            val priority = if (paymentType == Type.PAYMENT_DUE) Priority.HIGH else Priority.NORMAL

            return NotificationItem(
                id = System.currentTimeMillis().toInt(),
                title = title,
                message = message,
                timestamp = System.currentTimeMillis(),
                type = paymentType,
                isImportant = paymentType == Type.PAYMENT_DUE,
                priority = priority,
                data = data,
                expiryTime = if (paymentType == Type.PAYMENT_DUE) {
                    System.currentTimeMillis() + (24 * 60 * 60 * 1000) // Payment due expires in 24 hours
                } else {
                    null
                }
            )
        }

        fun createConnectionStatusNotification(isConnected: Boolean, serverInfo: String = ""): NotificationItem {
            val connectionType = if (isConnected) Type.CONNECTION_RESTORED else Type.CONNECTION_LOST

            val title = if (isConnected) "📶 Kết nối phục hồi" else "📡 Mất kết nối"

            val message = if (isConnected) {
                if (serverInfo.isNotEmpty()) "Đã kết nối lại với $serverInfo" else "Đã kết nối lại với server"
            } else {
                "Mất kết nối với server. Hiển thị dữ liệu đã lưu."
            }

            val data = mutableMapOf<String, String>().apply {
                put("connection_status", if (isConnected) "connected" else "disconnected")
                if (serverInfo.isNotEmpty()) put("server_info", serverInfo)
                put("timestamp", System.currentTimeMillis().toString())
            }

            return NotificationItem(
                id = System.currentTimeMillis().toInt(),
                title = title,
                message = message,
                timestamp = System.currentTimeMillis(),
                type = connectionType,
                priority = if (isConnected) Priority.NORMAL else Priority.HIGH,
                data = data,
                expiryTime = System.currentTimeMillis() + (2 * 60 * 60 * 1000) // Expires in 2 hours
            )
        }

        fun createReminderNotification(reminderTitle: String, reminderMessage: String, actionUrl: String? = null): NotificationItem {
            return NotificationItem(
                id = System.currentTimeMillis().toInt(),
                title = "⏰ $reminderTitle",
                message = reminderMessage,
                timestamp = System.currentTimeMillis(),
                type = Type.REMINDER,
                priority = Priority.NORMAL,
                actionUrl = actionUrl,
                data = mapOf(
                    "reminder_type" to "general",
                    "has_action" to (actionUrl != null).toString()
                ),
                expiryTime = System.currentTimeMillis() + (24 * 60 * 60 * 1000) // Expires in 24 hours
            )
        }
    }

    // Utility functions for notification management
    fun markAsRead(): NotificationItem {
        return this.copy(isRead = true)
    }

    fun markAsImportant(): NotificationItem {
        return this.copy(isImportant = true)
    }

    fun updatePriority(newPriority: Priority): NotificationItem {
        return this.copy(priority = newPriority)
    }

    fun addData(key: String, value: String): NotificationItem {
        val newData = data.toMutableMap()
        newData[key] = value
        return this.copy(data = newData)
    }

    fun removeData(key: String): NotificationItem {
        val newData = data.toMutableMap()
        newData.remove(key)
        return this.copy(data = newData)
    }

    fun extendExpiry(additionalMillis: Long): NotificationItem {
        val newExpiry = expiryTime?.let { it + additionalMillis } ?: (System.currentTimeMillis() + additionalMillis)
        return this.copy(expiryTime = newExpiry)
    }

    fun toSummaryString(): String {
        return "${type.displayName} | ${getFormattedTime()} | ${if (isRead) "Đã đọc" else "Chưa đọc"}"
    }

    fun toDetailString(): String {
        return buildString {
            appendLine("ID: $id")
            appendLine("Tiêu đề: $title")
            appendLine("Nội dung: $message")
            appendLine("Loại: ${type.displayName} ${type.emoji}")
            appendLine("Thời gian: ${getDetailedTime()}")
            appendLine("Độ ưu tiên: ${priority.displayName}")
            appendLine("Trạng thái: ${if (isRead) "Đã đọc" else "Chưa đọc"}")
            appendLine("Quan trọng: ${if (isImportant) "Có" else "Không"}")

            if (expiryTime != null) {
                appendLine("Hết hạn: ${getTimeUntilExpiry()}")
            }

            if (actionUrl != null) {
                appendLine("Có hành động: $actionUrl")
            }

            if (data.isNotEmpty()) {
                appendLine("Dữ liệu bổ sung:")
                data.forEach { (key, value) ->
                    appendLine("  $key: $value")
                }
            }
        }
    }
}