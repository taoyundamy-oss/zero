package com.zero.v2.app.system

import android.app.ActivityManager
import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import androidx.core.app.NotificationManagerCompat
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.zero.v2.core.moduleapi.PermissionStatus
import com.zero.v2.core.moduleapi.ResourceStatus
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/** Reads Android owners only; never requests permissions or starts services. */
class DeviceStateReader @Inject constructor(@ApplicationContext private val context: Context) {
    fun resources(now: Long): ResourceStatus {
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val percent = if (level >= 0 && scale > 0) level * 100 / scale else null
        val memory = ActivityManager.MemoryInfo()
        context.getSystemService(ActivityManager::class.java)?.getMemoryInfo(memory)
        val power = context.getSystemService(PowerManager::class.java)
        val thermal = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) power?.currentThermalStatus else null
        return ResourceStatus(
            batteryBand = percent?.let { if (it <= 15) "low" else if (it <= 40) "medium" else "high" },
            charging = battery?.let { it.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0 },
            thermalBand = thermal?.let { if (it >= PowerManager.THERMAL_STATUS_SEVERE) "high" else "normal" },
            memoryPressure = memory.lowMemory,
            observedAt = now,
        )
    }

    fun permissions(now: Long): List<PermissionStatus> {
        val requested = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions.orEmpty()
        val result = requested.filter { permission ->
            val info = context.packageManager.getPermissionInfo(permission, 0)
            info.protectionLevel and PermissionInfo.PROTECTION_MASK_BASE == PermissionInfo.PROTECTION_DANGEROUS
        }.map { permission ->
            PermissionStatus(permission,
                ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED,
                observedAt = now)
        }.toMutableList()
        val accessibility = Settings.Secure.getString(context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty().split(':')
            .any { it.substringBefore('/') == context.packageName }
        result += PermissionStatus("accessibility", accessibility, observedAt = now)
        result += PermissionStatus("overlay", Settings.canDrawOverlays(context), observedAt = now)
        result += PermissionStatus("notification_listener",
            context.packageName in NotificationManagerCompat.getEnabledListenerPackages(context), observedAt = now)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val granted = context.getSystemService(AlarmManager::class.java)?.canScheduleExactAlarms() == true
            result += PermissionStatus("exact_alarm", granted, observedAt = now)
        }
        return result
    }
}
