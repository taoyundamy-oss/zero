package com.zero.v2.services.mcp

import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager
import okhttp3.OkHttpClient

/**
 * zero-cloud 自签证书信任 + 指纹固定（P-ICP · C0.2 起源，C3.3 抽公共；S7.2 参数化）。
 *
 * 大陆 ECS 无法用域名+CA（SNI 级 ICP 拦截）→ IP 直连 + 自签；指纹 pin 防中间人，
 * 强度不低于 CA 域名验证。
 *
 * S7.2（D120/SR8）：常量态 → 可配置态——[TrustConfig] 依据（内置默认 ∪ SecureKeyStore
 * 全局新指纹 ∪ McpServerStore 条目指纹）调 [applyPins]；**双指纹过渡**（旧默认证书与新
 * 证书并存于接受集）支持换服务器期间新旧实例同时可信。换服务器 = 设置页改 MCP 条目
 * URL+指纹，零代码（HOST 为动态 getter，消费方 URL 拼接自动跟随 primary）。
 *
 * 消费方：McpTransport（MCP 通道）、WebFetchModule.download_file/upload_file（文件通道）、
 * TriggerHub sseClient（事件通道）——凡访问 pinned 主机的 OkHttp 客户端都须经 [install]。
 */
object ZeroCloudTrust {

    /** 服务器内置默认（迁移前现役实例） */
    const val DEFAULT_HOST = "203.0.113.10"

    /** 现役 nginx 自签证书 SHA-256（迁移期保留于接受集——双指纹过渡的"旧"端） */
    internal const val DEFAULT_CERT_SHA256 =
        "0000000000000000000000000000000000000000000000000000000000000000"

    /** pinned 主机集与证书接受集（TrustConfig 刷新；缺省 = 内置单服务器态） */
    @Volatile private var pinnedHosts: Set<String> = setOf(DEFAULT_HOST)
    @Volatile private var acceptedCerts: Set<String> = setOf(DEFAULT_CERT_SHA256)
    @Volatile private var primaryHost: String = DEFAULT_HOST

    /** 当前主实例主机（= zero-cloud MCP 条目 URL host，缺省内置）——URL 拼接消费方用 */
    val HOST: String get() = primaryHost

    /** URL 是否指向 pinned 主机（zero-cloud 族 IP 直连自签场景）——其余主机走系统 CA */
    fun isPinned(url: String): Boolean = try {
        java.net.URI(url).host in pinnedHosts
    } catch (_: Exception) {
        false
    }

    /** TrustConfig 全量刷新（hosts=pin 主机集；certs=接受证书集；primary=主实例主机） */
    fun applyPins(hosts: Set<String>, certs: Set<String>, primary: String) {
        pinnedHosts = hosts
        acceptedCerts = certs.map { it.trim().uppercase() }.toSet()
        primaryHost = primary
    }

    /** 自签信任 + 指纹固定：证书 SHA-256 命中接受集（双指纹并存）才放行，不匹配即断连 */
    fun install(builder: OkHttpClient.Builder): OkHttpClient.Builder = builder.apply {
        val trustManager = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                val cert = chain.firstOrNull() ?: throw CertificateException("无服务器证书")
                val sha256 = MessageDigest.getInstance("SHA-256")
                    .digest(cert.encoded).joinToString("") { "%02X".format(it) }
                if (sha256 !in acceptedCerts) {
                    throw CertificateException("zero-cloud 证书指纹不匹配（已接受 ${acceptedCerts.size} 枚——服务器换证需在设置更新指纹，或存在中间人）")
                }
            }
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, arrayOf(trustManager), null)
        sslSocketFactory(sslContext.socketFactory, trustManager)
        // 指纹 pin 即身份校验（证书 SAN 已含 IP），主机名校验交由 pin 承担
        hostnameVerifier { _, _ -> true }
    }
}
