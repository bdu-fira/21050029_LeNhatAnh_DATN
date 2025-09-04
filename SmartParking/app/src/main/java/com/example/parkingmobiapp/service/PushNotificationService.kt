package com.example.parkingmobiapp.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.parkingmobiapp.MainActivity
import com.example.parkingmobiapp.R
import com.example.parkingmobiapp.models.NotificationItem
import com.example.parkingmobiapp.utils.SharedPrefsHelper
import android.util.Log
import android.app.NotificationChannelGroup
import androidx.core.app.NotificationManagerCompat

class PushNotificationService {

    companion object {
        private const val TAG = "PushNotificationService"

        // Notification Channels
        private const val CHANNEL_PARKING_STATUS = "parking_status"
        private const val CHANNEL_SPACE_ALERTS = "space_alerts"
        private const val CHANNEL_SYSTEM_UPDATES = "system_updates"
        private const val CHANNEL_URGENT_ALERTS = "urgent_alerts"
        private const val CHANNEL_VEHICLE_ENTRY = "vehicle_entry"
        private const val CHANNEL_VEHICLE_EXIT = "vehicle_exit"

        // Channel Groups
        private const val GROUP_PARKING = "parking_group"
        private const val GROUP_SYSTEM = "system_group"

        // Notification IDs
        private const val NOTIFICATION_PARKING_FULL = 1001
        private const val NOTIFICATION_SPACE_AVAILABLE = 1002
        private const val NOTIFICATION_PARKING_STATUS = 1003
        private const val NOTIFICATION_SYSTEM_UPDATE = 1004
        private const val NOTIFICATION_URGENT_ALERT = 1005
    }





