// File: app/src/main/java/com/example/parkingmobiapp/utils/LocationUtils.kt

package com.example.parkingmobiapp.utils

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import androidx.core.content.ContextCompat
import kotlin.math.*

object LocationUtils {

    // Check if location permission is granted
    fun hasLocationPermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
    }

    // Calculate distance between two points using Haversine formula
    fun calculateDistance(
        lat1: Double,
        lon1: Double,
        lat2: Double,
        lon2: Double
    ): Double {
        val earthRadius = 6371000.0 // Earth radius in meters

        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)

        val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2) * sin(dLon / 2)

        val c = 2 * atan2(sqrt(a), sqrt(1 - a))

        return earthRadius * c // Distance in meters
    }

    // Calculate distance between Location objects
    fun calculateDistance(location1: Location, location2: Location): Double {
        return calculateDistance(
            location1.latitude,
            location1.longitude,
            location2.latitude,
            location2.longitude
        )
    }

    // Check if user is within allowed distance from parking spot
    fun isWithinParkingDistance(
        currentLat: Double,
        currentLon: Double,
        parkingLat: Double,
        parkingLon: Double,
        allowedDistanceMeters: Double = 50.0
    ): Boolean {
        val distance = calculateDistance(currentLat, currentLon, parkingLat, parkingLon)
        return distance <= allowedDistanceMeters
    }

    // Format distance for display
    fun formatDistance(distanceInMeters: Double): String {
        return when {
            distanceInMeters < 1000 -> "${distanceInMeters.toInt()}m"
            else -> "${String.format("%.1f", distanceInMeters / 1000)}km"
        }
    }

    // Get bearing between two points
    fun getBearing(
        lat1: Double,
        lon1: Double,
        lat2: Double,
        lon2: Double
    ): Double {
        val dLon = Math.toRadians(lon2 - lon1)
        val lat1Rad = Math.toRadians(lat1)
        val lat2Rad = Math.toRadians(lat2)

        val y = sin(dLon) * cos(lat2Rad)
        val x = cos(lat1Rad) * sin(lat2Rad) - sin(lat1Rad) * cos(lat2Rad) * cos(dLon)

        var bearing = Math.toDegrees(atan2(y, x))
        bearing = (bearing + 360) % 360

        return bearing
    }

    // Convert bearing to direction text
    fun bearingToDirection(bearing: Double): String {
        return when {
            bearing >= 337.5 || bearing < 22.5 -> "Bắc"
            bearing >= 22.5 && bearing < 67.5 -> "Đông Bắc"
            bearing >= 67.5 && bearing < 112.5 -> "Đông"
            bearing >= 112.5 && bearing < 157.5 -> "Đông Nam"
            bearing >= 157.5 && bearing < 202.5 -> "Nam"
            bearing >= 202.5 && bearing < 247.5 -> "Tây Nam"
            bearing >= 247.5 && bearing < 292.5 -> "Tây"
            bearing >= 292.5 && bearing < 337.5 -> "Tây Bắc"
            else -> "Không xác định"
        }
    }

    // Check if location is valid
    fun isValidLocation(latitude: Double, longitude: Double): Boolean {
        return latitude >= -90.0 && latitude <= 90.0 &&
                longitude >= -180.0 && longitude <= 180.0
    }

    // Create Location object
    fun createLocation(latitude: Double, longitude: Double, provider: String = "manual"): Location {
        val location = Location(provider)
        location.latitude = latitude
        location.longitude = longitude
        location.time = System.currentTimeMillis()
        return location
    }

    // Get location accuracy description
    fun getAccuracyDescription(accuracy: Float): String {
        return when {
            accuracy < 5 -> "Rất chính xác"
            accuracy < 10 -> "Chính xác"
            accuracy < 25 -> "Tương đối chính xác"
            accuracy < 50 -> "Kém chính xác"
            else -> "Rất kém chính xác"
        }
    }
}