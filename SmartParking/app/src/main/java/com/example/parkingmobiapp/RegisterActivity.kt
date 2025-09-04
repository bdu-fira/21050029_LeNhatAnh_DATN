package com.example.parkingmobiapp

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.parkingmobiapp.utils.SharedPrefsHelper
import com.example.parkingmobiapp.utils.ValidationUtils

/**
 * RegisterActivity - Màn hình đăng ký tài khoản
 * Chức năng:
 * - Đăng ký tài khoản mới
 * - Validate thông tin đầu vào
 * - Chuyển về màn hình đăng nhập
 */
class RegisterActivity : AppCompatActivity() {

    private lateinit var etFullName: EditText
    private lateinit var etEmail: EditText
    private lateinit var etPhone: EditText
    private lateinit var etPassword: EditText
    private lateinit var etConfirmPassword: EditText
    private lateinit var btnRegister: Button
    private lateinit var tvLogin: TextView

    private lateinit var sharedPrefsHelper: SharedPrefsHelper

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_register)

        initializeComponents()
        setupClickListeners()
    }

    private fun initializeComponents() {
        etFullName = findViewById(R.id.et_full_name)
        etEmail = findViewById(R.id.et_email)
        etPhone = findViewById(R.id.et_phone)
        etPassword = findViewById(R.id.et_password)
        etConfirmPassword = findViewById(R.id.et_confirm_password)
        btnRegister = findViewById(R.id.btn_register)
        tvLogin = findViewById(R.id.tv_login)

        sharedPrefsHelper = SharedPrefsHelper(this)
    }

    private fun setupClickListeners() {
        btnRegister.setOnClickListener {
            handleRegister()
        }

        tvLogin.setOnClickListener {
            navigateToLogin()
        }
    }

    private fun handleRegister() {
        val fullName = etFullName.text.toString().trim()
        val email = etEmail.text.toString().trim()
        val phone = etPhone.text.toString().trim()
        val password = etPassword.text.toString().trim()
        val confirmPassword = etConfirmPassword.text.toString().trim()

        // Validate input
        if (!validateInput(fullName, email, phone, password, confirmPassword)) {
            return
        }

        // TODO: Replace with actual API call
        performRegister(fullName, email, phone, password)
    }

    private fun validateInput(
        fullName: String,
        email: String,
        phone: String,
        password: String,
        confirmPassword: String
    ): Boolean {
        var isValid = true

        // Validate full name
        if (fullName.isEmpty()) {
            etFullName.error = "Vui lòng nhập họ tên"
            isValid = false
        } else if (fullName.length < 2) {
            etFullName.error = "Họ tên phải có ít nhất 2 ký tự"
            isValid = false
        }

        // Validate email
        if (email.isEmpty()) {
            etEmail.error = "Vui lòng nhập email"
            isValid = false
        } else if (!ValidationUtils.isValidEmail(email)) {
            etEmail.error = "Email không hợp lệ"
            isValid = false
        }

        // Validate phone
        if (phone.isEmpty()) {
            etPhone.error = "Vui lòng nhập số điện thoại"
            isValid = false
        } else if (!ValidationUtils.isValidPhone(phone)) {
            etPhone.error = "Số điện thoại không hợp lệ"
            isValid = false
        }

        // Validate password
        if (password.isEmpty()) {
            etPassword.error = "Vui lòng nhập mật khẩu"
            isValid = false
        } else if (password.length < 6) {
            etPassword.error = "Mật khẩu phải có ít nhất 6 ký tự"
            isValid = false
        }

        // Validate confirm password
        if (confirmPassword.isEmpty()) {
            etConfirmPassword.error = "Vui lòng xác nhận mật khẩu"
            isValid = false
        } else if (password != confirmPassword) {
            etConfirmPassword.error = "Mật khẩu xác nhận không khớp"
            isValid = false
        }

        return isValid
    }

    private fun performRegister(fullName: String, email: String, phone: String, password: String) {
        // Show loading state
        btnRegister.isEnabled = false
        btnRegister.text = "Đang đăng ký..."

        // TODO: Replace with actual API call
        // For demo purposes, simulate registration
        simulateRegister(fullName, email, phone, password)
    }

    private fun simulateRegister(fullName: String, email: String, phone: String, password: String) {
        // Simulate API delay
        android.os.Handler(mainLooper).postDelayed({
            // Simulate successful registration
            // In real implementation, check if email/phone already exists

            showToast("Đăng ký thành công! Vui lòng đăng nhập.")

            // Navigate back to login
            navigateToLogin()
        }, 2000)
    }

    private fun navigateToLogin() {
        val intent = Intent(this, LoginActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP
        startActivity(intent)
        finish()
    }

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}