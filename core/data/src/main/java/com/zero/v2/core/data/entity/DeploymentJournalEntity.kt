package com.zero.v2.core.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 动态模块部署 journal（E1 / 设计§5）——部署状态机持久化 + 进程恢复续跑。
 *
 * 设计§5："activation 前写预提交 journal；探活成功后原子切活动记录并发布目录 revision；
 * Android Room 只镜像该部署结果，重启从 supervisor 对账"。
 *
 * 状态机（[com.zero.v2.core.common.ReleaseState]）：PROPOSED→STAGED→VALIDATING→READY→CANARY→ACTIVE；
 * 失败→FAILED；灰度失败→ROLLING_BACK→ROLLED_BACK。进程恢复从 journal 续跑，不重新执行已完成副作用。
 *
 * **活动指针**：`state == "ACTIVE"` 即该模块当前活动版本（每模块至多一条 ACTIVE——
 * [DeploymentJournalDao.activateRelease] 先 demote 旧 ACTIVE→ROLLED_BACK 再写新 ACTIVE）。
 *
 * 幂等：同 deploymentId 不重复创建（PK IGNORE）；状态迁移用 updateById。
 */
@Entity(
    tableName = "deployment_journal",
    indices = [
        Index(value = ["module_id"]),
        Index(value = ["content_hash"]),
        Index(value = ["module_id", "state"]),
    ],
)
data class DeploymentJournalEntity(
    @PrimaryKey @ColumnInfo(name = "deployment_id") val deploymentId: String,
    @ColumnInfo(name = "module_id") val moduleId: String,
    /** 源码+manifest 的 contentHash（[com.zero.v2.core.common.contentHash]）——发布目录锚点。 */
    @ColumnInfo(name = "content_hash") val contentHash: String,
    /** manifest 确定性序列化（[com.zero.v2.core.common.canonicalString]）——留档+对账。 */
    @ColumnInfo(name = "manifest") val manifest: String,
    /** [com.zero.v2.core.common.ReleaseState] name。 */
    @ColumnInfo(name = "state") val state: String,
    /** 候选源码字节数（prepare 时记录，校验门用）。 */
    @ColumnInfo(name = "code_size") val codeSize: Int,
    /** 校验/部署尝试次数（失败重试递增，E3 灰度限次）。 */
    @ColumnInfo(name = "attempts", defaultValue = "0") val attempts: Int = 0,
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "updated_at") val updatedAt: Long = System.currentTimeMillis(),
    /** 最近错误（FAILED 时填，供 LLM 修复回路读真实失败报告——设计§5/§E）。 */
    @ColumnInfo(name = "last_error", defaultValue = "") val lastError: String = "",
)
