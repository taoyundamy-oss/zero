package com.zero.v2.core.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import com.zero.v2.core.data.dao.AgentTraceEventDao
import com.zero.v2.core.data.dao.AutomationDao
import com.zero.v2.core.data.dao.CharacterDao
import com.zero.v2.core.data.dao.ConversationDao
import com.zero.v2.core.data.dao.DynamicModuleDao
import com.zero.v2.core.data.dao.KbDao
import com.zero.v2.core.data.dao.MemoryDao
import com.zero.v2.core.data.dao.MessageDao
import com.zero.v2.core.data.dao.MusicDao
import com.zero.v2.core.data.dao.SystemStateDao
import com.zero.v2.core.data.dao.UserGraphDao
import com.zero.v2.core.data.entity.CharacterRelationState
import com.zero.v2.core.data.entity.CharacterStateEvent
import com.zero.v2.core.data.entity.ConversationMoodState
import com.zero.v2.core.data.entity.DeploymentJournalEntity
import com.zero.v2.core.data.entity.MemoryExtractionJobEntity
import com.zero.v2.core.data.entity.AgentTraceEventEntity
import com.zero.v2.core.data.entity.AutomationEntity
import com.zero.v2.core.data.entity.CharacterEntity
import com.zero.v2.core.data.entity.ConversationEntity
import com.zero.v2.core.data.entity.DynamicModuleEntity
import com.zero.v2.core.data.entity.KbChunkEntity
import com.zero.v2.core.data.entity.KbDocEntity
import com.zero.v2.core.data.entity.MemoryEntity
import com.zero.v2.core.data.entity.MessageEntity
import com.zero.v2.core.data.entity.ModuleConfigEntity
import com.zero.v2.core.data.entity.MusicTrackEntity
import com.zero.v2.core.data.entity.PlaylistEntity
import com.zero.v2.core.data.entity.PlaylistItemEntity
import com.zero.v2.core.data.entity.ScheduledPlaybackEntity
import com.zero.v2.core.data.entity.NotepadEntity
import com.zero.v2.core.data.entity.ReminderEntity
import com.zero.v2.core.data.entity.UserGraphAttribute
import com.zero.v2.core.data.entity.UserGraphEntity
import com.zero.v2.core.data.entity.UserGraphRelationship
import com.zero.v2.core.data.store.ModuleConfigDao

@Database(
    entities = [
        com.zero.v2.core.data.entity.MemoryDerivationEntity::class,
        com.zero.v2.core.data.entity.MemoryDerivationViewEntity::class,
        com.zero.v2.core.data.entity.ChronicleSourceEntity::class,
        com.zero.v2.core.data.entity.ChronicleDeletionEntity::class,
        com.zero.v2.core.data.entity.UsageRequestEntity::class,
        MemoryEntity::class,
        com.zero.v2.core.data.entity.MemoryReviewEntity::class,
        com.zero.v2.core.data.entity.MemoryConflictEntity::class,
        com.zero.v2.core.data.entity.MemorySourceEntity::class,
        com.zero.v2.core.data.entity.MemoryDeletionEntity::class,
        ModuleConfigEntity::class,
        ConversationEntity::class,
        MessageEntity::class,
        CharacterEntity::class,
        AutomationEntity::class,
        AgentTraceEventEntity::class,
        UserGraphEntity::class,
        UserGraphAttribute::class,
        UserGraphRelationship::class,
        NotepadEntity::class,
        ReminderEntity::class,
        MusicTrackEntity::class,
        PlaylistEntity::class,
        PlaylistItemEntity::class,
        ScheduledPlaybackEntity::class,
        KbDocEntity::class,
        KbChunkEntity::class,
        DynamicModuleEntity::class,
        MemoryExtractionJobEntity::class,
        CharacterRelationState::class,
        ConversationMoodState::class,
        CharacterStateEvent::class,
        DeploymentJournalEntity::class,
        com.zero.v2.core.data.entity.VectorVersionEntity::class,
        com.zero.v2.core.data.entity.VectorEntryEntity::class,
        com.zero.v2.core.data.entity.VectorActiveEntity::class,
        com.zero.v2.core.data.entity.VectorValidationEntity::class
    ],
    version = 38,  // P5 C4：来源版本绑定、派生队列与可重建视图
    exportSchema = true
)
abstract class ZeroDatabase : RoomDatabase() {
    abstract fun memoryDerivationDao(): com.zero.v2.core.data.dao.MemoryDerivationDao
    abstract fun chronicleSourceDao(): com.zero.v2.core.data.dao.ChronicleSourceDao
    abstract fun usageRequestDao(): com.zero.v2.core.data.dao.UsageRequestDao
    abstract fun vectorIndexDao(): com.zero.v2.core.data.dao.VectorIndexDao
    abstract fun memoryReviewDao(): com.zero.v2.core.data.dao.MemoryReviewDao
    abstract fun memoryDao(): MemoryDao
    abstract fun moduleConfigDao(): ModuleConfigDao
    abstract fun conversationDao(): ConversationDao
    abstract fun messageDao(): MessageDao
    abstract fun characterDao(): CharacterDao
    abstract fun automationDao(): AutomationDao
    abstract fun agentTraceEventDao(): AgentTraceEventDao
    abstract fun userGraphDao(): UserGraphDao
    abstract fun systemStateDao(): SystemStateDao
    abstract fun musicDao(): MusicDao
    abstract fun kbDao(): KbDao
    abstract fun dynamicModuleDao(): DynamicModuleDao
    abstract fun memoryExtractionJobDao(): com.zero.v2.core.data.dao.MemoryExtractionJobDao
    /** P1：角色状态（亲密度/情绪/事件） */
    abstract fun characterStateDao(): com.zero.v2.core.data.dao.CharacterStateDao
    /** E1：部署 journal（受控自迭代状态机 + 进程恢复续跑） */
    abstract fun deploymentJournalDao(): com.zero.v2.core.data.dao.DeploymentJournalDao
}
