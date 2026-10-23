    package com.example.optimus.helper

    import android.annotation.SuppressLint
    import android.content.Context
    import android.content.Intent
    import android.content.IntentFilter
    import android.media.AudioManager
    import android.net.ConnectivityManager
    import android.net.NetworkCapabilities
    import android.os.BatteryManager
    import android.os.PowerManager
    import com.google.android.gms.location.LocationServices
    import com.google.android.gms.location.Priority
    import android.Manifest
    import android.content.pm.PackageManager
    import android.util.Log
    import androidx.core.content.ContextCompat

    object DeviceInfo {

        fun getBatteryLevel(context: Context): String {
            val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            return "${(level * 100 / scale.toFloat()).toInt()}%"
        }

        fun getTemperature(context: Context): String {
            val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val temp = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0
            val celsius = temp / 10
            return "${celsius}°"
        }

        fun getRamUsageFactor(context: Context): Float {
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            val memoryInfo = android.app.ActivityManager.MemoryInfo()
            activityManager.getMemoryInfo(memoryInfo)
            return (memoryInfo.totalMem - memoryInfo.availMem).toFloat() / memoryInfo.totalMem
        }

        fun isScreenOn(context: Context): Boolean {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            return pm.isInteractive
        }

        fun isPhoneInUse(context: Context): Boolean {
            return isScreenOn(context)
        }

        fun isHeadphonesConnected(context: Context): Boolean {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            return audioManager.isWiredHeadsetOn || audioManager.isBluetoothA2dpOn
        }

        fun isInternetAvailable(context: Context): Boolean {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(network) ?: return false
            return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }

        fun isCharging(context: Context): Boolean {
            val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            return status == BatteryManager.BATTERY_STATUS_CHARGING ||
                    status == BatteryManager.BATTERY_STATUS_FULL
        }

        @SuppressLint("MissingPermission")
        fun getFreshLocation(
            context: Context,
            callback: (Double?, Double?) -> Unit
        ) {
            val fineGranted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED

            val coarseGranted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED

            if (!fineGranted && !coarseGranted) {
                Log.w("OPTIMUS", "Location permission has not been granted.")
                callback(null, null)
                return
            }

            try {
                val fused = LocationServices
                    .getFusedLocationProviderClient(context)

                fused.getCurrentLocation(
                    Priority.PRIORITY_HIGH_ACCURACY,
                    null
                )
                    .addOnSuccessListener { location ->
                        if (location != null) {
                            callback(
                                location.latitude,
                                location.longitude
                            )
                        } else {
                            Log.w(
                                "OPTIMUS",
                                "No current location was available."
                            )
                            callback(null, null)
                        }
                    }
                    .addOnFailureListener { error ->
                        Log.e(
                            "OPTIMUS",
                            "Could not obtain current location",
                            error
                        )
                        callback(null, null)
                    }

            } catch (e: SecurityException) {
                Log.e("OPTIMUS", "Location permission was revoked.", e)
                callback(null, null)
            }
        }
    }