    fun showVehicleNotification(
        context: Context,
        title: String,
        message: String,
        plateNumber: String,
        action: String,
        imageUrl: String? = null
    ) {
        try {
            val channelId = if (action == "entry")
                CHANNEL_VEHICLE_ENTRY else CHANNEL_VEHICLE_EXIT

            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                putExtra("notification_type", "vehicle")
                putExtra("plate_number", plateNumber)
                putExtra("action", action)
            }

            val pendingIntent = PendingIntent.getActivity(
                context,
                System.currentTimeMillis().toInt(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val notification = NotificationCompat.Builder(context, channelId)
                .setSmallIcon(R.drawable.ic_notification) // Đổi icon phù hợp
                .setContentTitle(title)
                .setContentText(message)
                .setStyle(NotificationCompat.BigTextStyle().bigText(message))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .build()

            NotificationManagerCompat.from(context).notify(System.currentTimeMillis().toInt(), notification)

            Log.d(TAG, "✅ Vehicle notification sent: $title")

        } catch (e: Exception) {
            Log.e(TAG, "❌ Error showing vehicle notification", e)
        }
    }




    // Thêm method tạo vehicle channels
    private fun createVehicleChannels(notificationManager: NotificationManager, context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Channel cho xe vào
            val entryChannel = NotificationChannel(
                CHANNEL_VEHICLE_ENTRY,
                "🚗 Xe vào bãi",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Thông báo khi xe vào bãi đỗ"
                group = GROUP_PARKING
                enableLights(true)
                lightColor = ContextCompat.getColor(context, R.color.success_color)
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 250, 250, 250)
                setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION), null)
            }
            notificationManager.createNotificationChannel(entryChannel)

            // Channel cho xe ra
            val exitChannel = NotificationChannel(
                CHANNEL_VEHICLE_EXIT,
                "🚗 Xe ra khỏi bãi",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Thông báo khi xe ra khỏi bãi đỗ"
                group = GROUP_PARKING
                enableLights(true)
                lightColor = ContextCompat.getColor(context, R.color.info_color)
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 250, 250, 250)
                setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION), null)
            }
            notificationManager.createNotificationChannel(exitChannel)
        }
    }
    /**
     * ✅ Initialize notification channels with enhanced categories
     */
    fun initializeNotificationChannels(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            try {
                // Create notification channel groups
                createNotificationGroups(notificationManager)

                // Create individual channels
                createParkingStatusChannel(notificationManager, context)
                createSpaceAlertChannel(notificationManager, context)
                createSystemUpdateChannel(notificationManager, context)
                createUrgentAlertChannel(notificationManager, context)
                createVehicleChannels(notificationManager, context) // ✅ THÊM

                Log.d(TAG, "✅ All notification channels initialized successfully")

            } catch (e: Exception) {
                Log.e(TAG, "❌ Error initializing notification channels", e)
            }
        }
    }

    private fun createNotificationGroups(notificationManager: NotificationManager) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Parking related notifications
            val parkingGroup = NotificationChannelGroup(
                GROUP_PARKING,
                "🅿️ Thông báo bãi đỗ xe"
            )

            // System related notifications
            val systemGroup = NotificationChannelGroup(
                GROUP_SYSTEM,
                "⚙️ Thông báo hệ thống"
            )

            notificationManager.createNotificationChannelGroups(listOf(parkingGroup, systemGroup))
        }
    }

    private fun createParkingStatusChannel(notificationManager: NotificationManager, context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_PARKING_STATUS,
                "📊 Trạng thái bãi đỗ",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Thông báo về tình trạng chỗ đỗ xe hiện tại"
                group = GROUP_PARKING
                enableLights(true)
                lightColor = ContextCompat.getColor(context, R.color.info_color)
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 250, 250, 250)
                setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION), null)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createSpaceAlertChannel(notificationManager: NotificationManager, context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_SPACE_ALERTS,
                "🚨 Cảnh báo chỗ đỗ",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Cảnh báo khi bãi đỗ đầy hoặc có chỗ trống"
                group = GROUP_PARKING
                enableLights(true)
                lightColor = ContextCompat.getColor(context, R.color.warning_color)
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 300, 200, 300, 200, 300)
                setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION), null)
                setShowBadge(true)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createSystemUpdateChannel(notificationManager: NotificationManager, context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_SYSTEM_UPDATES,
                "🔄 Cập nhật hệ thống",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Thông báo về cập nhật và bảo trì hệ thống"
                group = GROUP_SYSTEM
                enableLights(false)
                enableVibration(false)
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createUrgentAlertChannel(notificationManager: NotificationManager, context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_URGENT_ALERTS,
                "🚨 Cảnh báo khẩn cấp",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Cảnh báo khẩn cấp về bãi đỗ xe"
                group = GROUP_PARKING
                enableLights(true)
                lightColor = ContextCompat.getColor(context, R.color.error_color)
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 500, 300, 500, 300, 500)
                setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM), null)
                setShowBadge(true)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    /**
     * ✅ Show enhanced parking full notification
     */
    fun showParkingFullNotification(context: Context, totalSpaces: Int = 0, lastAvailableTime: String = "") {
        val sharedPrefs = SharedPrefsHelper(context)
        if (!sharedPrefs.isNotificationEnabled()) {
            Log.d(TAG, "Notifications disabled by user")
            return
        }

        try {
            val title = "🚫 Bãi đỗ xe đã đầy!"
            val message = if (totalSpaces > 0) {
                "Tất cả $totalSpaces chỗ đỗ đã có xe. Vui lòng chờ hoặc tìm bãi khác."
            } else {
                "Hiện tại không còn chỗ đỗ trống. Vui lòng chờ hoặc tìm bãi khác."
            }

            val expandedMessage = buildString {
                appendLine("📍 Tình trạng: Đầy 100%")
                if (totalSpaces > 0) {
                    appendLine("🅿️ Tổng chỗ đỗ: $totalSpaces")
                }
                if (lastAvailableTime.isNotEmpty()) {
                    appendLine("⏰ Lần cuối có chỗ: $lastAvailableTime")
                }
                appendLine("💡 Gợi ý: Thử lại sau 15-30 phút")
                appendLine("📱 Bật thông báo để nhận tin khi có chỗ trống")
            }

            val notification = createEnhancedNotification(
                context = context,
                channelId = CHANNEL_SPACE_ALERTS,
                title = title,
                message = message,
                expandedMessage = expandedMessage,
                notificationId = NOTIFICATION_PARKING_FULL,
                iconRes = R.drawable.ic_parking_full,
                color = ContextCompat.getColor(context, R.color.error_color),
                priority = NotificationCompat.PRIORITY_HIGH,
                autoCancel = true,
                showProgress = false
            )

            NotificationManagerCompat.from(context).notify(NOTIFICATION_PARKING_FULL, notification)

            Log.i(TAG, "✅ Parking full notification sent")

        } catch (e: Exception) {
            Log.e(TAG, "❌ Error showing parking full notification", e)
        }
    }

    /**
     * ✅ Show enhanced space available notification
     */
    fun showSpaceAvailableNotification(
        context: Context,
        availableSpaces: Int,
        totalSpaces: Int = 0,
        estimatedWaitTime: String = ""
    ) {
        val sharedPrefs = SharedPrefsHelper(context)
        if (!sharedPrefs.isNotificationEnabled()) return

        try {
            val title = "✅ Có chỗ đỗ trống!"
            val message = "Hiện có $availableSpaces chỗ đỗ trống. Nhanh tay đến bãi xe!"

            val expandedMessage = buildString {
                appendLine("🅿️ Chỗ trống: $availableSpaces/${totalSpaces}")
                appendLine("📊 Tỷ lệ: ${((totalSpaces - availableSpaces) * 100 / totalSpaces)}% đã sử dụng")
                if (estimatedWaitTime.isNotEmpty()) {
                    appendLine("⏱️ Thời gian chờ ước tính: $estimatedWaitTime")
                }
                appendLine("🚗 Hãy đến ngay để đảm bảo có chỗ đỗ")
                appendLine("📍 Mở app để xem vị trí chỗ trống")
            }

            val notification = createEnhancedNotification(
                context = context,
                channelId = CHANNEL_SPACE_ALERTS,
                title = title,
                message = message,
                expandedMessage = expandedMessage,
                notificationId = NOTIFICATION_SPACE_AVAILABLE,
                iconRes = R.drawable.ic_parking_available,
                color = ContextCompat.getColor(context, R.color.success_color),
                priority = NotificationCompat.PRIORITY_HIGH,
                autoCancel = true,
                showProgress = false
            )

            NotificationManagerCompat.from(context).notify(NOTIFICATION_SPACE_AVAILABLE, notification)

            Log.i(TAG, "✅ Space available notification sent: $availableSpaces spaces")

        } catch (e: Exception) {
            Log.e(TAG, "❌ Error showing space available notification", e)
        }
    }

    /**
     * ✅ Show parking status update notification
     */
    fun showParkingStatusNotification(
        context: Context,
        available: Int,
        total: Int,
        percentage: Int,
        trend: String = ""
    ) {
        val sharedPrefs = SharedPrefsHelper(context)
        if (!sharedPrefs.isNotificationEnabled()) return

        try {
            val statusEmoji = when {
                available == 0 -> "🚫"
                available <= 3 -> "⚠️"
                available <= total * 0.2 -> "🟡"
                else -> "✅"
            }

            val title = "$statusEmoji Cập nhật bãi đỗ xe"
            val message = "$available/$total chỗ đỗ trống ($percentage% đã sử dụng)"

            val expandedMessage = buildString {
                appendLine("📊 Thống kê hiện tại:")
                appendLine("• Chỗ trống: $available")
                appendLine("• Đã sử dụng: ${total - available}")
                appendLine("• Tổng số: $total")
                appendLine("• Tỷ lệ sử dụng: $percentage%")
                if (trend.isNotEmpty()) {
                    appendLine("📈 Xu hướng: $trend")
                }
                appendLine("🔄 Cập nhật lúc: ${java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date())}")
            }

            val color = when {
                available == 0 -> R.color.error_color
                available <= 3 -> R.color.warning_color
                else -> R.color.info_color
            }

            val notification = createEnhancedNotification(
                context = context,
                channelId = CHANNEL_PARKING_STATUS,
                title = title,
                message = message,
                expandedMessage = expandedMessage,
                notificationId = NOTIFICATION_PARKING_STATUS,
                iconRes = R.drawable.ic_parking_status,
                color = ContextCompat.getColor(context, color),
                priority = NotificationCompat.PRIORITY_DEFAULT,
                autoCancel = true,
                showProgress = false
            )

            NotificationManagerCompat.from(context).notify(NOTIFICATION_PARKING_STATUS, notification)

            Log.d(TAG, "✅ Parking status notification sent")

        } catch (e: Exception) {
            Log.e(TAG, "❌ Error showing parking status notification", e)
        }
    }

    /**
     * ✅ Show system maintenance notification
     */
    fun showSystemMaintenanceNotification(
        context: Context,
        maintenanceType: String,
        estimatedDuration: String = "",
        affectedFeatures: List<String> = emptyList()
    ) {
        try {
            val title = "🔧 Bảo trì hệ thống"
            val message = "Hệ thống đang được bảo trì: $maintenanceType"

            val expandedMessage = buildString {
                appendLine("🛠️ Loại bảo trì: $maintenanceType")
                if (estimatedDuration.isNotEmpty()) {
                    appendLine("⏰ Thời gian dự kiến: $estimatedDuration")
                }
                if (affectedFeatures.isNotEmpty()) {
                    appendLine("📋 Tính năng ảnh hưởng:")
                    affectedFeatures.forEach { feature ->
                        appendLine("• $feature")
                    }
                }
                appendLine("🙏 Xin lỗi vì sự bất tiện này")
                appendLine("📱 App vẫn hiển thị dữ liệu cache")
            }

            val notification = createEnhancedNotification(
                context = context,
                channelId = CHANNEL_SYSTEM_UPDATES,
                title = title,
                message = message,
                expandedMessage = expandedMessage,
                notificationId = NOTIFICATION_SYSTEM_UPDATE,
                iconRes = R.drawable.ic_system_update,
                color = ContextCompat.getColor(context, R.color.warning_color),
                priority = NotificationCompat.PRIORITY_LOW,
                autoCancel = true,
                showProgress = false
            )

            NotificationManagerCompat.from(context).notify(NOTIFICATION_SYSTEM_UPDATE, notification)

            Log.d(TAG, "✅ System maintenance notification sent")

        } catch (e: Exception) {
            Log.e(TAG, "❌ Error showing system maintenance notification", e)
        }
    }

    /**
     * ✅ Show urgent alert notification
     */
    fun showUrgentAlertNotification(
        context: Context,
        alertType: String,
        alertMessage: String,
        actionRequired: String = ""
    ) {
        try {
            val title = "🚨 Cảnh báo khẩn cấp"
            val message = "$alertType: $alertMessage"

            val expandedMessage = buildString {
                appendLine("⚠️ Loại cảnh báo: $alertType")
                appendLine("📢 Nội dung: $alertMessage")
                if (actionRequired.isNotEmpty()) {
                    appendLine("🎯 Hành động cần thiết: $actionRequired")
                }
                appendLine("📞 Liên hệ bảo vệ nếu cần thiết")
                appendLine("🕒 Thời gian: ${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())}")
            }

            val notification = createEnhancedNotification(
                context = context,
                channelId = CHANNEL_URGENT_ALERTS,
                title = title,
                message = message,
                expandedMessage = expandedMessage,
                notificationId = NOTIFICATION_URGENT_ALERT,
                iconRes = R.drawable.ic_urgent_alert,
                color = ContextCompat.getColor(context, R.color.error_color),
                priority = NotificationCompat.PRIORITY_MAX,
                autoCancel = false,
                showProgress = false
            )

            NotificationManagerCompat.from(context).notify(NOTIFICATION_URGENT_ALERT, notification)

            Log.w(TAG, "🚨 Urgent alert notification sent: $alertType")

        } catch (e: Exception) {
            Log.e(TAG, "❌ Error showing urgent alert notification", e)
        }
    }

    /**
     * ✅ Create enhanced notification with rich content
     */
    private fun createEnhancedNotification(
        context: Context,
        channelId: String,
        title: String,
        message: String,
        expandedMessage: String,
        notificationId: Int,
        iconRes: Int,
        color: Int,
        priority: Int,
        autoCancel: Boolean,
        showProgress: Boolean,
        progressMax: Int = 100,
        progressCurrent: Int = 0
    ): Notification {

        // Create intent for when notification is tapped
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            putExtra("notification_id", notificationId)
            putExtra("notification_type", channelId)
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            notificationId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Build the notification
        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(iconRes)
            .setLargeIcon(BitmapFactory.decodeResource(context.resources, R.drawable.ic_parking_logo))
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(expandedMessage))
            .setContentIntent(pendingIntent)
            .setAutoCancel(autoCancel)
            .setPriority(priority)
            .setColor(color)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setShowWhen(true)
            .setWhen(System.currentTimeMillis())

        // Add progress bar if needed
        if (showProgress) {
            builder.setProgress(progressMax, progressCurrent, false)
        }

        // Add action buttons based on notification type
        when (channelId) {
            CHANNEL_SPACE_ALERTS -> {
                val openAppIntent = Intent(context, MainActivity::class.java)
                val openAppPendingIntent = PendingIntent.getActivity(
                    context,
                    notificationId + 1000,
                    openAppIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                builder.addAction(R.drawable.ic_parking_logo, "Mở App", openAppPendingIntent)
            }
        }

        return builder.build()
    }

    /**
     * ✅ Generic notification method for backward compatibility
     */
    fun showNotification(context: Context, title: String, message: String) {
        try {
            val notification = createEnhancedNotification(
                context = context,
                channelId = CHANNEL_PARKING_STATUS,
                title = title,
                message = message,
                expandedMessage = message,
                notificationId = System.currentTimeMillis().toInt(),
                iconRes = R.drawable.ic_notification,
                color = ContextCompat.getColor(context, R.color.info_color),
                priority = NotificationCompat.PRIORITY_DEFAULT,
                autoCancel = true,
                showProgress = false
            )

            NotificationManagerCompat.from(context).notify(System.currentTimeMillis().toInt(), notification)

            Log.d(TAG, "✅ Generic notification sent: $title")

        } catch (e: Exception) {
            Log.e(TAG, "❌ Error showing generic notification", e)
        }
    }

    /**
     * ✅ Cancel specific notification
     */
    fun cancelNotification(context: Context, notificationId: Int) {
        NotificationManagerCompat.from(context).cancel(notificationId)
        Log.d(TAG, "🗑️ Notification cancelled: $notificationId")
    }

    /**
     * ✅ Cancel all notifications
     */
    fun cancelAllNotifications(context: Context) {
        NotificationManagerCompat.from(context).cancelAll()
        Log.d(TAG, "🗑️ All notifications cancelled")
    }

    /**
     * ✅ Check if notifications are enabled
     */
    fun areNotificationsEnabled(context: Context): Boolean {
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }
    fun createNotificationChannels(context: Context) {
        initializeNotificationChannels(context)
    }
}
