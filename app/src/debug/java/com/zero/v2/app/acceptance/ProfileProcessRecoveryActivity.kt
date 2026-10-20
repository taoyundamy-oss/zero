package com.zero.v2.app.acceptance

import android.content.Context
import android.content.ContextWrapper
import android.os.Bundle
import com.zero.v2.core.trace.AgentTraceManager
import com.zero.v2.services.llm.LlmProfile
import com.zero.v2.services.llm.LlmProfileStore
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import org.json.JSONObject

@EntryPoint
@InstallIn(SingletonComponent::class)
interface ProfileRecoveryEntryPoint {
    fun traces(): AgentTraceManager
}

/** Debug-only shell/DUMP fixture. Never writes personal preferences or credentials. */
class ProfileProcessRecoveryActivity : VisualAcceptanceActivity() {
    private lateinit var nonce: String
    private lateinit var prefName: String
    override fun onCreate(savedInstanceState: Bundle?) {
        nonce = requireNotNull(intent.getStringExtra("profileRecoveryNonce"))
        require(nonce.matches(Regex("[a-zA-Z0-9_]{1,64}")))
        prefName = "a8_s3_profile_recovery_$nonce"
        intent.putExtra("acceptancePage", "profile-editor").putExtra("acceptanceWidth", 1200)
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            val scoped = object : ContextWrapper(this) {
                override fun getSharedPreferences(name: String, mode: Int) =
                    super.getSharedPreferences(if (name == "zero_llm_profiles") prefName else name, mode)
            }
            val entry = EntryPointAccessors.fromApplication(this, ProfileRecoveryEntryPoint::class.java)
            LlmProfileStore(scoped, entry.traces()).upsert(
                LlmProfile.localQwen("http://127.0.0.1:8080/v1", "a8_recovery_model").copy(
                    id = "a8_s3_recovery_$nonce", displayName = "独立配置恢复", enabled = false,
                    temperature = .63f, maxTokens = 512, contextWindow = 6144, priority = 11,
                    apiKeyRef = "a8:unconfigured:$nonce", voice = "preserved-inactive-voice"))
        }
    }

    override fun onStop() {
        super.onStop()
        val report = java.io.File(filesDir, "a8-s3-process-recovery.json")
        val disk = java.io.File(applicationInfo.dataDir, "shared_prefs/$prefName.xml")
        val persisted = disk.exists() && disk.readText().contains("a8_s3_recovery_$nonce")
        // Restored activity must not overwrite the original process checkpoint.
        if (!report.exists() || JSONObject(report.readText()).optString("nonce") != nonce) {
            report.writeText(JSONObject().put("nonce", nonce).put("phase", "ready-for-host-kill")
                .put("pid", android.os.Process.myPid()).put("uid", android.os.Process.myUid())
                .put("taskId", taskId).put("isolatedPreferenceFile", prefName)
                .put("profilePersisted", persisted).put("activityStopped", true).toString(2))
        }
    }
}
