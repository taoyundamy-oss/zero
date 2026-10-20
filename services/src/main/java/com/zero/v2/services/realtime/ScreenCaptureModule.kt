package com.zero.v2.services.realtime

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.util.Base64
import android.view.Surface
import com.zero.v2.core.common.ActionPermission
import com.zero.v2.core.common.FunctionDefinition
import com.zero.v2.core.common.FunctionResult
import com.zero.v2.core.moduleapi.ModuleCategory
import com.zero.v2.core.moduleapi.ModuleLifecycle
import com.zero.v2.core.moduleapi.ModuleMetadata
import com.zero.v2.core.moduleapi.IZeroModule
import com.zero.v2.core.moduleapi.ModuleConfigBase
import com.zero.v2.core.trace.AgentTraceManager
import com.zero.v2.core.trace.NodeType
import com.zero.v2.services.module.VisionModule
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 屏幕截图分析模块 — Step 6.6（D34 副通道：无障碍文本不可用/用户要求"看页面"时按需截图走 VL）。
 *
 * 授权流（R1 严格时序）：LiveSessionScreen「屏幕共享」→ ProjectionConsentActivity 系统弹窗 →
 * LiveSessionService 先升级双类型 FGS → getMediaProjection() → attachProjection()。
 * 投影仅镜像到 ImageReader 表面（不持续投屏），FC 调用时单帧抓取 + VisionModule VL 分析。
 * 每会话重新授权（R6：token 不跨会话持久）；结束共享/服务销毁即全量释放。
 */
