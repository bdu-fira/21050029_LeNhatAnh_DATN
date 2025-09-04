package com.example.parkingmobiapp

import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AlertDialog
import android.util.Log

class ReportIssueActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "ReportIssueActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Create simple layout programmatically
        val scrollView = ScrollView(this)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(30, 30, 30, 30)
        }

        // Back button
        val backButton = Button(this).apply {
            text = "← Quay lại"
            setOnClickListener { finish() }
        }

        // Title
        val title = TextView(this).apply {
            text = "⚠️ Báo cáo sự cố"
            textSize = 24f
            setPadding(0, 20, 0, 30)
        }

        // Issue type selection
        val issueLabel = TextView(this).apply {
            text = "📋 Loại sự cố:"
            textSize = 18f
            setPadding(0, 0, 0, 15)
        }

        val issueSpinner = Spinner(this).apply {
            val issues = arrayOf(
                "🚗 Xe bị chặn lối ra",
                "🔧 Thiết bị bị hỏng",
                "💡 Đèn không hoạt động",
                "🧹 Khu vực bẩn",
                "🚫 Vị trí bị chiếm dụng",
                "📱 Khác..."
            )
            adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, issues)
        }

        // Location input
        val locationLabel = TextView(this).apply {
            text = "📍 Vị trí sự cố:"
            textSize = 18f
            setPadding(0, 30, 0, 15)
        }

        val locationInput = EditText(this).apply {
            hint = "VD: Khu A - Vị trí A12"
            setPadding(20, 20, 20, 20)
        }

        // Description input
        val descLabel = TextView(this).apply {
            text = "📝 Mô tả chi tiết:"
            textSize = 18f
            setPadding(0, 30, 0, 15)
        }

        val descInput = EditText(this).apply {
            hint = "Mô tả chi tiết sự cố..."
            minLines = 3
            maxLines = 5
            setPadding(20, 20, 20, 20)
        }

        // Contact info
        val contactLabel = TextView(this).apply {
            text = "📞 Thông tin liên hệ:"
            textSize = 18f
            setPadding(0, 30, 0, 15)
        }

        val nameInput = EditText(this).apply {
            hint = "Họ và tên"
            setPadding(20, 20, 20, 20)
        }

        val phoneInput = EditText(this).apply {
            hint = "Số điện thoại"
            inputType = android.text.InputType.TYPE_CLASS_PHONE
            setPadding(20, 20, 20, 20)
        }

        // Submit button
        val submitButton = Button(this).apply {
            text = "📤 Gửi báo cáo"
            textSize = 18f
            setPadding(0, 40, 0, 0)
            setOnClickListener {
                handleSubmit(issueSpinner, locationInput, descInput, nameInput, phoneInput)
            }
        }

        // Emergency contact
        val emergency = TextView(this).apply {
            text = "🆘 Trường hợp khẩn cấp: Liên hệ bảo vệ"
            textSize = 14f
            setPadding(0, 40, 0, 0)
            setTextColor(android.graphics.Color.RED)
            setOnClickListener {
                showEmergencyDialog()
            }
        }

        // Add all views to layout
        layout.addView(backButton)
        layout.addView(title)
        layout.addView(issueLabel)
        layout.addView(issueSpinner)
        layout.addView(locationLabel)
        layout.addView(locationInput)
        layout.addView(descLabel)
        layout.addView(descInput)
        layout.addView(contactLabel)
        layout.addView(nameInput)
        layout.addView(phoneInput)
        layout.addView(submitButton)
        layout.addView(emergency)

        scrollView.addView(layout)
        setContentView(scrollView)

        showToast("⚠️ Form báo cáo sự cố")
        Log.d(TAG, "✅ ReportIssueActivity opened")
    }

    private fun handleSubmit(
        issueSpinner: Spinner,
        locationInput: EditText,
        descInput: EditText,
        nameInput: EditText,
        phoneInput: EditText
    ) {
        try {
            val selectedIssue = issueSpinner.selectedItem.toString()
            val location = locationInput.text.toString().trim()
            val description = descInput.text.toString().trim()
            val name = nameInput.text.toString().trim()
            val phone = phoneInput.text.toString().trim()

            // Simple validation
            if (location.isEmpty()) {
                showToast("⚠️ Vui lòng nhập vị trí sự cố")
                return
            }

            if (description.isEmpty()) {
                showToast("⚠️ Vui lòng mô tả sự cố")
                return
            }

            if (name.isEmpty() || phone.isEmpty()) {
                showToast("⚠️ Vui lòng nhập thông tin liên hệ")
                return
            }

            // Show confirmation
            val reportInfo = """
                📋 Báo cáo sự cố:
                
                🔸 Loại: $selectedIssue
                📍 Vị trí: $location
                📝 Mô tả: $description
                👤 Người báo: $name
                📞 SĐT: $phone
                
                Báo cáo sẽ được gửi đến quản lý bãi xe.
            """.trimIndent()

            AlertDialog.Builder(this)
                .setTitle("✅ Xác nhận gửi báo cáo")
                .setMessage(reportInfo)
                .setPositiveButton("📤 Gửi") { _, _ ->
                    submitReport(selectedIssue, location, description, name, phone)
                }
                .setNegativeButton("❌ Hủy", null)
                .show()

        } catch (e: Exception) {
            Log.e(TAG, "Error handling submit", e)
            showToast("❌ Lỗi khi xử lý form")
        }
    }

    private fun submitReport(issue: String, location: String, desc: String, name: String, phone: String) {
        try {
            // TODO: Send to actual server/email/system
            Log.d(TAG, "📤 Report to submit: $issue at $location by $name")

            // For now, just show success
            AlertDialog.Builder(this)
                .setTitle("📤 Báo cáo đã được ghi nhận")
                .setMessage("""
                    Báo cáo của bạn đã được ghi nhận.
                    
                    Chúng tôi sẽ xử lý sớm nhất có thể.
                    
                    Cảm ơn bạn đã góp ý cải thiện dịch vụ!
                """.trimIndent())
                .setPositiveButton("👌 OK") { _, _ ->
                    finish() // Close activity
                }
                .setCancelable(false)
                .show()

        } catch (e: Exception) {
            Log.e(TAG, "Error submitting report", e)
            showToast("❌ Lỗi khi gửi báo cáo")
        }
    }

    private fun showEmergencyDialog() {
        AlertDialog.Builder(this)
            .setTitle("📞 Liên hệ khẩn cấp")
            .setMessage("Trường hợp khẩn cấp, vui lòng liên hệ trực tiếp với bảo vệ hoặc quản lý bãi xe.")
            .setPositiveButton("👌 Hiểu rồi", null)
            .show()
    }

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}