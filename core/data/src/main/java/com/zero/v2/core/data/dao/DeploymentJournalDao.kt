package com.zero.v2.core.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.zero.v2.core.data.entity.DeploymentJournalEntity

/**
 * 部署 journal DAO（E1 / 设计§5）——状态机持久化 + 进程恢复续跑 + 活动指针原子切换。
 *
 * 幂等：[insertIfNew] IGNORE（同 deploymentId 不重复）；状态经 [updateState] 迁移。
 * 活动指针：[activateRelease] 先 demote 旧 ACTIVE→ROLLED_BACK 再写新 ACTIVE（事务内原子，设计§5"探活成功后原子切活动记录"）。
 */
@Dao
interface DeploymentJournalDao {

    @Query("DELETE FROM deployment_journal WHERE module_id = :moduleId")
    suspend fun clearMirror(moduleId: String)

    @Transaction
    suspend fun replaceMirror(moduleId: String, rows: List<DeploymentJournalEntity>) {
        require(rows.all { it.moduleId == moduleId })
        clearMirror(moduleId)
        rows.forEach { insertIfNew(it) }
    }

    /** 幂等插入（同 deploymentId 冲突 IGNORE）。返回 1=新插入。 */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfNew(entry: DeploymentJournalEntity): Long

    @Query("SELECT * FROM deployment_journal WHERE deployment_id = :deploymentId")
    suspend fun getById(deploymentId: String): DeploymentJournalEntity?

    /** 模块当前活动版本（state=ACTIVE——至多一条；切活动前先 demote 旧 ACTIVE）。 */
    @Query("SELECT * FROM deployment_journal WHERE module_id = :moduleId AND state IN ('ACTIVE','CANARY') LIMIT 1")
    suspend fun getActiveForModule(moduleId: String): DeploymentJournalEntity?

    /** 模块上一健康版本（回滚目标——排除当前失败 hash，取最近 ROLLED_BACK 的可恢复版本）。 */
    @Query(
        """SELECT * FROM deployment_journal
           WHERE module_id = :moduleId AND state = 'ROLLED_BACK' AND content_hash != :excludeHash
           ORDER BY updated_at DESC LIMIT 1"""
    )
    suspend fun getPreviousHealthy(moduleId: String, excludeHash: String): DeploymentJournalEntity?

    /** 模块所有 journal（按时间倒序——部署历史/对账）。 */
    @Query("SELECT * FROM deployment_journal WHERE module_id = :moduleId ORDER BY created_at DESC")
    suspend fun historyForModule(moduleId: String): List<DeploymentJournalEntity>

    /** 中断在途（非终态/非 ACTIVE）的部署——进程恢复时找续跑目标。 */
    @Query(
        """SELECT * FROM deployment_journal
           WHERE module_id = :moduleId AND state NOT IN ('ACTIVE','FAILED','ROLLED_BACK')
           ORDER BY updated_at DESC"""
    )
    suspend fun inFlightForModule(moduleId: String): List<DeploymentJournalEntity>

    /** 状态迁移 + 错误 + attempts 更新（幂等——deploymentId 定位）。 */
    @Query(
        """UPDATE deployment_journal
           SET state = :state, last_error = :error, attempts = :attempts, updated_at = :updatedAt
           WHERE deployment_id = :deploymentId"""
    )
    suspend fun updateState(deploymentId: String, state: String, error: String, attempts: Int, updatedAt: Long)

    /** 切活动指针（事务内）：demote 旧 ACTIVE→ROLLED_BACK，写新 deploymentId→ACTIVE。 */
    @Query("UPDATE deployment_journal SET state = 'ROLLED_BACK', updated_at = :ts WHERE module_id = :moduleId AND state = 'ACTIVE'")
    suspend fun demoteActive(moduleId: String, ts: Long)

    @Query("UPDATE deployment_journal SET state = 'ACTIVE', updated_at = :ts WHERE deployment_id = :deploymentId")
    suspend fun promoteToActive(deploymentId: String, ts: Long)

    /** 按 contentHash 查（幂等发布——同 hash 已有 journal 则复用）。 */
    @Query("SELECT * FROM deployment_journal WHERE module_id = :moduleId AND content_hash = :hash LIMIT 1")
    suspend fun getByHash(moduleId: String, hash: String): DeploymentJournalEntity?
}
