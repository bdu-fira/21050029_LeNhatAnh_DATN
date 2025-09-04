// app/src/main/java/com/example/parkingmobiapp/SplashActivity.kt
package com.example.parkingmobiapp

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.animation.AnimationUtils
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import com.example.parkingmobiapp.services.PushNotificationService
import com.example.parkingmobiapp.utils.AppConstants
import com.example.parkingmobiapp.utils.SharedPrefsHelper

/**
 * SplashActivity - Màn hình chào mừng khi khởi động app
 * Chức năng:
 * - Hiển thị logo và thông tin ứng dụng
 * - Khởi tạo các service cần thiết
 * - Kiểm tra trạng thái đăng ký người dùng
 * - Kiểm tra trạng thái đăng nhập
 * - Loading animation và progress
 * - Điều hướng đến MainActivity hoặc LoginActivity
 */
class SplashActivity : AppCompatActivity() {

    // UI Components
    private lateinit var ivAppLogo: ImageView
    private lateinit var tvAppName: TextView
    private lateinit var tvLoadingStatus: TextView
    private lateinit var progressLoading: ProgressBar

    // Utilities
    private lateinit var sharedPrefsHelper: SharedPrefsHelper
    private lateinit var notificationService: PushNotificationService

    // Handler for delayed execution
    private val handler = Handler(Looper.getMainLooper())
    private var currentProgress = 0

