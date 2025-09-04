// app/src/main/java/com/example/parkingmobiapp/models/VehicleNotification.kt
package com.example.parkingmobiapp.models
data class VehicleNotification(
    val id: String,
    val type: String = "vehicle_activity",
    val title: String,
    val message: String,
    val plateNumber: String,
    val ownerName: String? = null,
    val action: String, // "entry" or "exit"
    val timestamp: String,
    val imageUrl: String? = null,
    val parkingDuration: Int? = null, // in minutes
    val entryTime: String? = null,
    val exitTime: String? = null
)