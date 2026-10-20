package com.zero.v2.services.module

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Handler
import android.os.Looper
import com.zero.v2.core.common.ActionPermission
import com.zero.v2.core.common.FunctionDefinition
import com.zero.v2.core.moduleapi.IZeroModule
import com.zero.v2.core.moduleapi.ModuleCategory
import com.zero.v2.core.moduleapi.ModuleConfigBase
import com.zero.v2.core.moduleapi.ModuleLifecycle
import com.zero.v2.core.moduleapi.ModuleMetadata
import com.zero.v2.core.common.FunctionResult
import com.zero.v2.core.trace.AgentTraceManager
import com.zero.v2.core.trace.NodeType
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * 位置服务模块 — Step 9.2（D58 系统级感知）。
 *
 * 定位策略：① getLastKnownLocation 缓存优先（零功耗，10 分钟内有效）
 *          → ② requestLocationUpdates 一次性请求（GPS/网络双通道，10s 超时）
 * FC get_location()（AUTO）：返回 lat/lng/精度/来源 + 时间戳。
 * 与高德 MCP 协同：LLM 拿坐标后可调 maps_regeocode 逆地理编码获取地址。
 */
@Singleton
class LocationModule @Inject constructor(
    @ApplicationContext private val context: Context,

    private val traceManager: AgentTraceManager
) : IZeroModule, com.zero.v2.core.moduleapi.ToolContributor {

    companion object {
        private const val TRACE_ID = "location"
        /** 缓存位置有效期（10 分钟） */
        private const val CACHE_MAX_AGE_MS = 10 * 60 * 1000L
        /** 请求新位置超时 */
        private const val REQUEST_TIMEOUT_MS = 10_000L
    }

    override val metadata = ModuleMetadata(
        id = "location",
        displayName = "位置服务",
        description = "获取设备当前位置（GPS/网络定位），可配合高德地图逆地理编码",
        category = ModuleCategory.SYSTEM,
        launchOrder = 115
    )

    private val _lifecycle = MutableStateFlow(ModuleLifecycle.UNINITIALIZED)
    override val lifecycle = _lifecycle.asStateFlow()
    override var isEnabled: Boolean = true
    override val requiredPermissions: List<String> = listOf(
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.ACCESS_FINE_LOCATION
    )
    override val config = object : ModuleConfigBase() {
        override fun toJson() = "{}"
        override fun fromJson(json: String) {}
        override fun defaultConfig() = this
    }

    override suspend fun onAwake() {

        _lifecycle.value = ModuleLifecycle.AWAKE
    }
    override suspend fun onStart() { _lifecycle.value = ModuleLifecycle.STARTED }
    override suspend fun onDestroy() { _lifecycle.value = ModuleLifecycle.DESTROYED }
    override suspend fun onConfigChanged(newConfig: ModuleConfigBase) {}
    override suspend fun checkAvailability(): Boolean = hasLocationPermission()
    override fun markDisabled() { _lifecycle.value = ModuleLifecycle.DISABLED }

    override fun contributions(): List<com.zero.v2.core.moduleapi.ToolContribution> =
        com.zero.v2.core.moduleapi.toolDeclarations(metadata.id) {
        val functionRegistry = this
        functionRegistry.register(
            FunctionDefinition(
                name = "get_location",
                description = "获取设备当前位置（纬度/经度/精度）。获取后可配合高德地图工具逆地理编码获取详细地址，" +
                        "或用于路线规划、周边搜索等位置相关查询。",
                parameters = mapOf("type" to "object", "properties" to emptyMap<String, Any>())
            ),
            metadata.id, permission = ActionPermission.AUTO
        ) { getLocation() }
    }

    // ═══ 定位逻辑 ═══

    private fun hasLocationPermission(): Boolean = try {
        context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED ||
        context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
    } catch (_: Exception) { false }

    private suspend fun getLocation(): FunctionResult = withContext(Dispatchers.IO) {
        if (!hasLocationPermission()) {
            traceManager.record(TRACE_ID, NodeType.ERROR, metadata.id, "no_permission")
            return@withContext FunctionResult.failure(
                "位置权限未授予。请引导用户到 设置→权限中心 授权位置权限。")
        }
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val t0 = System.currentTimeMillis()

        // 策略 ①：缓存位置（零功耗）
        val cached = getCachedLocation(lm)
        if (cached != null) {
            traceManager.record(TRACE_ID, NodeType.SYSTEM_EVENT, metadata.id,
                "location_cached: provider=${cached.provider} accuracy=${"%.0f".format(cached.accuracy)}m")
            return@withContext FunctionResult.success(formatLocation(cached, "cached"))
        }

        // 策略 ②：请求新位置（GPS/网络双通道，10s 超时）
        val fresh = withTimeoutOrNull(REQUEST_TIMEOUT_MS) { requestLocation(lm) }
        if (fresh != null) {
            traceManager.record(TRACE_ID, NodeType.SYSTEM_EVENT, metadata.id,
                "location_fresh: provider=${fresh.provider} accuracy=${"%.0f".format(fresh.accuracy)}m " +
                        "ms=${System.currentTimeMillis() - t0}")
            return@withContext FunctionResult.success(formatLocation(fresh, "fresh"))
        }

        traceManager.record(TRACE_ID, NodeType.ERROR, metadata.id, "location_timeout")
        FunctionResult.failure("无法获取位置（GPS/网络均不可用或 ${REQUEST_TIMEOUT_MS / 1000}s 超时）——" +
                "请检查设备定位服务是否开启")
    }

    /** 获取缓存位置（多 provider 取最新，10 分钟内有效） */
    @Suppress("NestedBlockDepth") // 既有 provider/权限/异常三层防御，后续在 Location 专项拆分
    @SuppressLint("MissingPermission") // 方法入口同时检查 coarse/fine，调用仍有 SecurityException 兜底
    private fun getCachedLocation(lm: LocationManager): Location? {
        if (context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) !=
                PackageManager.PERMISSION_GRANTED &&
            context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) !=
                PackageManager.PERMISSION_GRANTED
        ) return null
        val providers = listOf(
            LocationManager.NETWORK_PROVIDER,
            LocationManager.GPS_PROVIDER,
            LocationManager.PASSIVE_PROVIDER
        )
        var best: Location? = null
        for (provider in providers) {
            try {
                val loc = lm.getLastKnownLocation(provider) ?: continue
                if (System.currentTimeMillis() - loc.time < CACHE_MAX_AGE_MS) {
                    if (best == null || loc.time > best!!.time) best = loc
                }
            } catch (_: SecurityException) { continue }
        }
        return best
    }

    /** 一次性请求位置（主线程 callback，双 provider 并发） */
    @SuppressLint("MissingPermission") // 方法入口同时检查 coarse/fine，异步调用仍有 SecurityException 兜底
    private suspend fun requestLocation(lm: LocationManager): Location? {
        if (context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) !=
                PackageManager.PERMISSION_GRANTED &&
            context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) !=
                PackageManager.PERMISSION_GRANTED
        ) return null
        return suspendCancellableCoroutine { cont ->
            val mainHandler = Handler(Looper.getMainLooper())
            var gotResult = false

            val listener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    if (gotResult) return
                    gotResult = true
                    mainHandler.post { lm.removeUpdates(this) }
                    if (cont.isActive) cont.resume(location)
                }
            }

            mainHandler.post {
                try {
                    lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 0, 0f, listener)
                    lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 0, 0f, listener)
                } catch (_: SecurityException) {
                    if (!gotResult && cont.isActive) cont.resume(null)
                }
            }

            cont.invokeOnCancellation {
                mainHandler.post { lm.removeUpdates(listener) }
            }
        }
    }

    private fun formatLocation(loc: Location, source: String): String {
        val fmt = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
        val time = fmt.format(Date(loc.time))
        val lat = "%.4f".format(loc.latitude)
        val lng = "%.4f".format(loc.longitude)
        val acc = "%.0f".format(loc.accuracy)
        return "📍 当前位置: lat=$lat, lng=$lng（精度≈${acc}m, ${loc.provider}, $time）\n" +
                "💡 可用高德地图工具逆地理编码获取详细地址（maps_regeocode）或做周边搜索"
    }
}
