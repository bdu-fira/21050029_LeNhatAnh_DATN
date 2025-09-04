package com.example.parkingmobiapp.models

data class UserSettings(
    val plateNumber: String = "",
    val notificationsEnabled: Boolean = true,
    val soundEnabled: Boolean = true,
    val vibrationEnabled: Boolean = true,
    val autoRefreshEnabled: Boolean = true,
    val refreshIntervalSeconds: Int = 30,
    val selectedSoundUri: String? = null
)