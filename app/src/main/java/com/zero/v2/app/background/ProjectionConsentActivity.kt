package com.zero.v2.app.background

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.zero.v2.core.trace.AgentTraceManager
import com.zero.v2.core.trace.NodeType
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * 屏幕共享授权页 — Step 6.6（R1/R6：MediaProjection 系统弹窗必经 Activity）。
 *
 * 透明 Activity：仅拉起系统授权弹窗，结果转发 LiveSessionService 处理——
 * R1 严格时序要求 getMediaProjection() 前服务已含 MEDIA_PROJECTION FGS 类型，
 * 故本页不做投影创建，只透传 resultCode/data。每会话重新授权（token 不持久）。
 */
@AndroidEntryPoint
class ProjectionConsentActivity : ComponentActivity() {

    @Inject lateinit var traceManager: AgentTraceManager

    private val consentLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            traceManager.record("screen_capture", NodeType.USER_INPUT, "screen_capture",
                "consent_granted → 转发 Service（R1: 先升级双类型 FGS）")
            startService(Intent(this, LiveSessionService::class.java)
                .setAction(LiveSessionService.ACTION_ATTACH_PROJECTION)
                .putExtra(LiveSessionService.EXTRA_RESULT_CODE, result.resultCode)
                .putExtra(LiveSessionService.EXTRA_RESULT_DATA, result.data))
        } else {
            traceManager.record("screen_capture", NodeType.SYSTEM_EVENT, "screen_capture",
                "consent_denied（用户拒绝或取消）")
        }
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        if (savedInstanceState == null) consentLauncher.launch(mpm.createScreenCaptureIntent())
    }
}
