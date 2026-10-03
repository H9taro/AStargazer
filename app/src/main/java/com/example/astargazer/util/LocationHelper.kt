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
     * 現在のデバイスの最新のGPS位置情報（Location）を取得する
     * パーミッションがない場合や取得できない場合は null を返す
     */
    fun getLastKnownLocation(context: Context): Location? {
        val fineGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarseGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

        if (!fineGranted && !coarseGranted) {
            Log.w("LocationHelper", "Location permissions are not granted.")
            return null
        }

        try {
            val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null

            val gpsLocation = if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            } else null

            val netLocation = if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            } else null

            return when {
                gpsLocation != null && netLocation != null -> {
                    if (gpsLocation.time > netLocation.time) gpsLocation else netLocation
                }
                gpsLocation != null -> gpsLocation
                netLocation != null -> netLocation
                else -> null
            }
        } catch (e: Exception) {
            Log.e("LocationHelper", "Failed to get last known location", e)
            return null
        }
    }
}
