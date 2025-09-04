// File: app/src/main/java/com/example/parkingmobiapp/model/ParkingResponse.kt

package com.example.parkingmobiapp.models

// Simple response model for internal repository use
data class ParkingResponse(
    val success: Boolean = false,
    val message: String = "",
    val sessionId: String? = null,
    val totalCost: Double = 0.0
)