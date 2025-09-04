package com.example.parkingmobiapp.services

import android.content.Context
import android.util.Log
import com.example.parkingmobiapp.models.NotificationItem
import com.example.parkingmobiapp.service.ParkingStatusService
import com.example.parkingmobiapp.utils.SharedPrefsHelper
import io.socket.client.IO
import io.socket.client.Socket
import org.json.JSONObject
import org.json.JSONException
import kotlinx.coroutines.*
import java.net.URISyntaxException
import java.util.concurrent.ConcurrentHashMap
import java.text.SimpleDateFormat
import java.util.*
import android.os.Build
import com.example.parkingmobiapp.models.VehicleNotification

/**
 * ✅ COMPLETE WebSocket Manager - Vehicle Notification Integration
 * Quản lý kết nối WebSocket và xử lý thông báo xe real-time
 */
class WebSocketManager private constructor(
    private val context: Context,
    private val sharedPrefsHelper: SharedPrefsHelper
) {

    companion object {
        private const val TAG = "WebSocketManager"
        private const val RECONNECT_DELAY = 5000L // 5 seconds
        private const val MAX_RECONNECT_ATTEMPTS = 10
        private const val PING_INTERVAL = 30000L // 30 seconds
        private const val CONNECTION_TIMEOUT = 15000L // 15 seconds
        private const val INITIAL_RECONNECT_DELAY = 2000L // 2 seconds

        @Volatile
        private var INSTANCE: WebSocketManager? = null

        fun getInstance(context: Context, sharedPrefsHelper: SharedPrefsHelper): WebSocketManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: WebSocketManager(context, sharedPrefsHelper).also { INSTANCE = it }
            }
        }
    }

    // Socket.IO client
    private var socket: Socket? = null

    // Connection state
    private var isConnected = false
    private var reconnectAttempts = 0
    private var lastConnectionAttempt = 0L
    private var lastPongReceived = 0L

    // Coroutines
    private var connectionJob: Job? = null
    private var pingJob: Job? = null
    private val coroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // ✅ ENHANCED: Vehicle-specific callbacks
    private val vehicleCallbacks = ConcurrentHashMap<String, (VehicleNotification) -> Unit>()
    private val statusUpdateCallbacks = ConcurrentHashMap<String, (ParkingStatusService.ParkingStatusData) -> Unit>()
    private val notificationCallbacks = ConcurrentHashMap<String, (NotificationItem) -> Unit>()
    private val connectionCallbacks = ConcurrentHashMap<String, (Boolean) -> Unit>()

    // Services
    private val notificationService = PushNotificationService()

    /**
     * ✅ MAIN CONNECTION METHOD
     */
    fun connect() {
        if (isConnected) {
            Log.d(TAG, "✅ Already connected to WebSocket")
            return
        }

        val currentTime = System.currentTimeMillis()
        if (currentTime - lastConnectionAttempt < INITIAL_RECONNECT_DELAY) {
            Log.d(TAG, "⏰ Connection attempt too soon, waiting...")
            return
        }
        lastConnectionAttempt = currentTime

        val serverUrl = getValidatedServerUrl()
        if (serverUrl.isEmpty()) {
            Log.w(TAG, "❌ No valid server URL configured")
            notifyConnectionCallbacks(false)
            return
        }

        Log.i(TAG, "🔌 Starting WebSocket connection to: $serverUrl")

        connectionJob = coroutineScope.launch {
            try {
                connectToServer(serverUrl)
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error connecting to WebSocket", e)
                handleConnectionError()
            }
        }
    }

    /**
     * ✅ SERVER URL VALIDATION
     */
    private fun getValidatedServerUrl(): String {
        val rawUrl = sharedPrefsHelper.getServerUrl()
        Log.d(TAG, "🔍 Raw server URL from prefs: '$rawUrl'")

        if (rawUrl.isEmpty()) {
            Log.w(TAG, "❌ Server URL is empty")
            return ""
        }

        return try {
            val cleanUrl = rawUrl.trim().removeSuffix("/")
            Log.d(TAG, "🧹 Cleaned URL: '$cleanUrl'")

            val finalUrl = when {
                cleanUrl.startsWith("http://") || cleanUrl.startsWith("https://") -> {
                    Log.d(TAG, "✅ URL already has protocol: $cleanUrl")
                    cleanUrl
                }
                cleanUrl.contains(":") -> {
                    val formattedUrl = "http://$cleanUrl"
                    Log.d(TAG, "🔧 Auto-formatted URL: $formattedUrl")
                    formattedUrl
                }
                else -> {
                    Log.w(TAG, "❌ Invalid server URL format: $cleanUrl")
                    ""
                }
            }

            Log.i(TAG, "🎯 Final WebSocket URL: $finalUrl")
            finalUrl

        } catch (e: Exception) {
            Log.e(TAG, "❌ Error validating server URL: $rawUrl", e)
            ""
        }
    }

    /**
     * ✅ SOCKET.IO CONNECTION SETUP
     */
    private suspend fun connectToServer(serverUrl: String) {
        try {
            Log.i(TAG, "🔌 Connecting to Socket.IO server: $serverUrl")

            val options = IO.Options().apply {
                forceNew = true
                reconnection = false
                timeout = CONNECTION_TIMEOUT
                transports = arrayOf("websocket", "polling")
                upgrade = true
                rememberUpgrade = true

                // ✅ ENHANCED: Authentication with vehicle info
                val vehicleSession = sharedPrefsHelper.getVehicleSession()
                if (vehicleSession != null) {
                    auth = mapOf(
                        "plateNumber" to vehicleSession.plateNumber,
                        "ownerPhone" to vehicleSession.ownerPhone,
                        "clientType" to "android",
                        "timestamp" to System.currentTimeMillis().toString()
                    )
                    Log.d(TAG, "🔐 Authentication set for: ${vehicleSession.plateNumber} | ${vehicleSession.ownerPhone}")
                } else {
                    Log.w(TAG, "⚠️ No vehicle session found for authentication")
                }
            }

            socket = IO.socket(serverUrl, options)
            setupSocketEventHandlers()

            withContext(Dispatchers.Main) {
                Log.d(TAG, "🚀 Initiating socket connection...")
                socket?.connect()
            }

        } catch (e: URISyntaxException) {
            Log.e(TAG, "❌ Invalid server URL: $serverUrl", e)
            handleConnectionError()
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error setting up connection", e)
            handleConnectionError()
        }
    }

    /**
     * ✅ COMPLETE EVENT HANDLERS SETUP
     */
    private fun setupSocketEventHandlers() {
        socket?.apply {
            Log.d(TAG, "🔡 Setting up Socket.IO event handlers...")

            // ========== CONNECTION EVENTS ==========
            on(Socket.EVENT_CONNECT) {
                Log.i(TAG, "✅ 🎉 CONNECTED to Socket.IO server successfully!")
                Log.i(TAG, "🆔 Socket ID: ${id()}")

                isConnected = true
                reconnectAttempts = 0
                lastPongReceived = System.currentTimeMillis()

                // ✅ CRITICAL: Join vehicle room immediately after connection
                joinVehicleRoomImmediately()

                // Request current parking status
                requestParkingStatus()

                // Start ping mechanism
                startPingMechanism()

                // Notify callbacks
                notifyConnectionCallbacks(true)
            }

            on(Socket.EVENT_DISCONNECT) { args ->
                val reason = if (args.isNotEmpty()) args[0].toString() else "unknown"
                Log.w(TAG, "❌ 💔 DISCONNECTED from Socket.IO server")
                Log.w(TAG, "🔍 Disconnect reason: $reason")

                isConnected = false
                stopPingMechanism()
                notifyConnectionCallbacks(false)

                when (reason) {
                    "io server disconnect" -> {
                        Log.i(TAG, "🛑 Server initiated disconnect - will not auto-reconnect")
                    }
                    "transport close", "transport error", "ping timeout" -> {
                        Log.i(TAG, "🔄 Network issue - will auto-reconnect")
                        handleConnectionError()
                    }
                    else -> {
                        Log.i(TAG, "❓ Unknown disconnect reason - will auto-reconnect")
                        handleConnectionError()
                    }
                }
            }

            on(Socket.EVENT_CONNECT_ERROR) { args ->
                val errorData = if (args.isNotEmpty()) args[0] else "Unknown error"
                val errorMessage = errorData.toString()

                Log.e(TAG, "🚫 ❌ Socket.IO CONNECTION ERROR")
                Log.e(TAG, "🔍 Error details: $errorMessage")

                when {
                    errorMessage.contains("timeout") -> {
                        Log.w(TAG, "⏱️ Connection timeout - server may be slow or unreachable")
                    }
                    errorMessage.contains("403") || errorMessage.contains("Forbidden") -> {
                        Log.w(TAG, "🔐 Authentication error - check credentials")
                        notifyConnectionCallbacks(false)
                        return@on
                    }
                    errorMessage.contains("ENOTFOUND") || errorMessage.contains("ECONNREFUSED") -> {
                        Log.w(TAG, "🌐 Network error - check server URL and connectivity")
                    }
                    errorMessage.contains("ETIMEDOUT") -> {
                        Log.w(TAG, "⏰ Network timeout - check internet connection")
                    }
                    else -> {
                        Log.w(TAG, "❓ Unknown connection error: $errorMessage")
                    }
                }

                handleConnectionError()
            }

            // ========== 🚗 VEHICLE NOTIFICATION EVENTS 🚗 ==========

            // ✅ CRITICAL: Vehicle notification handler
            on("vehicle_notification") { args ->
                try {
                    Log.i(TAG, "🚗 📱 *** RECEIVED VEHICLE NOTIFICATION ***")

                    if (args.isNotEmpty()) {
                        val data = when (val arg = args[0]) {
                            is JSONObject -> arg
                            is String -> JSONObject(arg)
                            else -> {
                                Log.e(TAG, " Invalid vehicle notification data type: ${arg::class.java}")
                                return@on
                            }
                        }

                        Log.i(TAG, " Vehicle notification data: $data")

                        // Parse vehicle notification
                        val notification = parseVehicleNotification(data)
                        Log.i(TAG, " Parsed vehicle notification: ${notification.title}")
                        Log.i(TAG, " Details: ${notification.plateNumber} - ${notification.action}")

                        //  FIXED: Chỉ hiển thị system notification MỘT LẦN
                        showVehicleSystemNotification(notification)

                        //  FIXED: Chỉ notify vehicle callbacks, KHÔNG convert sang standard notification
                        notifyVehicleCallbacks(notification)

                        //  REMOVED: Dòng này gây duplicate
                        // val standardNotification = convertToStandardNotification(notification)
                        // notifyNotificationCallbacks(standardNotification)

                        Log.i(TAG, " Vehicle notification processed!")

                    } else {
                        Log.w(TAG, " Vehicle notification received but no data")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, " Error handling vehicle notification", e)
                }
            }

            //  Room join confirmation
            on("room_joined") { args ->
                try {
                    if (args.isNotEmpty()) {
                        val data = args[0] as JSONObject
                        val rooms = data.optJSONArray("rooms")
                        Log.i(TAG, " Successfully joined rooms: $rooms")

                        // Log vehicle session info for debugging
                        val vehicleSession = sharedPrefsHelper.getVehicleSession()
                        if (vehicleSession != null) {
                            Log.i(TAG, " Vehicle session active: ${vehicleSession.plateNumber}")
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error handling room joined", e)
                }
            }

            // ========== PARKING STATUS EVENTS ==========
            on("parking_status_update") { args ->
                try {
                    Log.d(TAG, " Received parking_status_update event")

                    if (args.isNotEmpty()) {
                        val data = when (val arg = args[0]) {
                            is JSONObject -> arg
                            is String -> {
                                try {
                                    JSONObject(arg)
                                } catch (e: Exception) {
                                    Log.e(TAG, "Cannot parse parking status string to JSON: $arg", e)
                                    return@on
                                }
                            }
                            else -> {
                                Log.e(TAG, " Unexpected parking status data type: ${arg::class.java}")
                                return@on
                            }
                        }

                        val parkingStatus = parseParkingStatusUpdate(data)
                        notifyStatusCallbacks(parkingStatus)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error parsing parking status update", e)
                }
            }

            // ========== SYSTEM EVENTS ==========
            on("notification") { args ->
                try {
                    Log.d(TAG, " Received general notification event")

                    if (args.isNotEmpty()) {
                        val data = when (val arg = args[0]) {
                            is JSONObject -> arg
                            is String -> {
                                try {
                                    JSONObject(arg)
                                } catch (e: Exception) {
                                    Log.e(TAG, " Cannot parse notification string to JSON: $arg", e)
                                    return@on
                                }
                            }
                            else -> {
                                Log.e(TAG, " Unexpected notification data type: ${arg::class.java}")
                                return@on
                            }
                        }

                        val notification = parseNotification(data)
                        showSystemNotification(notification)
                        notifyNotificationCallbacks(notification)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, " Error parsing general notification", e)
                }
            }

            on("system_info") { args ->
                try {
                    if (args.isNotEmpty()) {
                        val data = args[0] as JSONObject
                        val systemStatus = data.optString("system_status", "unknown")
                        val connectedClients = data.optInt("connected_clients", 0)
                        val features = data.optJSONArray("features")

                        Log.i(TAG, " Server status: $systemStatus, Connected clients: $connectedClients")
                        if (features != null) {
                            Log.i(TAG, " Available features: $features")
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, " Error parsing system info", e)
                }
            }

            // ========== PING/PONG EVENTS ==========
            on("pong") { args ->
                try {
                    lastPongReceived = System.currentTimeMillis()
                    if (args.isNotEmpty()) {
                        val data = args[0] as JSONObject
                        val serverTime = data.optString("timestamp")
                        Log.v(TAG, "🏓 Pong received at: $serverTime")
                    } else {
                        Log.v(TAG, "🏓 Pong received")
                    }
                } catch (e: Exception) {
                    Log.v(TAG, "🏓 Pong received (simple)")
                }
            }

            on("error") { args ->
                val errorData = if (args.isNotEmpty()) args[0] else "Unknown error"
                Log.e(TAG, "⚠️ 🔥 Server error: $errorData")
            }

            on("test_message") { args ->
                try {
                    if (args.isNotEmpty()) {
                        val data = args[0] as JSONObject
                        Log.d(TAG, "🧪 Test message: $data")
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "🧪 Test message received")
                }
            }

            Log.d(TAG, "✅ 🔡 All Socket.IO event handlers configured successfully")
        }
    }

    /**
     * ✅ CRITICAL: Join vehicle room immediately after connection
     */
    private fun joinVehicleRoomImmediately() {
        try {
            val vehicleSession = sharedPrefsHelper.getVehicleSession()
            if (vehicleSession != null) {
                val joinData = JSONObject().apply {
                    put("plate_number", vehicleSession.plateNumber.uppercase())
                    put("owner_phone", vehicleSession.ownerPhone)
                    put("client_type", "android")
                    put("user_id", vehicleSession.plateNumber) // for compatibility
                    put("join_timestamp", System.currentTimeMillis())
                    put("device_info", Build.MODEL)
                    put("app_version", "1.0.0")
                }

                Log.i(TAG, "  *** JOINING VEHICLE ROOM ***")
                Log.i(TAG, " Vehicle: ${vehicleSession.plateNumber}")
                Log.i(TAG, " Phone: ${vehicleSession.ownerPhone}")
                Log.i(TAG, " Join data: $joinData")

                socket?.emit("join_vehicle_room", joinData)

                Log.i(TAG, " Vehicle room join request sent!")

            } else {
                Log.w(TAG, " Cannot join vehicle room - no vehicle session found")
            }
        } catch (e: JSONException) {
            Log.e(TAG, " Error creating vehicle room join data", e)
        } catch (e: Exception) {
            Log.e(TAG, " Error joining vehicle room", e)
        }
    }

    /**
     * ✅ ENHANCED: Parse vehicle notification with comprehensive data
     */
    private fun parseVehicleNotification(data: JSONObject): VehicleNotification {
        return VehicleNotification(
            id = data.optString("id", "unknown_${System.currentTimeMillis()}"),
            type = data.optString("type", "vehicle_activity"),
            title = data.optString("title", "Thông báo xe"),
            message = data.optString("message", "Có hoạt động xe"),
            plateNumber = data.optString("plate_number", ""),
            ownerName = data.optString("owner_name"),
            action = data.optString("action", "unknown"),
            timestamp = data.optString("timestamp", getCurrentTimestamp()),
            imageUrl = data.optString("image_url"),
            parkingDuration = if (data.has("parking_duration")) data.optInt("parking_duration") else null,
            entryTime = data.optString("entry_time"),
            exitTime = data.optString("exit_time")
        )
    }

    /**
     * ✅ ENHANCED: Show vehicle system notification with rich content
     */
    private fun showVehicleSystemNotification(notification: VehicleNotification) {
        try {
            val title = if (notification.action == "entry") {
                "Xe vào bãi đỗ"
            } else {
                " Xe ra khỏi bãi đỗ"
            }

            val message = buildString {
                append("Xe ${notification.plateNumber}")
                if (!notification.ownerName.isNullOrEmpty()) {
                    append(" của ${notification.ownerName}")
                }
                append(" đã ${if (notification.action == "entry") "vào" else "ra khỏi"} bãi đỗ xe")

                notification.parkingDuration?.let { duration ->
                    if (duration > 0) {
                        val hours = duration / 60
                        val minutes = duration % 60
                        if (hours > 0) {
                            append("\n Thời gian đỗ: ${hours}h ${minutes}p")
                        } else {
                            append("\n️ Thời gian đỗ: ${minutes} phút")
                        }
                    }
                }
            }

            // Show notification using notification service
            notificationService.showVehicleNotification(
                context = context,
                title = title,
                message = message,
                plateNumber = notification.plateNumber,
                action = notification.action,
                imageUrl = notification.imageUrl
            )

            Log.i(TAG, "✅ 🚗 Vehicle notification shown: $title")
            Log.i(TAG, "📋 Message: $message")

        } catch (e: Exception) {
            Log.e(TAG, "❌ Error showing vehicle notification", e)
        }
    }

    /**
     * ✅ NEW: Convert vehicle notification to standard notification for UI
     */
    private fun convertToStandardNotification(vehicleNotification: VehicleNotification): NotificationItem {
        val type = if (vehicleNotification.action == "entry") {
            NotificationItem.Type.VEHICLE_ENTERED
        } else {
            NotificationItem.Type.VEHICLE_EXITED
        }

        val priority = NotificationItem.Priority.HIGH

        return NotificationItem(
            id = vehicleNotification.id.hashCode(),
            title = vehicleNotification.title,
            message = vehicleNotification.message,
            timestamp = System.currentTimeMillis(),
            type = type,
            priority = priority,
            isRead = false,
            isImportant = true
        )
    }

    // ========== CALLBACK MANAGEMENT ==========

    fun registerVehicleCallback(id: String, callback: (VehicleNotification) -> Unit) {
        vehicleCallbacks[id] = callback
        Log.d(TAG, "🚗 ✅ Registered vehicle callback: $id (total: ${vehicleCallbacks.size})")
    }

    fun unregisterVehicleCallback(id: String) {
        vehicleCallbacks.remove(id)
        Log.d(TAG, "🚗 ❌ Unregistered vehicle callback: $id (remaining: ${vehicleCallbacks.size})")
    }

    private fun notifyVehicleCallbacks(notification: VehicleNotification) {
        Log.d(TAG, "📢 Notifying ${vehicleCallbacks.size} vehicle callbacks...")
        vehicleCallbacks.values.forEach { callback ->
            try {
                callback(notification)
                Log.d(TAG, "✅ Vehicle callback notified successfully")
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error in vehicle callback", e)
            }
        }
    }

    fun registerParkingStatusCallback(id: String, callback: (ParkingStatusService.ParkingStatusData) -> Unit) {
        statusUpdateCallbacks[id] = callback
        Log.d(TAG, "📊 ✅ Registered parking status callback: $id (total: ${statusUpdateCallbacks.size})")
    }

    fun unregisterParkingStatusCallback(id: String) {
        statusUpdateCallbacks.remove(id)
        Log.d(TAG, "📊 ❌ Unregistered parking status callback: $id (remaining: ${statusUpdateCallbacks.size})")
    }

    fun registerNotificationCallback(id: String, callback: (NotificationItem) -> Unit) {
        notificationCallbacks[id] = callback
        Log.d(TAG, "📱 ✅ Registered notification callback: $id (total: ${notificationCallbacks.size})")
    }

    fun unregisterNotificationCallback(id: String) {
        notificationCallbacks.remove(id)
        Log.d(TAG, "📱 ❌ Unregistered notification callback: $id (remaining: ${notificationCallbacks.size})")
    }

    fun registerConnectionCallback(id: String, callback: (Boolean) -> Unit) {
        connectionCallbacks[id] = callback
        Log.d(TAG, "🔌 ✅ Registered connection callback: $id (total: ${connectionCallbacks.size})")
    }

    fun unregisterConnectionCallback(id: String) {
        connectionCallbacks.remove(id)
        Log.d(TAG, "🔌 ❌ Unregistered connection callback: $id (remaining: ${connectionCallbacks.size})")
    }

    // ========== PUBLIC API METHODS ==========

    fun isConnected(): Boolean {
        val socketConnected = socket?.connected() ?: false
        return isConnected && socketConnected
    }

    fun requestParkingStatus() {
        if (isConnected) {
            try {
                val requestData = JSONObject().apply {
                    put("timestamp", System.currentTimeMillis())
                    put("client_type", "android")
                }

                Log.d(TAG, "📊 🔄 Requesting parking status...")
                socket?.emit("request_parking_status", requestData)
                Log.d(TAG, "✅ 📊 Parking status request sent")
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error requesting parking status", e)
            }
        } else {
            Log.w(TAG, "⚠️ Cannot request parking status - not connected")
        }
    }

    fun forceReconnect() {
        Log.i(TAG, "🔄 Force reconnect requested")
        disconnect()
        reconnectAttempts = 0

        coroutineScope.launch {
            delay(1000)
            connect()
        }
    }

    fun testConnection(): Boolean {
        if (!isConnected) {
            Log.w(TAG, "🧪 Test connection failed - not connected")
            return false
        }

        return try {
            val testData = JSONObject().apply {
                put("test_timestamp", System.currentTimeMillis())
                put("client_type", "android")
                put("test_id", "test_${System.currentTimeMillis()}")
            }
            socket?.emit("test_connection", testData)
            Log.d(TAG, "🧪 Test connection message sent")
            true
        } catch (e: Exception) {
            Log.e(TAG, "🧪 Test connection failed", e)
            false
        }
    }

    fun sendHeartbeat() {
        if (isConnected) {
            try {
                val heartbeatData = JSONObject().apply {
                    put("heartbeat_type", "manual")
                    put("app_state", "foreground")
                    put("timestamp", System.currentTimeMillis())
                    put("client_type", "android")
                }
                socket?.emit("heartbeat", heartbeatData)
                Log.d(TAG, "💗 Heartbeat sent")
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error sending heartbeat", e)
            }
        }
    }

    fun reportAppState(state: String) {
        if (isConnected) {
            try {
                val stateData = JSONObject().apply {
                    put("app_state", state)
                    put("state_change_time", System.currentTimeMillis())
                    put("timestamp", System.currentTimeMillis())
                    put("client_type", "android")
                }
                socket?.emit("app_state_change", stateData)
                Log.d(TAG, "📱 App state reported: $state")
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error reporting app state", e)
            }
        }
    }

    fun isConnectionHealthy(): Boolean {
        if (!isConnected) return false

        val timeSinceLastPong = System.currentTimeMillis() - lastPongReceived
        val isHealthy = timeSinceLastPong < PING_INTERVAL * 2

        if (!isHealthy) {
            Log.w(TAG, "⚠️ Connection unhealthy - last pong: ${timeSinceLastPong}ms ago")
        }

        return isHealthy
    }

    fun disconnect() {
        Log.i(TAG, "🔌 Disconnecting from Socket.IO server")

        try {
            isConnected = false
            stopPingMechanism()

            socket?.apply {
                try {
                    emit("client_disconnect", JSONObject().apply {
                        put("reason", "client_initiated")
                        put("timestamp", System.currentTimeMillis())
                    })
                } catch (e: Exception) {
                    Log.w(TAG, "Error sending disconnect notification", e)
                }

                off()
                disconnect()
                close()
            }
            socket = null
            connectionJob?.cancel()

        } catch (e: Exception) {
            Log.e(TAG, "❌ Error disconnecting", e)
        }
    }

    fun initializeNotifications() {
        try {
            notificationService.initializeNotificationChannels(context)
            Log.d(TAG, "✅ Notification service initialized")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error initializing notifications", e)
        }
    }

    fun cleanup() {
        Log.i(TAG, "🧹 Cleaning up WebSocket resources")

        try {
            disconnect()
            statusUpdateCallbacks.clear()
            notificationCallbacks.clear()
            connectionCallbacks.clear()
            vehicleCallbacks.clear()
            coroutineScope.cancel()

            synchronized(WebSocketManager::class.java) {
                INSTANCE = null
            }

            Log.i(TAG, "✅ WebSocket cleanup completed")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error during cleanup", e)
        }
    }

    // ========== HELPER METHODS ==========

    private fun getCurrentTimestamp(): String {
        return SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
    }

    private fun parseParkingStatusUpdate(data: JSONObject): ParkingStatusService.ParkingStatusData {
        return try {
            Log.d(TAG, "🔍 Parsing parking status data...")

            val statusData = data.optJSONObject("parking_status") ?: data
            val total = maxOf(0, statusData.optInt("total", 0))
            val available = maxOf(0, statusData.optInt("available", 0))
            val occupied = maxOf(0, statusData.optInt("occupied", 0))
            val percentage = maxOf(0.0, minOf(100.0, statusData.optDouble("percentage_full", 0.0)))

            val result = ParkingStatusService.ParkingStatusData(
                parking_status = ParkingStatusService.ParkingInfo(
                    total = total,
                    available = available,
                    occupied = occupied,
                    percentage_full = percentage
                ),
                status_message = data.optString("status_message", "No status"),
                last_updated = data.optString("last_updated", getCurrentTimestamp()),
                color_indicator = data.optString("color_indicator", "gray")
            )

            Log.d(TAG, "✅ Successfully parsed parking status")
            result
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error parsing parking status", e)
            ParkingStatusService.ParkingStatusData(
                parking_status = ParkingStatusService.ParkingInfo(0, 0, 0, 0.0),
                status_message = "Error parsing status",
                last_updated = getCurrentTimestamp(),
                color_indicator = "red"
            )
        }
    }

    private fun notifyStatusCallbacks(status: ParkingStatusService.ParkingStatusData) {
        Log.d(TAG, "📢 Notifying ${statusUpdateCallbacks.size} status callbacks...")
        statusUpdateCallbacks.values.forEach { callback ->
            try {
                callback(status)
                Log.d(TAG, "✅ Status callback notified successfully")
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error in status callback", e)
            }
        }
    }

    private fun notifyNotificationCallbacks(notification: NotificationItem) {
        Log.d(TAG, "📢 Notifying ${notificationCallbacks.size} notification callbacks...")
        notificationCallbacks.values.forEach { callback ->
            try {
                callback(notification)
                Log.d(TAG, "✅ Notification callback notified successfully")
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error in notification callback", e)
            }
        }
    }

    private fun notifyConnectionCallbacks(connected: Boolean) {
        Log.d(TAG, "📢 Notifying ${connectionCallbacks.size} connection callbacks: connected=$connected")
        connectionCallbacks.values.forEach { callback ->
            try {
                callback(connected)
                Log.d(TAG, "✅ Connection callback notified successfully")
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error in connection callback", e)
            }
        }
    }

    private fun handleConnectionError() {
        isConnected = false

        if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
            Log.e(TAG, "💀 Max reconnect attempts reached")
            notifyConnectionCallbacks(false)
            return
        }

        val baseDelay = if (reconnectAttempts == 0) INITIAL_RECONNECT_DELAY else RECONNECT_DELAY
        val exponentialDelay = baseDelay * (1 shl reconnectAttempts.coerceAtMost(6))
        val jitter = (Math.random() * 1000).toLong()
        val totalDelay = exponentialDelay + jitter

        Log.i(TAG, "🔄 Scheduling reconnection ${reconnectAttempts + 1}/$MAX_RECONNECT_ATTEMPTS in ${totalDelay}ms")

        coroutineScope.launch {
            delay(totalDelay)
            if (!isConnected) {
                reconnectAttempts++
                Log.i(TAG, "🔄 Attempting reconnection #$reconnectAttempts")
                connect()
            }
        }
    }

    private fun startPingMechanism() {
        stopPingMechanism()

        pingJob = coroutineScope.launch {
            while (isConnected && isActive) {
                try {
                    delay(PING_INTERVAL)
                    if (isConnected) {
                        val timeSinceLastPong = System.currentTimeMillis() - lastPongReceived
                        if (timeSinceLastPong > PING_INTERVAL * 2) {
                            Log.w(TAG, "⚠️ No pong for ${timeSinceLastPong}ms - connection may be stale")
                            handleConnectionError()
                            break
                        }

                        val pingData = JSONObject().apply {
                            put("timestamp", System.currentTimeMillis())
                            put("client_type", "android")
                        }
                        socket?.emit("ping", pingData)
                        Log.v(TAG, "🏓 Ping sent")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Error sending ping", e)
                    break
                }
            }
        }
    }

    private fun stopPingMechanism() {
        pingJob?.cancel()
        pingJob = null
    }

    private fun parseNotification(data: JSONObject): NotificationItem {
        return try {
            val typeString = data.optString("type", "system_update")
            val priorityValue = data.optInt("priority", 2)

            val type = try {
                NotificationItem.Type.fromString(typeString)
            } catch (e: Exception) {
                Log.w(TAG, "Unknown notification type: $typeString")
                NotificationItem.Type.SYSTEM_UPDATE
            }

            val priority = try {
                NotificationItem.Priority.fromInt(priorityValue)
            } catch (e: Exception) {
                Log.w(TAG, "Unknown priority value: $priorityValue")
                NotificationItem.Priority.NORMAL
            }

            NotificationItem(
                id = data.optInt("id", System.currentTimeMillis().toInt()),
                title = data.optString("title", "Notification").take(100),
                message = data.optString("message", "").take(500),
                timestamp = System.currentTimeMillis(),
                type = type,
                priority = priority,
                isRead = false,
                isImportant = priority == NotificationItem.Priority.HIGH || priority == NotificationItem.Priority.URGENT
            )
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error parsing notification", e)
            NotificationItem(
                id = System.currentTimeMillis().toInt(),
                title = "Error",
                message = "Failed to parse notification",
                timestamp = System.currentTimeMillis(),
                type = NotificationItem.Type.SYSTEM_UPDATE,
                priority = NotificationItem.Priority.LOW
            )
        }
    }

    private fun showSystemNotification(notification: NotificationItem) {
        if (!sharedPrefsHelper.isNotificationEnabled()) {
            Log.d(TAG, "Notifications disabled by user")
            return
        }

        try {
            notificationService.showNotification(context, notification.title, notification.message)
            Log.i(TAG, "✅ System notification shown: ${notification.type}")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error showing system notification", e)
        }
    }

    fun getConnectionInfo(): Map<String, Any> {
        return mapOf(
            "connected" to isConnected,
            "server_url" to sharedPrefsHelper.getServerUrl(),
            "reconnect_attempts" to reconnectAttempts,
            "max_attempts" to MAX_RECONNECT_ATTEMPTS,
            "callbacks_registered" to (statusUpdateCallbacks.size + notificationCallbacks.size + connectionCallbacks.size + vehicleCallbacks.size),
            "socket_connected" to (socket?.connected() ?: false),
            "socket_id" to (socket?.id() ?: "none"),
            "last_pong" to lastPongReceived,
            "connection_type" to "socket.io"
        )
    }

    fun getDetailedStatus(): Map<String, Any> {
        return mapOf(
            "basic_info" to getConnectionInfo(),
            "callbacks" to mapOf(
                "status_callbacks" to statusUpdateCallbacks.keys.toList(),
                "notification_callbacks" to notificationCallbacks.keys.toList(),
                "connection_callbacks" to connectionCallbacks.keys.toList(),
                "vehicle_callbacks" to vehicleCallbacks.keys.toList()
            ),
            "health" to mapOf(
                "is_healthy" to isConnectionHealthy(),
                "time_since_last_pong" to (System.currentTimeMillis() - lastPongReceived),
                "ping_interval" to PING_INTERVAL,
                "connection_timeout" to CONNECTION_TIMEOUT
            ),
            "server_config" to mapOf(
                "raw_url" to sharedPrefsHelper.getServerUrl(),
                "validated_url" to getValidatedServerUrl(),
                "has_vehicle_session" to (sharedPrefsHelper.getVehicleSession() != null)
            ),
            "socket_info" to mapOf(
                "socket_exists" to (socket != null),
                "socket_connected" to (socket?.connected() ?: false),
                "socket_id" to (socket?.id() ?: "none")
            )
        )
    }

    // ========== TESTING AND DEBUGGING METHODS ==========

    /**
     * ✅ Test vehicle notification reception
     */
    fun testVehicleNotificationReception() {
        try {
            Log.d(TAG, "🧪 Testing vehicle notification reception...")

            if (isConnected()) {
                Log.d(TAG, "🧪 WebSocket connected, system ready for vehicle notifications")

                // Send test notification request
                val testData = JSONObject().apply {
                    put("test", true)
                    put("plate_number", sharedPrefsHelper.getVehicleSession()?.plateNumber ?: "TEST123")
                    put("action", "entry")
                    put("timestamp", System.currentTimeMillis())
                }

                socket?.emit("test_vehicle_notification", testData)
                Log.d(TAG, "🧪 Test vehicle notification request sent")

            } else {
                Log.w(TAG, "🧪 WebSocket not connected, vehicle notifications may not work")
            }

            val vehicleSession = sharedPrefsHelper.getVehicleSession()
            if (vehicleSession != null) {
                Log.d(TAG, "🧪 Vehicle session active: ${vehicleSession.plateNumber}")
            } else {
                Log.w(TAG, "🧪 No vehicle session found")
            }

        } catch (e: Exception) {
            Log.e(TAG, "❌ Error testing vehicle notification reception", e)
        }
    }

    /**
     * ✅ Manual test of callback system
     */
    fun testCallbackSystem() {
        Log.d(TAG, "🧪 Testing callback system...")

        // Test vehicle callback
        registerVehicleCallback("test") { notification ->
            Log.d(TAG, "🧪 Test vehicle callback received: ${notification.title}")
        }

        // Test notification callback
        registerNotificationCallback("test") { notification ->
            Log.d(TAG, "🧪 Test notification callback received: ${notification.title}")
        }

        // Test connection callback
        registerConnectionCallback("test") { connected ->
            Log.d(TAG, "🧪 Test connection callback: connected=$connected")
        }

        Log.d(TAG, "🧪 Callback system test completed")
    }

    /**
     * ✅ Force send test notification via WebSocket
     */
    fun sendTestNotification() {
        if (isConnected) {
            try {
                val testNotification = JSONObject().apply {
                    put("id", "test_${System.currentTimeMillis()}")
                    put("type", "vehicle_activity")
                    put("title", "🧪 Test Vehicle Notification")
                    put("message", "This is a test notification at ${getCurrentTimestamp()}")
                    put("plate_number", sharedPrefsHelper.getVehicleSession()?.plateNumber ?: "TEST123")
                    put("action", "entry")
                    put("timestamp", getCurrentTimestamp())
                }

                socket?.emit("test_notification", testNotification)
                Log.d(TAG, "🧪 Test notification sent via WebSocket")
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error sending test notification", e)
            }
        } else {
            Log.w(TAG, "🧪 Cannot send test notification - not connected")
        }
    }

    /**
     * ✅ Get comprehensive debug information
     */
    fun getDebugInfo(): String {
        return buildString {
            appendLine("=== WebSocket Debug Info ===")
            appendLine("Connected: ${isConnected()}")
            appendLine("Socket Exists: ${socket != null}")
            appendLine("Socket Connected: ${socket?.connected() ?: false}")
            appendLine("Socket ID: ${socket?.id() ?: "none"}")
            appendLine("Server URL: ${sharedPrefsHelper.getServerUrl()}")
            appendLine("Reconnect Attempts: $reconnectAttempts/$MAX_RECONNECT_ATTEMPTS")
            appendLine("Last Pong: ${Date(lastPongReceived)}")
            appendLine("Connection Healthy: ${isConnectionHealthy()}")
            appendLine()
            appendLine("=== Callbacks Registered ===")
            appendLine("Vehicle Callbacks: ${vehicleCallbacks.size} - ${vehicleCallbacks.keys}")
            appendLine("Status Callbacks: ${statusUpdateCallbacks.size} - ${statusUpdateCallbacks.keys}")
            appendLine("Notification Callbacks: ${notificationCallbacks.size} - ${notificationCallbacks.keys}")
            appendLine("Connection Callbacks: ${connectionCallbacks.size} - ${connectionCallbacks.keys}")
            appendLine()
            appendLine("=== Vehicle Session ===")
            val vehicleSession = sharedPrefsHelper.getVehicleSession()
            if (vehicleSession != null) {
                appendLine("Plate: ${vehicleSession.plateNumber}")
                appendLine("Owner: ${vehicleSession.ownerName}")
                appendLine("Phone: ${vehicleSession.ownerPhone}")
                appendLine("Active: ${vehicleSession.isExpired()}")
            } else {
                appendLine("No vehicle session found")
            }
            appendLine("=== End Debug Info ===")
        }
    }
}