@Singleton
class ScreenCaptureModule @Inject constructor(
    @ApplicationContext private val context: Context,

    private val visionModule: VisionModule,
    private val traceManager: AgentTraceManager
) : IZeroModule, com.zero.v2.core.moduleapi.ToolContributor {

    companion object {
        private const val TRACE_ID = "screen_capture"
        /** 首帧等待上限（VirtualDisplay 建立后系统需 ~100-500ms 才合成第一帧） */
        private const val FIRST_FRAME_TIMEOUT_MS = 1500L
        /** VL 输入长边上限（控制 base64 体积与延迟） */
        private const val MAX_EDGE_PX = 1280
        private const val JPEG_QUALITY = 72
    }

    override val metadata = ModuleMetadata(
        id = "screen_capture",
        displayName = "屏幕截图分析",
        description = "按需截取当前屏幕并调用视觉模型分析（D34 副通道，需 Live 内授权屏幕共享）",
        category = ModuleCategory.INSTRUMENT
    )

    private val _lifecycle = MutableStateFlow(ModuleLifecycle.UNINITIALIZED)
    override val lifecycle = _lifecycle.asStateFlow()
    override var isEnabled: Boolean = true
    override val requiredPermissions: List<String> = emptyList()
    override val config: ModuleConfigBase = object : ModuleConfigBase() {
        override fun toJson() = "{}"
        override fun fromJson(json: String) {}
        override fun defaultConfig() = this
    }

    // Service 线程写 / FC IO 协程读——跨线程可见
    @Volatile private var projection: MediaProjection? = null
    @Volatile private var imageReader: ImageReader? = null
    @Volatile private var virtualDisplay: android.hardware.display.VirtualDisplay? = null
    private var projectionCallback: MediaProjection.Callback? = null

    private val _sharing = MutableStateFlow(false)
    /** 屏幕共享是否活跃（UI 状态展示用） */
    val sharing = _sharing.asStateFlow()

    override suspend fun onAwake() {
        _lifecycle.value = ModuleLifecycle.AWAKE

    }
    override suspend fun onStart() { _lifecycle.value = ModuleLifecycle.STARTED }
    override suspend fun onDestroy() {
        _lifecycle.value = ModuleLifecycle.DESTROYED
        release()
    }
    override suspend fun onConfigChanged(newConfig: ModuleConfigBase) {}
    override suspend fun checkAvailability(): Boolean = true
    override fun markDisabled() { _lifecycle.value = ModuleLifecycle.DISABLED }

    override fun contributions(): List<com.zero.v2.core.moduleapi.ToolContribution> =
        com.zero.v2.core.moduleapi.toolDeclarations(metadata.id) {
        val functionRegistry = this
        // CONFIRM：截屏属敏感操作，每次调用需用户确认（D9/D24 统一权限）
        functionRegistry.register(
            FunctionDefinition(
                name = "capture_screen_analyze",
                description = "截取当前屏幕并用视觉模型分析画面。适用于：无障碍文本不可用的页面" +
                        "（游戏/图片/视频/Canvas）、用户说\"看一下这个页面\"等场景。需要用户已在 Live 界面授权屏幕共享。",
                parameters = mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "question" to mapOf("type" to "string", "description" to "关于画面的分析问题，如：这个页面上有什么内容")
                    ),
                    "required" to listOf("question")
                )
            ),
            metadata.id, permission = ActionPermission.CONFIRM
        ) { args ->
            val question = args["question"] as? String ?: "描述这个页面的内容"
            captureAndAnalyze(question)
        }
    }

    // ═══ 投影生命周期（由 LiveSessionService 调用）═══

    /** 持有投影并建立镜像表面（R1：调用前 Service 已升级双类型 FGS）。
     *  @param onSystemStop 系统侧终止投影时回调（Service 需立即降级 FGS——API 34+ 持
     *  mediaProjection 类型而无活跃投影，系统会强制杀死服务，整个 Live 会话随之死亡） */
    fun attachProjection(p: MediaProjection, width: Int, height: Int, dpi: Int,
                         onSystemStop: (() -> Unit)? = null) {
        release()  // 防御：重复授权时先清旧
        try {
            val callback = object : MediaProjection.Callback() {
                override fun onStop() {
                    // 系统侧终止（状态栏停止投射/新授权顶替旧投影）→ 全量释放 + 通知降级 FGS
                    traceManager.record(TRACE_ID, NodeType.SYSTEM_EVENT, metadata.id,
                        "projection_stopped_by_system → 释放")
                    release()
                    onSystemStop?.invoke()
                }
            }
            projectionCallback = callback
            p.registerCallback(callback, null)
            projection = p
            imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
            virtualDisplay = p.createVirtualDisplay(
                "zero_screen_capture", width, height, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader!!.surface, null, null)
            _sharing.value = true
            traceManager.record(TRACE_ID, NodeType.SYSTEM_EVENT, metadata.id,
                "projection_attached: ${width}x${height} single-frame on-demand")
        } catch (t: Throwable) {
            traceManager.record(TRACE_ID, NodeType.ERROR, metadata.id,
                "projection_attach_failed: ${t.javaClass.simpleName} ${t.message?.take(50)}")
            release()
        }
    }

    fun isSharingActive(): Boolean = projection != null

    /** 结束共享：全量释放（每会话重新授权，token 不持久）。
     *  先注销回调——stop() 会触发 onStop，若在 onDestroy 路径将向已销毁服务发幽灵 downgrade */
    fun release() {
        _sharing.value = false
        try { virtualDisplay?.release() } catch (_: Exception) {}
        virtualDisplay = null
        try { imageReader?.close() } catch (_: Exception) {}
        imageReader = null
        projectionCallback?.let { cb -> try { projection?.unregisterCallback(cb) } catch (_: Exception) {} }
        projectionCallback = null
        try { projection?.stop() } catch (_: Exception) {}
        projection = null
    }

    // ═══ 单帧抓取 + VL ═══

    private suspend fun captureAndAnalyze(question: String): FunctionResult = withContext(Dispatchers.IO) {
        if (projection == null) {
            traceManager.record(TRACE_ID, NodeType.ERROR, metadata.id,
                "capture_rejected: 无活跃投影（引导重新授权）")
            return@withContext FunctionResult.failure(
                "屏幕共享未开启。请让用户在 Live 界面点击「屏幕共享」按钮完成系统授权后重试。")
        }
        val dataUrl = captureScreenJpeg()
            ?: return@withContext FunctionResult.failure("截图失败（画面尚未就绪或共享已中断），请稍后重试或重新授权。")
        traceManager.record(TRACE_ID, NodeType.SYSTEM_EVENT, metadata.id,
            "capture_ok: base64=${dataUrl.length / 1024}KB question=${question.take(30)}")
        val answer = try {
            visionModule.analyzePoke(dataUrl, question)
        } catch (t: java.io.IOException) {
            FunctionResult.failure(t.message ?: "vision_request_failed")
        } catch (t: org.json.JSONException) {
            FunctionResult.failure("vision_response_invalid: ${t.message?.take(50)}")
        }
        if (answer.success) {
            traceManager.record(TRACE_ID, NodeType.SYSTEM_EVENT, metadata.id,
                "analyze_ok: len=${answer.data.length}")
        }
        answer
    }

    /** 单帧抓取 → 缩放 → JPEG → data URL（base64） */
    private suspend fun captureScreenJpeg(): String? {
        val reader = imageReader ?: return null
        // 首帧等待：镜像建立后系统需时间合成
        var image = reader.acquireLatestImage()
        var waited = 0L
        while (image == null && waited < FIRST_FRAME_TIMEOUT_MS) {
            delay(50); waited += 50
            image = reader.acquireLatestImage()
        }
        if (image == null) {
            traceManager.record(TRACE_ID, NodeType.ERROR, metadata.id, "capture_timeout: 首帧超时")
            return null
        }
        return try {
            val plane = image.planes[0]
            val buffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowPadding = plane.rowStride - pixelStride * image.width
            val full = Bitmap.createBitmap(
                image.width + rowPadding / pixelStride, image.height, Bitmap.Config.ARGB_8888)
            full.copyPixelsFromBuffer(buffer)
            val src = if (rowPadding > 0)
                Bitmap.createBitmap(full, 0, 0, image.width, image.height) else full
            // 长边缩放（控 VL 输入体积与延迟）
            val scale = MAX_EDGE_PX.toFloat() / maxOf(src.width, src.height)
            val bmp = if (scale < 1f) Bitmap.createScaledBitmap(
                src, (src.width * scale).toInt(), (src.height * scale).toInt(), true) else src
            val bytes = ByteArrayOutputStream().use { out ->
                bmp.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
                out.toByteArray()
            }
            "data:image/jpeg;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
        } catch (t: Throwable) {
            traceManager.record(TRACE_ID, NodeType.ERROR, metadata.id,
                "capture_failed: ${t.javaClass.simpleName} ${t.message?.take(40)}")
            null
        } finally {
            try { image.close() } catch (_: Exception) {}
        }
    }
}
