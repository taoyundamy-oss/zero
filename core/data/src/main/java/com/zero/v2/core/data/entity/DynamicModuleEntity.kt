package com.zero.v2.core.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import org.json.JSONArray

/**
 * 动态模块（Termux/Debian 热插拔）注册表 —— M2.1（方案 §5.2 D67/D68 简化版 + P0-4/P1-5）。
 *
 * - **perm 恒 AUTO、无 capability 维度**（D67 废止双维放权——origin 仅作展示标签）
 * - **lastEndpoint 仅调试信息（P0-4 封板）**：真实连接地址一律以 supervisor 启动回报
 *   （ZERO_MOD_LISTENING/抽象名）为准；D51 恢复=supervisor 重启→重新发现→更新→mount
 * - **installedDeps 归属记录（P1-5）**：依赖装在 Debian 全局（D67 不隔离）——记录供未来
 *   治理参考，v1 不治理
 * - 状态转换（P1-6）：CRASHED --用户 enable--> DISABLED --自动 PROBED--> ACTIVE；
 *   探活失败回 DISABLED（CRASHED 特指运行中崩）
 */
@Entity(tableName = "dynamic_modules", indices = [Index("state")])
data class DynamicModuleEntity(
    @PrimaryKey val id: String,
    /** 来源：HANDWRITTEN（手写受信）| GENERATED（LLM 自编译） */
    @ColumnInfo(name = "origin", defaultValue = "HANDWRITTEN") val origin: String = "HANDWRITTEN",
    /** 模块源码（.py 全文；真身部署于 Debian /root/zero/modules/<id>.py） */
    @ColumnInfo(name = "source") val source: String,
    /** manifest 快照 JSON（deps/termuxDeps/runtime 解析结果留档） */
    @ColumnInfo(name = "schema", defaultValue = "{}") val schema: String = "{}",
    /** 传输：http（主，D59）| socket（AF_UNIX 兜底） */
    @ColumnInfo(name = "transport", defaultValue = "http") val transport: String = "http",
    /** 上次运行端点（仅调试）——module start 后由 supervisor runtime 回报刷新 */
    @ColumnInfo(name = "last_endpoint", defaultValue = "") val lastEndpoint: String = "",
    /** pip 依赖清单 JSON 数组（manifest 声明；非空触发安装，installTimeoutS 计时） */
    @ColumnInfo(name = "deps", defaultValue = "[]") val deps: String = "[]",
    /** 实际装过的 pip 包 JSON 数组（P1-5 归属记录） */
    @ColumnInfo(name = "installed_deps", defaultValue = "[]") val installedDeps: String = "[]",
    /** pkg 依赖 JSON 数组（bootstrap 装机时消费） */
    @ColumnInfo(name = "termux_deps", defaultValue = "[]") val termuxDeps: String = "[]",
    /** 运行时：python（默认）| node（预留） */
    @ColumnInfo(name = "runtime", defaultValue = "python") val runtime: String = "python",
    /** 配置声明 JSON（D68 configSchema——Zero 侧模块配置页据此动态渲染三型控件） */
    @ColumnInfo(name = "config_schema", defaultValue = "[]") val configSchema: String = "[]",
    /** ACTIVE（挂载中）| DISABLED（停用/未挂载）| CRASHED（运行中崩） */
    @ColumnInfo(name = "state", defaultValue = "DISABLED") val state: String = "DISABLED",
    @ColumnInfo(name = "created_at") val createdAt: Long,
    /** 最近一次崩溃时间（0=从未；跨会话崩数计 K 判据的数据源之一） */
    @ColumnInfo(name = "last_crash_at", defaultValue = "0") val lastCrashAt: Long = 0,
    /**
     * 跨会话崩溃计数（M4.1 · A-II-3 全局层）：超 crashAutoDisableK（§9.1 TermuxBridge 域）
     * → auto-DISABLE + 权限中心告警。清零规则：仅告警后用户再启用时（防 enable-crash 循环洗计数；
     * 低于 K 不清——跨会话累计语义）。
     */
    @ColumnInfo(name = "crash_count", defaultValue = "0") val crashCount: Int = 0,
) {
    fun depsList(): List<String> = parseJsonList(deps)
    fun installedDepsList(): List<String> = parseJsonList(installedDeps)
    fun termuxDepsList(): List<String> = parseJsonList(termuxDeps)

    companion object {
        fun parseJsonList(json: String): List<String> = try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { arr.optString(it) }
        } catch (_: Exception) { emptyList() }

        fun toJsonList(list: List<String>): String = JSONArray(list).toString()
    }
}
