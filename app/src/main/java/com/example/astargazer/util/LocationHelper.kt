package com.example.astargazer.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.util.Log
import androidx.core.content.ContextCompat

object LocationHelper {

    /**
     * 利用可能な最新の GPS / Network 位置情報を取得する
     */
    fun getLastKnownLocation(context: Context): Location? {
        val hasFine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val hasCoarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

        if (!hasFine && !hasCoarse) {
            return null
        }

        try {
            val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null

            // GPS または Network プロバイダから最後の既知の位置情報を取得
            val gpsLocation = if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            } else {
                null
            }

            val networkLocation = if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            } else {
                null
            }

            // より新しい（または有効な）ロケーションを返す
            return when {
                gpsLocation != null && networkLocation != null -> {
                    if (gpsLocation.time > networkLocation.time) gpsLocation else networkLocation
                }
                gpsLocation != null -> gpsLocation
                else -> networkLocation
            }
        } catch (e: Exception) {
            Log.e("LocationHelper", "Failed to get last known location", e)
            return null
        }
    }
}
