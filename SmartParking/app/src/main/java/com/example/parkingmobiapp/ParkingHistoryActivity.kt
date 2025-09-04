package com.example.parkingmobiapp

import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.parkingmobiapp.models.NotificationItem
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*
import android.view.LayoutInflater
import android.view.ViewGroup
import android.app.AlertDialog

/**
 * ParkingHistoryActivity - Màn hình lịch sử đỗ xe thực tế
 * Hiển thị các lần xe vào/ra từ notifications storage
 */
class ParkingHistoryActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "ParkingHistoryActivity"
    }

    // UI Components
    private lateinit var rvParkingHistory: RecyclerView
    private lateinit var tvEmptyMessage: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var tvTotalSessions: TextView
    private lateinit var tvAverageDuration: TextView
    private lateinit var tvLastParking: TextView
    private lateinit var btnClearHistory: Button

    // Data
    private val parkingHistoryList = mutableListOf<ParkingSession>()
    private lateinit var historyAdapter: ParkingHistoryAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_parking_history)

        initializeViews()
        setupRecyclerView()
        loadParkingHistory()
    }

    private fun initializeViews() {
        try {
            // Back button
            findViewById<ImageView>(R.id.iv_back_button).setOnClickListener {
                finish()
            }

            // RecyclerView và các views khác
            rvParkingHistory = findViewById(R.id.rv_parking_history)
            tvEmptyMessage = findViewById(R.id.tv_empty_message)
            progressBar = findViewById(R.id.progress_bar)
            tvTotalSessions = findViewById(R.id.tv_total_sessions)
            tvAverageDuration = findViewById(R.id.tv_average_duration)
            tvLastParking = findViewById(R.id.tv_last_parking)
            btnClearHistory = findViewById(R.id.btn_clear_history)

            btnClearHistory.setOnClickListener {
                showClearHistoryDialog()
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error initializing views", e)
            // Fallback to simple layout if custom layout not found
            createSimpleLayout()
        }
    }

    private fun createSimpleLayout() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 50, 50, 50)
        }

        val backButton = Button(this).apply {
            text = "← Quay lại"
            setOnClickListener { finish() }
        }

        val title = TextView(this).apply {
            text = "📋 Lịch sử đỗ xe"
            textSize = 24f
            setPadding(0, 20, 0, 40)
        }

        rvParkingHistory = RecyclerView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }

        tvEmptyMessage = TextView(this).apply {
            text = "Chưa có lịch sử đỗ xe"
            textSize = 16f
            visibility = View.GONE
        }

        progressBar = ProgressBar(this).apply {
            visibility = View.GONE
        }

        tvTotalSessions = TextView(this).apply {
            text = "Tổng số lần đỗ: 0"
            textSize = 14f
        }

        tvAverageDuration = TextView(this).apply {
            text = "Thời gian TB: 0 phút"
            textSize = 14f
        }

        tvLastParking = TextView(this).apply {
            text = "Lần đỗ gần nhất: --"
            textSize = 14f
        }

        btnClearHistory = Button(this).apply {
            text = "Xóa lịch sử"
            setOnClickListener { showClearHistoryDialog() }
        }

        layout.addView(backButton)
        layout.addView(title)
        layout.addView(tvTotalSessions)
        layout.addView(tvAverageDuration)
        layout.addView(tvLastParking)
        layout.addView(rvParkingHistory)
        layout.addView(tvEmptyMessage)
        layout.addView(progressBar)
        layout.addView(btnClearHistory)

        setContentView(layout)
    }

    private fun setupRecyclerView() {
        historyAdapter = ParkingHistoryAdapter(parkingHistoryList) { session ->
            showSessionDetails(session)
        }

        rvParkingHistory.apply {
            layoutManager = LinearLayoutManager(this@ParkingHistoryActivity)
            adapter = historyAdapter
        }
    }

    private fun loadParkingHistory() {
        progressBar.visibility = View.VISIBLE
        tvEmptyMessage.visibility = View.GONE

        try {
            // Load từ SharedPreferences
            val sharedPrefs = getSharedPreferences("notifications", Context.MODE_PRIVATE)
            val notificationsJson = sharedPrefs.getString("notification_list", "[]") ?: "[]"
            val notificationsList = JSONArray(notificationsJson)

            parkingHistoryList.clear()
            val vehicleEntries = mutableMapOf<String, VehicleEntry>()

            // Parse notifications và ghép cặp entry/exit
            for (i in 0 until notificationsList.length()) {
                try {
                    val json = notificationsList.getJSONObject(i)
                    val type = json.optString("type", "")

                    if (type == "VEHICLE_ENTERED" || type == "VEHICLE_EXITED") {
                        val plateNumber = json.optString("plate_number", "")
                        val timestamp = json.optLong("timestamp", 0)
                        val action = json.optString("action", "")
                        val ownerName = json.optString("owner_name", "")
                        val parkingDuration = json.optInt("parking_duration", 0)

                        if (plateNumber.isNotEmpty()) {
                            when (action) {
                                "entry" -> {
                                    // Lưu entry để ghép với exit sau
                                    vehicleEntries[plateNumber] = VehicleEntry(
                                        plateNumber = plateNumber,
                                        ownerName = ownerName,
                                        entryTime = timestamp,
                                        entryMessage = json.optString("message", "")
                                    )

                                    // Tạo session chưa hoàn thành (đang đỗ)
                                    val ongoingSession = ParkingSession(
                                        id = timestamp.toInt(),
                                        plateNumber = plateNumber,
                                        ownerName = ownerName,
                                        entryTime = timestamp,
                                        exitTime = null,
                                        duration = 0,
                                        status = "Đang đỗ",
                                        fee = 0.0
                                    )
                                    parkingHistoryList.add(ongoingSession)
                                }

                                "exit" -> {
                                    // Tìm entry tương ứng
                                    val entry = vehicleEntries[plateNumber]
                                    if (entry != null && parkingDuration > 0) {
                                        // Cập nhật session đã có với exit time
                                        val sessionToUpdate = parkingHistoryList.find {
                                            it.plateNumber == plateNumber && it.exitTime == null
                                        }

                                        if (sessionToUpdate != null) {
                                            // Cập nhật session existing
                                            val index = parkingHistoryList.indexOf(sessionToUpdate)
                                            parkingHistoryList[index] = sessionToUpdate.copy(
                                                exitTime = timestamp,
                                                duration = parkingDuration,
                                                status = "Đã ra",
                                                fee = calculateFee(parkingDuration)
                                            )
                                        } else {
                                            // Tạo session mới nếu không tìm thấy
                                            val session = ParkingSession(
                                                id = entry.entryTime.toInt(),
                                                plateNumber = plateNumber,
                                                ownerName = entry.ownerName,
                                                entryTime = entry.entryTime,
                                                exitTime = timestamp,
                                                duration = parkingDuration,
                                                status = "Đã ra",
                                                fee = calculateFee(parkingDuration)
                                            )
                                            parkingHistoryList.add(session)
                                        }

                                        // Xóa entry đã xử lý
                                        vehicleEntries.remove(plateNumber)
                                    } else if (parkingDuration > 0) {
                                        // Exit mà không có entry - tạo session với thời gian ước tính
                                        val estimatedEntryTime = timestamp - (parkingDuration * 60 * 1000)
                                        val session = ParkingSession(
                                            id = timestamp.toInt(),
                                            plateNumber = plateNumber,
                                            ownerName = ownerName,
                                            entryTime = estimatedEntryTime,
                                            exitTime = timestamp,
                                            duration = parkingDuration,
                                            status = "Đã ra",
                                            fee = calculateFee(parkingDuration)
                                        )
                                        parkingHistoryList.add(session)
                                    }
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error parsing notification at index $i", e)
                }
            }

            // Sắp xếp theo thời gian mới nhất
            parkingHistoryList.sortByDescending { it.entryTime }

            // Cập nhật UI
            updateStatistics()
            updateUI()

            Log.d(TAG, "✅ Loaded ${parkingHistoryList.size} parking sessions")

        } catch (e: Exception) {
            Log.e(TAG, "Error loading parking history", e)
            showError("Lỗi khi tải lịch sử đỗ xe")
        } finally {
            progressBar.visibility = View.GONE
        }
    }

    private fun updateStatistics() {
        // Tổng số lần đỗ
        val completedSessions = parkingHistoryList.filter { it.exitTime != null }
        tvTotalSessions.text = "Tổng số lần đỗ: ${parkingHistoryList.size}"

        // Thời gian trung bình
        if (completedSessions.isNotEmpty()) {
            val avgDuration = completedSessions.map { it.duration }.average()
            val hours = avgDuration.toInt() / 60
            val minutes = avgDuration.toInt() % 60
            tvAverageDuration.text = if (hours > 0) {
                "Thời gian TB: ${hours}h ${minutes}p"
            } else {
                "Thời gian TB: ${minutes} phút"
            }
        } else {
            tvAverageDuration.text = "Thời gian TB: --"
        }

        // Lần đỗ gần nhất
        if (parkingHistoryList.isNotEmpty()) {
            val lastSession = parkingHistoryList.first()
            val dateFormat = SimpleDateFormat("dd/MM HH:mm", Locale.getDefault())
            tvLastParking.text = "Gần nhất: ${dateFormat.format(Date(lastSession.entryTime))}"
        } else {
            tvLastParking.text = "Lần đỗ gần nhất: --"
        }
    }

    private fun updateUI() {
        if (parkingHistoryList.isEmpty()) {
            rvParkingHistory.visibility = View.GONE
            tvEmptyMessage.visibility = View.VISIBLE
        } else {
            rvParkingHistory.visibility = View.VISIBLE
            tvEmptyMessage.visibility = View.GONE
            historyAdapter.notifyDataSetChanged()
        }
    }

    private fun calculateFee(durationMinutes: Int): Double {
        // Giả sử 5000đ cho 30 phút đầu, sau đó 3000đ mỗi 30 phút
        return when {
            durationMinutes <= 30 -> 5000.0
            durationMinutes <= 60 -> 10000.0
            else -> {
                val additionalHalfHours = ((durationMinutes - 60) / 30.0).toInt() + 1
                10000.0 + (additionalHalfHours * 3000)
            }
        }
    }

    private fun showSessionDetails(session: ParkingSession) {
        val details = buildString {
            appendLine("🚗 Biển số: ${session.plateNumber}")
            if (session.ownerName.isNotEmpty()) {
                appendLine("👤 Chủ xe: ${session.ownerName}")
            }

            val dateFormat = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())
            appendLine("⬇️ Giờ vào: ${dateFormat.format(Date(session.entryTime))}")

            if (session.exitTime != null) {
                appendLine("⬆️ Giờ ra: ${dateFormat.format(Date(session.exitTime))}")

                val hours = session.duration / 60
                val minutes = session.duration % 60
                if (hours > 0) {
                    appendLine("⏱️ Thời gian: ${hours}h ${minutes}p")
                } else {
                    appendLine("⏱️ Thời gian: ${minutes} phút")
                }

                appendLine("💰 Phí đỗ xe: ${String.format("%,.0f", session.fee)}đ")
            } else {
                appendLine("📍 Trạng thái: Đang đỗ xe")

                val currentDuration = ((System.currentTimeMillis() - session.entryTime) / 60000).toInt()
                val hours = currentDuration / 60
                val minutes = currentDuration % 60
                if (hours > 0) {
                    appendLine("⏱️ Đã đỗ: ${hours}h ${minutes}p")
                } else {
                    appendLine("⏱️ Đã đỗ: ${minutes} phút")
                }
            }
        }

        AlertDialog.Builder(this)
            .setTitle("Chi tiết lượt đỗ xe")
            .setMessage(details)
            .setPositiveButton("OK", null)
            .show()
    }

    private fun showClearHistoryDialog() {
        AlertDialog.Builder(this)
            .setTitle("Xóa lịch sử")
            .setMessage("Bạn có chắc chắn muốn xóa toàn bộ lịch sử đỗ xe?")
            .setPositiveButton("Xóa") { _, _ ->
                clearHistory()
            }
            .setNegativeButton("Hủy", null)
            .show()
    }

    private fun clearHistory() {
        try {
            // Chỉ xóa các notification type VEHICLE_ENTERED và VEHICLE_EXITED
            val sharedPrefs = getSharedPreferences("notifications", Context.MODE_PRIVATE)
            val notificationsJson = sharedPrefs.getString("notification_list", "[]") ?: "[]"
            val notificationsList = JSONArray(notificationsJson)

            val filteredList = JSONArray()
            for (i in 0 until notificationsList.length()) {
                val json = notificationsList.getJSONObject(i)
                val type = json.optString("type", "")
                if (type != "VEHICLE_ENTERED" && type != "VEHICLE_EXITED") {
                    filteredList.put(json)
                }
            }

            sharedPrefs.edit()
                .putString("notification_list", filteredList.toString())
                .apply()

            parkingHistoryList.clear()
            updateStatistics()
            updateUI()

            Toast.makeText(this, "Đã xóa lịch sử đỗ xe", Toast.LENGTH_SHORT).show()

        } catch (e: Exception) {
            Log.e(TAG, "Error clearing history", e)
            showError("Lỗi khi xóa lịch sử")
        }
    }

    private fun showError(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    // Data classes
    data class ParkingSession(
        val id: Int,
        val plateNumber: String,
        val ownerName: String,
        val entryTime: Long,
        val exitTime: Long?,
        val duration: Int, // in minutes
        val status: String,
        val fee: Double
    )

    data class VehicleEntry(
        val plateNumber: String,
        val ownerName: String,
        val entryTime: Long,
        val entryMessage: String
    )

    // RecyclerView Adapter
    inner class ParkingHistoryAdapter(
        private val sessions: List<ParkingSession>,
        private val onItemClick: (ParkingSession) -> Unit
    ) : RecyclerView.Adapter<ParkingHistoryAdapter.ViewHolder>() {

        inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val tvPlateNumber: TextView = itemView.findViewById(R.id.tv_plate_number)
            val tvDateTime: TextView = itemView.findViewById(R.id.tv_date_time)
            val tvDuration: TextView = itemView.findViewById(R.id.tv_duration)
            val tvStatus: TextView = itemView.findViewById(R.id.tv_status)
            val tvFee: TextView = itemView.findViewById(R.id.tv_fee)
            val ivStatusIcon: ImageView = itemView.findViewById(R.id.iv_status_icon)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            // Try to inflate custom layout, fallback to programmatic if not found
            return try {
                val view = LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_parking_history, parent, false)
                ViewHolder(view)
            } catch (e: Exception) {
                // Create programmatic layout if XML not found
                createProgrammaticViewHolder(parent)
            }
        }

        private fun createProgrammaticViewHolder(parent: ViewGroup): ViewHolder {
            val itemView = LinearLayout(parent.context).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                setPadding(20, 20, 20, 20)
            }

            val leftLayout = LinearLayout(parent.context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            val tvPlateNumber = TextView(parent.context).apply {
                id = R.id.tv_plate_number
                textSize = 16f
                setTextColor(ContextCompat.getColor(context, android.R.color.black))
            }

            val tvDateTime = TextView(parent.context).apply {
                id = R.id.tv_date_time
                textSize = 14f
            }

            val tvDuration = TextView(parent.context).apply {
                id = R.id.tv_duration
                textSize = 14f
            }

            leftLayout.addView(tvPlateNumber)
            leftLayout.addView(tvDateTime)
            leftLayout.addView(tvDuration)

            val rightLayout = LinearLayout(parent.context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }

            val tvStatus = TextView(parent.context).apply {
                id = R.id.tv_status
                textSize = 14f
            }

            val tvFee = TextView(parent.context).apply {
                id = R.id.tv_fee
                textSize = 14f
            }

            val ivStatusIcon = ImageView(parent.context).apply {
                id = R.id.iv_status_icon
                layoutParams = LinearLayout.LayoutParams(50, 50)
            }

            rightLayout.addView(tvStatus)
            rightLayout.addView(tvFee)
            rightLayout.addView(ivStatusIcon)

            itemView.addView(leftLayout)
            itemView.addView(rightLayout)

            return ViewHolder(itemView)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val session = sessions[position]

            holder.tvPlateNumber.text = "🚗 ${session.plateNumber}"

            val dateFormat = SimpleDateFormat("dd/MM HH:mm", Locale.getDefault())
            holder.tvDateTime.text = "Vào: ${dateFormat.format(Date(session.entryTime))}"

            if (session.exitTime != null) {
                val hours = session.duration / 60
                val minutes = session.duration % 60
                holder.tvDuration.text = if (hours > 0) {
                    "⏱️ ${hours}h ${minutes}p"
                } else {
                    "⏱️ ${minutes} phút"
                }

                holder.tvStatus.text = "✅ Đã ra"
                holder.tvStatus.setTextColor(ContextCompat.getColor(holder.itemView.context, R.color.success_color))
                holder.tvFee.text = "💰 ${String.format("%,.0f", session.fee)}đ"
                holder.tvFee.visibility = View.VISIBLE
            } else {
                val currentDuration = ((System.currentTimeMillis() - session.entryTime) / 60000).toInt()
                val hours = currentDuration / 60
                val minutes = currentDuration % 60
                holder.tvDuration.text = if (hours > 0) {
                    "⏱️ Đã ${hours}h ${minutes}p"
                } else {
                    "⏱️ Đã ${minutes} phút"
                }

                holder.tvStatus.text = "🔴 Đang đỗ"
                holder.tvStatus.setTextColor(ContextCompat.getColor(holder.itemView.context, R.color.warning_color))
                holder.tvFee.visibility = View.GONE
            }

            holder.itemView.setOnClickListener {
                onItemClick(session)
            }
        }

        override fun getItemCount() = sessions.size
    }
}