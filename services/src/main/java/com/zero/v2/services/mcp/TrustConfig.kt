package com.zero.v2.services.mcp

import com.zero.v2.core.trace.AgentTraceManager
import com.zero.v2.core.trace.NodeType
import com.zero.v2.services.security.CredentialStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 信任配置 — S7.2（D120/SR8）：ZeroCloudTrust 常量参数化的单一来源。
 *
 * pin 集 = 内置默认（旧实例——双指纹过渡的"旧"端）
 *        ∪ SecureKeyStore 全局新指纹（[EXTRA_CERT_KEY]，手工/备份写入通道）
 *        ∪ McpServerStore 各条目（URL host + certSha256 字段）
 * primary 主机 = 名称 "zero-cloud"（D103 固定）的启用条目 URL host，缺省内置。
 *
 * 自刷新：collect serverStore.servers——存量载入与任何增删改（UI/对话添加）即时生效；
 * 启动实例化由 McpClientModule.onAwake 注入保证（见 S7.2-b 接线）。
 * 换服务器流程：MCP 设置把 zero-cloud 条目改为新 URL+新指纹 → 全链（MCP/下载/上传/SSE）
 * 的 pin 与 URL 自动跟随（消费方 HOST 为动态 getter）。
 */
@Singleton
class TrustConfig
    @Inject
    constructor(
        private val serverStore: McpServerStore,
        private val secureKeyStore: CredentialStore,
        private val traceManager: AgentTraceManager,
    ) {
        companion object {
            /** 迁移期全局新证书指纹（64 位 hex；MCP 条目指纹为主入口，此为备份通道） */
            const val EXTRA_CERT_KEY = "zero_cloud:cert_sha256_extra"
        }

        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        init {
            scope.launch {
                serverStore.servers.collect { refresh() }
            }
        }

        /** 全量重算并应用 pins（幂等；任何调用方可手动触发） */
        fun refresh() {
            val hosts = mutableSetOf(ZeroCloudTrust.DEFAULT_HOST)
            val certs = mutableSetOf(ZeroCloudTrust.DEFAULT_CERT_SHA256)
            runCatching { secureKeyStore.getString(EXTRA_CERT_KEY) }.getOrNull()?.trim()?.uppercase()
                ?.takeIf { it.length == 64 }?.let { certs += it }
            var primary = ZeroCloudTrust.DEFAULT_HOST
            serverStore.servers.value.forEach { s ->
                val host = runCatching { java.net.URI(s.url).host }.getOrNull() ?: return@forEach
                val fp = s.certSha256.trim().uppercase()
                if (fp.length == 64) {
                    hosts += host
                    certs += fp
                }
                // D103：名称固定 zero-cloud 的启用条目 = 主实例（事件/上传 URL 跟随其 host）
                if (s.name == "zero-cloud" && s.enabled) primary = host
            }
            ZeroCloudTrust.applyPins(hosts, certs, primary)
            traceManager.record(
                "trust",
                NodeType.SYSTEM_EVENT,
                "mcp_client",
                "pins_refreshed: hosts=${hosts.joinToString(",") { it.take(24) }} " +
                    "certs=${certs.size} primary=$primary",
            )
        }

        /** 生命周期收口：McpClientModule 销毁时取消 store 流监听。 */
        fun close() {
            scope.cancel()
        }
    }
