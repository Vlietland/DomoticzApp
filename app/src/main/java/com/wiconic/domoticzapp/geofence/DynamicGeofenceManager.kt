package com.wiconic.domoticzapp.geofence

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.app.ActivityCompat
import okhttp3.*
import java.io.IOException

class DynamicGeofenceManager(
    private val context: Context,
    private val geofenceCenterLat: Double,
    private val geofenceCenterLon: Double,
    private val geofenceRadius: Float,
    private val measurementsBeforeTrigger: Int,
    private val pollingFrequency: Long
) {
    private val client = OkHttpClient()
    private var isTriggered = false
    private var hasLeftArea = true // Initially true to allow first trigger
    private var consecutiveInAreaCount = 0
    private val handler = Handler(Looper.getMainLooper())
    private var isMonitoring = false
    private var locationManager: LocationManager? = null
    
    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            checkGeofence(location)
        }
        
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        
        override fun onProviderEnabled(provider: String) {}
        
        override fun onProviderDisabled(provider: String) {}
    }
    
    private val pollingRunnable = object : Runnable {
        override fun run() {
            requestLocationUpdate()
            if (isMonitoring) {
                handler.postDelayed(this, pollingFrequency)
            }
        }
    }

    fun startMonitoring() {
        if (isMonitoring) return
        
        isMonitoring = true
        isTriggered = false
        consecutiveInAreaCount = 0
        
        locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        
        // Start polling for location updates
        handler.post(pollingRunnable)
        
        Log.i("DynamicGeofenceManager", "Started monitoring with radius $geofenceRadius, " +
                "measurements before trigger $measurementsBeforeTrigger, " +
                "polling frequency $pollingFrequency ms")
    }
    
    private fun requestLocationUpdate() {
        if (ActivityCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.e("DynamicGeofenceManager", "Location permission not granted")
            return
        }
        
        try {
            locationManager?.requestSingleUpdate(
                LocationManager.GPS_PROVIDER,
                locationListener,
                Looper.getMainLooper()
            )
        } catch (e: Exception) {
            Log.e("DynamicGeofenceManager", "Error requesting location update: ${e.message}")
        }
    }
    
    private fun checkGeofence(location: Location) {
        val distance = calculateDistance(
            location.latitude, location.longitude,
            geofenceCenterLat, geofenceCenterLon
        )
        
        val isInArea = distance <= geofenceRadius
        
        Log.d("DynamicGeofenceManager", "Location check: in area = $isInArea, " +
                "distance = $distance, radius = $geofenceRadius, " +
                "consecutive count = $consecutiveInAreaCount")
        
        if (isInArea) {
            consecutiveInAreaCount++
            
            // Check if we've reached the required number of measurements and haven't triggered yet
            if (consecutiveInAreaCount >= measurementsBeforeTrigger && !isTriggered && hasLeftArea) {
                sendProximityTrigger()
                isTriggered = true
                Log.i("DynamicGeofenceManager", "Proximity trigger activated after $consecutiveInAreaCount measurements")
            }
        } else {
            // Reset counter when outside the area
            consecutiveInAreaCount = 0
            
            // If we were previously triggered and now we're outside, allow for a new trigger
            if (isTriggered) {
                isTriggered = false
                hasLeftArea = true
                Log.i("DynamicGeofenceManager", "Left geofence area, ready for new trigger")
            }
        }
    }
    
    private fun calculateDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float {
        val results = FloatArray(1)
        Location.distanceBetween(lat1, lon1, lat2, lon2, results)
        return results[0]
    }

    private fun sendProximityTrigger() {
        val url = "https://domoticz-server.com/api/proximity-trigger" // Replace with actual URL
        val request = Request.Builder()
            .url(url)
            .post(RequestBody.create(null, ByteArray(0)))
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e("DynamicGeofenceManager", "Failed to send proximity trigger: ${e.message}")
            }

            override fun onResponse(call: Call, response: Response) {
                if (response.isSuccessful) {
                    Log.i("DynamicGeofenceManager", "Proximity trigger sent successfully")
                } else {
                    Log.e("DynamicGeofenceManager", "Failed to send proximity trigger: ${response.code}")
                }
            }
        })
    }

    fun stopMonitoring() {
        if (!isMonitoring) return
        
        isMonitoring = false
        handler.removeCallbacks(pollingRunnable)
        
        if (ActivityCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            locationManager?.removeUpdates(locationListener)
        }
        
        locationManager = null
        Log.i("DynamicGeofenceManager", "Stopped monitoring")
    }
}
