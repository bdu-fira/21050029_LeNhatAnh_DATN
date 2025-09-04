package com.example.parkingmobiapp.utils

import android.util.Patterns
import java.util.regex.Pattern

object ValidationUtils {

    // Email validation
    fun isValidEmail(email: String): Boolean {
        return email.isNotEmpty() && Patterns.EMAIL_ADDRESS.matcher(email).matches()
    }

    // Vietnamese phone number validation - UPDATED: Only 10 digits starting with 0
    fun isValidPhone(phone: String): Boolean {
        // Chỉ chấp nhận số điện thoại Việt Nam 10 số: 0xxxxxxxxx
        val phonePattern = "^0[0-9]{9}$"
        return phone.isNotEmpty() && Pattern.compile(phonePattern).matcher(phone).matches()
    }

    // Email or phone validation
    fun isValidEmailOrPhone(input: String): Boolean {
        return isValidEmail(input) || isValidPhone(input)
    }

    // Password validation
    fun isValidPassword(password: String): Boolean {
        return password.length >= 6
    }

    // Strong password validation
    fun isStrongPassword(password: String): Boolean {
        if (password.length < 8) return false

        val hasUpperCase = password.any { it.isUpperCase() }
        val hasLowerCase = password.any { it.isLowerCase() }
        val hasDigit = password.any { it.isDigit() }
        val hasSpecialChar = password.any { !it.isLetterOrDigit() }

        return hasUpperCase && hasLowerCase && hasDigit && hasSpecialChar
    }

    // Vietnamese plate number validation
    fun isValidPlateNumber(plateNumber: String): Boolean {
        val platePattern = "^[0-9]{2}[A-Z]{1,2}-[0-9]{3,6}(\\.[0-9]{2})?$"
        return plateNumber.isNotEmpty() && Pattern.compile(platePattern).matcher(plateNumber).matches()
    }

    // Full name validation
    fun isValidFullName(fullName: String): Boolean {
        return fullName.trim().length >= 2 && fullName.trim().contains(" ")
    }

    // Check if string contains only letters and spaces
    fun isValidName(name: String): Boolean {
        val namePattern = "^[a-zA-ZÀÁÂÃÈÉÊÌÍÒÓÔÕÙÚĂĐĨŨƠàáâãèéêìíòóôõùúăđĩũơƯĂẠẢẤẦẨẪẬẮẰẲẴẶẸẺẼỀỀỂưăạảấầẩẫậắằẳẵặẹẻẽềềểỄỆỈỊỌỎỐỒỔỖỘỚỜỞỠỢỤỦỨỪễệỉịọỏốồổỗộớờởỡợụủứừỬỮỰỲỴÝỶỸửữựỳỵýỷỹ\\s]+$"
        return name.isNotEmpty() && Pattern.compile(namePattern).matcher(name).matches()
    }

    // Validate age (18-100)
    fun isValidAge(age: Int): Boolean {
        return age in 18..100
    }

    // Validate OTP code
    fun isValidOTP(otp: String): Boolean {
        return otp.length == 6 && otp.all { it.isDigit() }
    }

    // Get password strength level
    fun getPasswordStrength(password: String): PasswordStrength {
        if (password.length < 6) return PasswordStrength.WEAK

        var score = 0

        // Length check
        if (password.length >= 8) score++
        if (password.length >= 12) score++

        // Character variety
        if (password.any { it.isUpperCase() }) score++
        if (password.any { it.isLowerCase() }) score++
        if (password.any { it.isDigit() }) score++
        if (password.any { !it.isLetterOrDigit() }) score++

        return when {
            score <= 2 -> PasswordStrength.WEAK
            score <= 4 -> PasswordStrength.MEDIUM
            else -> PasswordStrength.STRONG
        }
    }

    // Format phone number to display format - UPDATED for 10 digits only
    fun formatPhoneNumber(phone: String): String {
        val cleaned = phone.replace(Regex("[^0-9]"), "")

        // Chỉ format số điện thoại 10 số bắt đầu bằng 0
        return if (cleaned.length == 10 && cleaned.startsWith("0")) {
            "${cleaned.substring(0, 4)} ${cleaned.substring(4, 7)} ${cleaned.substring(7)}"
        } else {
            phone // Trả về nguyên bản nếu không đúng format
        }
    }

    // UPDATED: Specific phone validation with detailed error messages
    fun validatePhoneNumber(phone: String): PhoneValidationResult {
        val cleaned = phone.replace(Regex("[^0-9]"), "")

        return when {
            phone.isEmpty() -> PhoneValidationResult(false, "Vui lòng nhập số điện thoại")
            cleaned.length < 10 -> PhoneValidationResult(false, "Số điện thoại phải có 10 số")
            cleaned.length > 10 -> PhoneValidationResult(false, "Số điện thoại chỉ được có 10 số")
            !cleaned.startsWith("0") -> PhoneValidationResult(false, "Số điện thoại phải bắt đầu bằng số 0")
            !isValidPhone(cleaned) -> PhoneValidationResult(false, "Số điện thoại không hợp lệ")
            else -> PhoneValidationResult(true, "Số điện thoại hợp lệ")
        }
    }

    // Check if input contains potentially harmful content
    fun containsHarmfulContent(input: String): Boolean {
        val harmfulPatterns = listOf(
            "<script",
            "javascript:",
            "onclick=",
            "onerror=",
            "onload="
        )

        val lowercaseInput = input.lowercase()
        return harmfulPatterns.any { lowercaseInput.contains(it) }
    }

    // UPDATED: Helper method to get phone number examples
    fun getPhoneNumberExamples(): List<String> {
        return listOf(
            "0987654321",
            "0123456789",
            "0356789012",
            "0778901234",
            "0912345678"
        )
    }

    // UPDATED: Check if phone number has valid Vietnam mobile prefix
    fun hasValidVietnamMobilePrefix(phone: String): Boolean {
        val cleaned = phone.replace(Regex("[^0-9]"), "")
        if (cleaned.length != 10 || !cleaned.startsWith("0")) return false

        val validPrefixes = listOf(
            "032", "033", "034", "035", "036", "037", "038", "039", // Viettel
            "070", "079", "077", "076", "078", // Mobifone
            "083", "084", "085", "081", "082", // Vinaphone
            "056", "058", // Vietnamobile
            "092", "094", "088" // Gmobile
        )

        val prefix = cleaned.substring(0, 3)
        return validPrefixes.contains(prefix)
    }

    enum class PasswordStrength {
        WEAK, MEDIUM, STRONG
    }

    data class PhoneValidationResult(
        val isValid: Boolean,
        val message: String
    )
}