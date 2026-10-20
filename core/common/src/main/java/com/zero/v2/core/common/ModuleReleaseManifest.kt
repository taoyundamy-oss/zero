package com.zero.v2.core.common

import java.security.MessageDigest

/**
 * 动态模块发布 manifest（E1 / 设计§5：版本化 + 固定字段 + contentHash）。
 *
 * 设计§5 固定字段：sdkRange / entrypoint / capabilities / dependencies（锁版本+hash）/ inputSchema /
 * outputSchema / healthContract / resourceLimits / dataSchemaVersion。
 *
 * manifest 与源码共同决定 [contentHash]——同一代码+manifest 产出同一 hash（确定性），
 * 供 `modules/<id>/releases/<contentHash>/` 只读发布目录 + 幂等发布 + 回滚锚点。
 *
 * 纯数据 + 纯逻辑（无 Android 依赖）→ JVM 可单测。
 */
data class ModuleReleaseManifest(
    /** SDK 兼容范围（如 ">=1.0,<2.0"）——supervisor 版本兼容门。 */
    val sdkRange: String = ">=1.0",
    /** 入口符号（模块内 serve() 或等价可调用对象）。 */
    val entrypoint: String = "serve",
    /** 能力声明（模块自报能做什么——展示用，非授权）。 */
    val capabilities: List<String> = emptyList(),
    /** pip 依赖（锁版本，如 "requests==2.31.0"）——每版本环境安装，不动全局 pip。 */
    val dependencies: List<String> = emptyList(),
    /** termux pkg 依赖（bootstrap 域）。 */
    val termuxDeps: List<String> = emptyList(),
    /** 输入 schema（JSON 字符串——契约校验用）。 */
    val inputSchema: String = "{}",
    /** 输出 schema（JSON 字符串——契约校验用）。 */
    val outputSchema: String = "{}",
    /** 健康契约（探活策略：endpoint 窗口/超时——supervisor 探活消费）。 */
    val healthContract: String = "endpoint_window_15s",
    /** 资源限额（调用数/时长/内存——E3 灰度消费）。 */
    val resourceLimits: String = "{}",
    /** 数据 schema 版本（模块私有数据迁移用——不可逆迁移须提案）。 */
    val dataSchemaVersion: Int = 1,
    /** 传输：http（主）| socket（AF_UNIX 兜底）。 */
    val transport: String = "http",
)

/**
 * 发布 contentHash——SHA-256(code \u0001 manifestCanonical)，hex。
 *
 * code 与 manifest 共同决定 hash：同 code+manifest → 同 hash（确定性，幂等发布）。
 * manifest 字段顺序固定（data class 序列化确定性），重排不影响 hash。
 */
fun contentHash(code: String, manifest: ModuleReleaseManifest): String {
    val md = MessageDigest.getInstance("SHA-256")
    val canonical = manifest.canonicalString()
    val input = "$code\u0001$canonical"
    return md.digest(input.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}

/** manifest 确定性序列化（字段顺序固定——供 hash 稳定 + journal 留档）。 */
fun ModuleReleaseManifest.canonicalString(): String = buildString {
    append("sdkRange=").append(sdkRange).append('\n')
    append("entrypoint=").append(entrypoint).append('\n')
    append("capabilities=").append(capabilities.sorted().joinToString(",")).append('\n')
    append("dependencies=").append(dependencies.sorted().joinToString(",")).append('\n')
    append("termuxDeps=").append(termuxDeps.sorted().joinToString(",")).append('\n')
    append("inputSchema=").append(inputSchema).append('\n')
    append("outputSchema=").append(outputSchema).append('\n')
    append("healthContract=").append(healthContract).append('\n')
    append("resourceLimits=").append(resourceLimits).append('\n')
    append("dataSchemaVersion=").append(dataSchemaVersion).append('\n')
    append("transport=").append(transport)
}