    // Loading steps
    private val loadingSteps = listOf(
        "Khởi tạo ứng dụng..." to 20,
        "Kiểm tra kết nối..." to 40,
        "Tải cấu hình..." to 60,
        "Khởi tạo thông báo..." to 80,
        "Hoàn tất..." to 100
    )
    private var currentStep = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)

        // Hide action bar for full screen experience
        supportActionBar?.hide()

        // Handle back button press - disable during splash
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // Disable back button during splash
                // User should wait for initialization to complete
            }
        })

        initializeComponents()
        startInitialization()
    }

    /**
     * Khởi tạo các UI components
     */
    private fun initializeComponents() {
        ivAppLogo = findViewById(R.id.iv_app_logo_large)
        tvAppName = findViewById(R.id.tv_app_name)
        tvLoadingStatus = findViewById(R.id.tv_loading_status)
        progressLoading = findViewById(R.id.progress_loading)

        // Initialize utilities
        sharedPrefsHelper = SharedPrefsHelper(this)
        notificationService = PushNotificationService()

        // Start logo animation
        startLogoAnimation()
    }

    /**
     * Start logo animation
     */
    private fun startLogoAnimation() {
        try {
            val fadeInAnimation = AnimationUtils.loadAnimation(this, android.R.anim.fade_in)
            fadeInAnimation.duration = 1000

            ivAppLogo.startAnimation(fadeInAnimation)
            tvAppName.startAnimation(fadeInAnimation)
        } catch (e: Exception) {
            // If animation fails, continue without it
        }
    }

    /**
     * Start app initialization process
     */
    private fun startInitialization() {
        // Reset progress
        currentProgress = 0
        currentStep = 0
        progressLoading.progress = 0

        // Start loading steps
        executeNextLoadingStep()
    }

    /**
     * Execute next loading step
     */
    private fun executeNextLoadingStep() {
        if (currentStep >= loadingSteps.size) {
            // All steps completed, check authentication
            checkAuthenticationStatus()
            return
        }

        val (statusText, targetProgress) = loadingSteps[currentStep]
        tvLoadingStatus.text = statusText

        // Animate progress bar
        animateProgress(targetProgress) {
            // Execute the actual loading task
            when (currentStep) {
                0 -> initializeApp()
                1 -> checkConnection()
                2 -> loadConfiguration()
                3 -> initializeNotifications()
                4 -> finalizeInitialization()
            }

            // Move to next step after a delay
            handler.postDelayed({
                currentStep++
                executeNextLoadingStep()
            }, 500) // 500ms delay between steps
        }
    }

    /**
     * Animate progress bar to target value
     */
    private fun animateProgress(targetProgress: Int, onComplete: () -> Unit) {
        val progressAnimator = object : Runnable {
            override fun run() {
                if (currentProgress < targetProgress) {
                    currentProgress += 2 // Increment by 2 for smooth animation
                    if (currentProgress > targetProgress) {
                        currentProgress = targetProgress
                    }

                    progressLoading.progress = currentProgress
                    handler.postDelayed(this, 50) // 50ms between updates
                } else {
                    onComplete()
                }
            }
        }

        handler.post(progressAnimator)
    }

    /**
     * Step 1: Initialize app
     */
    private fun initializeApp() {
        try {
            // Check if this is first launch
            if (sharedPrefsHelper.isFirstLaunch()) {
                // Set app version
                sharedPrefsHelper.saveAppVersion(AppConstants.APP_VERSION)
                sharedPrefsHelper.setFirstLaunch(false)
            }

            // Verify app version
            val savedVersion = sharedPrefsHelper.getAppVersion()
            if (savedVersion != AppConstants.APP_VERSION) {
                // Handle app update
                handleAppUpdate(savedVersion)
            }

        } catch (e: Exception) {
            // Log error but continue
        }
    }

    /**
     * Step 2: Check connection
     */
    private fun checkConnection() {
        try {
            // TODO: Implement actual network check
            // For now, simulate connection check
            val hasInternet = true // Placeholder

            if (!hasInternet) {
                // Handle no internet connection
                // Could show retry dialog or continue offline
            }

        } catch (e: Exception) {
            // Continue even if connection check fails
        }
    }

    /**
     * Step 3: Load configuration
     */
    private fun loadConfiguration() {
        try {
            // Load user settings
            val userSettings = sharedPrefsHelper.getUserSettings()

            // Validate settings
            if (userSettings.plateNumber.isEmpty()) {
                // User needs to register plate number
                // MainActivity will handle this
            }

            // Load server configuration
            val serverUrl = sharedPrefsHelper.getServerUrl()
            if (serverUrl.isEmpty()) {
                // Set default server URL
                sharedPrefsHelper.saveServerUrl(AppConstants.BASE_URL)
            }

        } catch (e: Exception) {
            // Use default configuration
        }
    }

    /**
     * Step 4: Initialize notifications
     */
    private fun initializeNotifications() {
        try {
            // Create notification channels
            notificationService.createNotificationChannels(this)

            // Check notification permissions
            val notificationsEnabled = notificationService.areNotificationsEnabled(this)
            if (!notificationsEnabled) {
                // MainActivity will request permissions
            }

        } catch (e: Exception) {
            // Continue without notifications for now
        }
    }

    /**
     * Step 5: Finalize initialization
     */
    private fun finalizeInitialization() {
        try {
            // Update last launch time
            sharedPrefsHelper.saveLastUpdateTime(System.currentTimeMillis())

            // Clear old notification count if needed
            val lastNotificationTime = sharedPrefsHelper.getLastNotificationTime()
            val oneDayAgo = System.currentTimeMillis() - 86400000 // 24 hours

            if (lastNotificationTime < oneDayAgo) {
                // Reset notification count after 24 hours
                sharedPrefsHelper.resetNotificationCount()
            }

        } catch (e: Exception) {
            // Continue to main activity
        }
    }

    /**
     * Step 6: Check authentication status
     */
    private fun checkAuthenticationStatus() {
        try {
            // Kiểm tra xem user đã đăng nhập chưa
            val isLoggedIn = sharedPrefsHelper.isUserLoggedIn()

            if (!isLoggedIn) {
                // User chưa đăng nhập, chuyển đến LoginActivity
                navigateToLoginActivity()
                return
            }

            // User đã đăng nhập, tiếp tục đến MainActivity
            navigateToMainActivity()

        } catch (e: Exception) {
            // Nếu có lỗi, mặc định chuyển đến LoginActivity
            navigateToLoginActivity()
        }
    }

    /**
     * Navigate to LoginActivity
     */
    private fun navigateToLoginActivity() {
        try {
            val intent = Intent(this, LoginActivity::class.java)
            intent.putExtra("from_splash", true)

            startActivity(intent)

            // Add transition animation
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)

            // Finish splash activity
            finish()

        } catch (e: Exception) {
            // If navigation fails, try again
            handler.postDelayed({
                navigateToLoginActivity()
            }, 1000)
        }
    }

    /**
     * Navigate to MainActivity
     */
    private fun navigateToMainActivity() {
        try {
            val intent = Intent(this, MainActivity::class.java)

            // Add any necessary extras
            intent.putExtra("from_splash", true)

            // Check if we should show onboarding or go directly to main
            val plateNumber = sharedPrefsHelper.getPlateNumber()
            if (plateNumber.isEmpty()) {
                intent.putExtra("show_registration", true)
            }

            startActivity(intent)

            // Add transition animation
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)

            // Finish splash activity
            finish()

        } catch (e: Exception) {
            // If navigation fails, try again
            handler.postDelayed({
                navigateToMainActivity()
            }, 1000)
        }
    }

    /**
     * Handle app update
     */
    private fun handleAppUpdate(oldVersion: String) {
        try {
            // Perform version-specific update tasks
            when {
                oldVersion < "1.0.0" -> {
                    // Handle updates from versions before 1.0.0
                    // Could migrate data, update settings, etc.
                }
            }

            // Update stored version
            sharedPrefsHelper.saveAppVersion(AppConstants.APP_VERSION)

        } catch (e: Exception) {
            // If update fails, continue with current settings
        }
    }

    /**
     * Handle activity pause
     */
    override fun onPause() {
        super.onPause()

        // If user leaves during splash, finish the activity
        if (!isFinishing) {
            finish()
        }
    }

    /**
     * Handle activity destroy
     */
    override fun onDestroy() {
        super.onDestroy()

        // Clean up handler callbacks
        handler.removeCallbacksAndMessages(null)
    }

    /**
     * Handle low memory
     */
    override fun onLowMemory() {
        super.onLowMemory()

        // Skip remaining animations and go directly to appropriate screen
        handler.removeCallbacksAndMessages(null)
        checkAuthenticationStatus()
    }
}