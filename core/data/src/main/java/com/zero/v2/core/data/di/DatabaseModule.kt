package com.zero.v2.core.data.di

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.zero.v2.core.data.dao.AgentTraceEventDao
import com.zero.v2.core.data.dao.AutomationDao
import com.zero.v2.core.data.dao.CharacterDao
import com.zero.v2.core.data.dao.ConversationDao
import com.zero.v2.core.data.dao.MemoryDao
import com.zero.v2.core.data.dao.MessageDao
import com.zero.v2.core.data.dao.UserGraphDao
import com.zero.v2.core.data.db.ZeroDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    private val MIGRATION_5_6 = object : Migration(5, 6) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE characters ADD COLUMN is_active INTEGER NOT NULL DEFAULT 0")
        }
    }

    /** v3.5: automations 表（Plan-Execute 引擎持久化）。显式迁移，避免 fallback 清库 */
    private val MIGRATION_6_7 = object : Migration(6, 7) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `automations` (
                    `id` TEXT NOT NULL,
                    `title` TEXT NOT NULL,
                    `description` TEXT NOT NULL,
                    `planJson` TEXT NOT NULL,
                    `status` TEXT NOT NULL,
                    `scheduleType` TEXT NOT NULL,
                    `scheduleValue` TEXT NOT NULL,
                    `lastRunAt` INTEGER,
                    `nextRunAt` INTEGER NOT NULL,
                    `runCount` INTEGER NOT NULL,
                    `createdAt` INTEGER NOT NULL,
                    PRIMARY KEY(`id`)
                )
                """.trimIndent()
            )
        }
    }

    /** v3.7: agent_trace_events 表（D3: Trace 独立持久化，不入 Message 表） */
    private val MIGRATION_7_8 = object : Migration(7, 8) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `agent_trace_events` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `traceId` TEXT NOT NULL,
                    `sequence` INTEGER NOT NULL,
                    `timestamp` INTEGER NOT NULL,
                    `nodeType` TEXT NOT NULL,
                    `sourceModule` TEXT NOT NULL,
                    `summary` TEXT NOT NULL,
                    `detail` TEXT,
                    `tokensUsed` INTEGER,
                    `durationMs` INTEGER,
                    `parentTraceId` TEXT
                )
                """.trimIndent()
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_agent_trace_events_traceId` ON `agent_trace_events` (`traceId`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_agent_trace_events_timestamp` ON `agent_trace_events` (`timestamp`)")
        }
    }

    /** v4.1: memories 表增强（type/status/confidence/reinforcement_count，Phase 4 演化地基）+ 按 tier 回填 type */
    private val MIGRATION_8_9 = object : Migration(8, 9) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE memories ADD COLUMN `type` TEXT NOT NULL DEFAULT 'EVENT'")
            db.execSQL("ALTER TABLE memories ADD COLUMN `status` TEXT NOT NULL DEFAULT 'ACTIVE'")
            db.execSQL("ALTER TABLE memories ADD COLUMN `confidence` REAL NOT NULL DEFAULT 0.5")
            db.execSQL("ALTER TABLE memories ADD COLUMN `reinforcement_count` INTEGER NOT NULL DEFAULT 0")
            // 存量回填: Tier 2 → FACT，其余 → EVENT
            db.execSQL("UPDATE memories SET type = CASE WHEN tier >= 2 THEN 'FACT' ELSE 'EVENT' END")
        }
    }

    /** v4.10: 用户图谱三表（D16 — 实体/时间区间版本化属性/关系） */
    private val MIGRATION_9_10 = object : Migration(9, 10) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `user_graph_entities` (
                    `id` TEXT NOT NULL,
                    `name` TEXT NOT NULL,
                    `type` TEXT NOT NULL,
                    `createdAt` INTEGER NOT NULL,
                    PRIMARY KEY(`id`)
                )
                """.trimIndent()
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_user_graph_entities_name` ON `user_graph_entities` (`name`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_user_graph_entities_type` ON `user_graph_entities` (`type`)")
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `user_graph_attributes` (
                    `entityId` TEXT NOT NULL,
                    `key` TEXT NOT NULL,
                    `value` TEXT NOT NULL,
                    `source` TEXT NOT NULL,
                    `confidence` REAL NOT NULL,
                    `effectiveFrom` INTEGER NOT NULL,
                    `effectiveTo` INTEGER,
                    `evidence` TEXT,
                    `createdAt` INTEGER NOT NULL,
                    PRIMARY KEY(`entityId`, `key`, `effectiveFrom`)
                )
                """.trimIndent()
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_user_graph_attributes_entityId` ON `user_graph_attributes` (`entityId`)")
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `user_graph_relationships` (
                    `fromEntityId` TEXT NOT NULL,
                    `toEntityId` TEXT NOT NULL,
                    `type` TEXT NOT NULL,
                    `effectiveFrom` INTEGER NOT NULL,
                    `effectiveTo` INTEGER,
                    PRIMARY KEY(`fromEntityId`, `toEntityId`, `type`, `effectiveFrom`)
                )
                """.trimIndent()
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_user_graph_relationships_fromEntityId` ON `user_graph_relationships` (`fromEntityId`)")
        }
    }

    /** v3.4 (D41): automations 增加 execMode（PLAN/REACT） */
    private val MIGRATION_10_11 = object : Migration(10, 11) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE automations ADD COLUMN `execMode` TEXT NOT NULL DEFAULT 'PLAN'")
        }
    }

    /** v6.7: 提醒/记事本持久化（实测 P0：taskQueue/notepad 纯内存态，重启与更新即失） */
    private val MIGRATION_11_12 = object : Migration(11, 12) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `notepad` (
                    `id` INTEGER PRIMARY KEY NOT NULL,
                    `content` TEXT NOT NULL,
                    `updated_at` INTEGER NOT NULL
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `reminders` (
                    `id` TEXT NOT NULL,
                    `remark` TEXT NOT NULL,
                    `trigger_at` INTEGER NOT NULL,
                    `is_recurring` INTEGER NOT NULL,
                    `recurring_ms` INTEGER NOT NULL,
                    PRIMARY KEY(`id`)
                )
                """.trimIndent()
            )
        }
    }

    /** N6.2（设计 §九 CC2）：会话滚动摘要存储——summary / summary_upto（消息 id 水印，X13）
     *  Migration 与 Entity 变更必须同 commit；缺失迁移将中止升级并保留原库；老库升级为 L3 必测项。 */
    private val MIGRATION_12_13 = object : Migration(12, 13) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE conversations ADD COLUMN `summary` TEXT")
            db.execSQL("ALTER TABLE conversations ADD COLUMN `summary_upto` TEXT")
        }
    }

    /** S3.1（收束轨 D118/SR2）：reminders 全生命周期扩列——title/note/status/created_by/repeat_rule。
     *  旧列（remark/trigger_at/is_recurring/recurring_ms）原样保留——既有写入方零改动；
     *  逐列 ALTER（SQLite 不支持同行多列），NOT NULL+DEFAULT 与 @ColumnInfo 逐项对齐
     * （Room schema 校验要求 ALTER 默认值与注解 defaultValue 完全一致）。
     *  与 Entity 变更同 commit；老库升级（12 条提醒全保留）= L3 必测。 */
    private val MIGRATION_13_14 = object : Migration(13, 14) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE reminders ADD COLUMN `title` TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE reminders ADD COLUMN `note` TEXT")
            db.execSQL("ALTER TABLE reminders ADD COLUMN `status` TEXT NOT NULL DEFAULT 'active'")
            db.execSQL("ALTER TABLE reminders ADD COLUMN `created_by` TEXT NOT NULL DEFAULT 'user'")
            db.execSQL("ALTER TABLE reminders ADD COLUMN `repeat_rule` TEXT")
        }
    }

    /** MU1.1（音乐轨 MD13/M8 · DB v15）：music_tracks 表——relative_path 唯一定位键（UNIQUE）+ miss_count。
     *  NOT NULL 列 DEFAULT 与 @ColumnInfo(defaultValue=) 逐项对齐（Room schema 校验）；
     *  added_at 无 DEFAULT（调用方显式传）。
     *  与 Entity/Dao/版本变更同 commit；缺失迁移将中止升级并保留原库；老库升级 = L3 必测。 */
    private val MIGRATION_14_15 = object : Migration(14, 15) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `music_tracks` (
                    `id` TEXT NOT NULL,
                    `relative_path` TEXT NOT NULL,
                    `display_title` TEXT NOT NULL DEFAULT '',
                    `artist` TEXT,
                    `album` TEXT,
                    `duration_ms` INTEGER NOT NULL DEFAULT 0,
                    `file_size` INTEGER NOT NULL DEFAULT 0,
                    `added_at` INTEGER NOT NULL,
                    `modified_at` INTEGER NOT NULL DEFAULT 0,
                    `miss_count` INTEGER NOT NULL DEFAULT 0,
                    `play_count` INTEGER NOT NULL DEFAULT 0,
                    `last_played_at` INTEGER,
                    PRIMARY KEY(`id`)
                )
                """.trimIndent()
            )
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_music_tracks_relative_path` " +
                    "ON `music_tracks` (`relative_path`)")
        }
    }

    /** MU3.1（音乐轨 §5.2/§5.3 · DB v16）：歌单双表——music_playlists + music_playlist_items。
     *  双外键 CASCADE（删 Track/Playlist 自动清项）+ playlist_id/track_id 双索引（Room 外键须索引）。
     *  与 Entity/Dao/版本变更同 commit；老库升级（tracks 保留）= L3 必测。 */
    private val MIGRATION_15_16 = object : Migration(15, 16) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `music_playlists` (
                    `id` TEXT NOT NULL,
                    `name` TEXT NOT NULL,
                    `created_by` TEXT NOT NULL DEFAULT 'user',
                    `created_at` INTEGER NOT NULL,
                    `source_prompt` TEXT,
                    PRIMARY KEY(`id`)
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `music_playlist_items` (
                    `id` TEXT NOT NULL,
                    `playlist_id` TEXT NOT NULL,
                    `track_id` TEXT NOT NULL,
                    `position` INTEGER NOT NULL,
                    PRIMARY KEY(`id`),
                    FOREIGN KEY(`playlist_id`) REFERENCES `music_playlists`(`id`)
                        ON UPDATE NO ACTION ON DELETE CASCADE,
                    FOREIGN KEY(`track_id`) REFERENCES `music_tracks`(`id`)
                        ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent()
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_music_playlist_items_playlist_id` " +
                    "ON `music_playlist_items` (`playlist_id`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_music_playlist_items_track_id` " +
                    "ON `music_playlist_items` (`track_id`)")
        }
    }

    /** MU4.1（音乐轨 §5.4/MD14 · DB v17）：music_schedules——定时播放调度（认领状态机）。
     *  playlist_id 弱引用无 FK（歌单可先删，执行时查不到走失败文案）；
     *  status DEFAULT 'active' 与 @ColumnInfo(defaultValue=) 对齐。
     *  与 Entity/Dao/版本变更同 commit；老库升级 = L3 必测。 */
    private val MIGRATION_16_17 = object : Migration(16, 17) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `music_schedules` (
                    `id` TEXT NOT NULL,
                    `trigger_at` INTEGER NOT NULL,
                    `playlist_id` TEXT,
                    `mode` TEXT NOT NULL DEFAULT 'FIXED',
                    `hint` TEXT,
                    `is_recurring` INTEGER NOT NULL DEFAULT 0,
                    `recurring_ms` INTEGER NOT NULL DEFAULT 0,
                    `status` TEXT NOT NULL DEFAULT 'active',
                    `created_by` TEXT NOT NULL DEFAULT 'user',
                    PRIMARY KEY(`id`)
                )
                """.trimIndent()
            )
        }
    }

    /**
     * KB1.1（DB v18 · 设计 §五）：知识库两表——kb_docs（UNIQUE(relative_path) 索引）+
     * kb_chunks（FK→kb_docs.id CASCADE + doc_id 索引；embedding BLOB 可空 /
     * embed_model DEFAULT '' 换档防护 KA5）。DEFAULT 与 @ColumnInfo(defaultValue=) 逐项
     * 对齐；FK 子句 ON UPDATE NO ACTION ON DELETE CASCADE 与 Room 生成一致（15_16 同律）。
     * 与 Entity/Dao/版本变更同 commit；老库升级 = L3 必测。 */
    private val MIGRATION_17_18 = object : Migration(17, 18) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `kb_docs` (
                    `id` TEXT NOT NULL,
                    `relative_path` TEXT NOT NULL,
                    `title` TEXT NOT NULL DEFAULT '',
                    `mtime` INTEGER NOT NULL,
                    `size` INTEGER NOT NULL,
                    `chunk_count` INTEGER NOT NULL DEFAULT 0,
                    `indexed_at` INTEGER NOT NULL,
                    `tags` TEXT NOT NULL DEFAULT '',
                    PRIMARY KEY(`id`)
                )
                """.trimIndent()
            )
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_kb_docs_relative_path` " +
                    "ON `kb_docs` (`relative_path`)")
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `kb_chunks` (
                    `id` TEXT NOT NULL,
                    `doc_id` TEXT NOT NULL,
                    `seq` INTEGER NOT NULL,
                    `heading_path` TEXT NOT NULL DEFAULT '',
                    `text` TEXT NOT NULL,
                    `char_len` INTEGER NOT NULL,
                    `embedding` BLOB,
                    `embed_model` TEXT NOT NULL DEFAULT '',
                    `updated_at` INTEGER NOT NULL,
                    PRIMARY KEY(`id`),
                    FOREIGN KEY(`doc_id`) REFERENCES `kb_docs`(`id`)
                        ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent()
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_kb_chunks_doc_id` ON `kb_chunks` (`doc_id`)")
        }
    }

    /** M2.1（模块化轨）：dynamic_modules——D67/D68 简化字段（§5.2 + P0-4/P1-5）。
     *  列定义与 DynamicModuleEntity @ColumnInfo(defaultValue) 严格互配；导出的 Room schema
     *  与迁移 SQL 逐列校验，不匹配时中止升级并保留原库。 */
    private val MIGRATION_18_19 = object : Migration(18, 19) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `dynamic_modules` (
                    `id` TEXT NOT NULL,
                    `origin` TEXT NOT NULL DEFAULT 'HANDWRITTEN',
                    `source` TEXT NOT NULL,
                    `schema` TEXT NOT NULL DEFAULT '{}',
                    `transport` TEXT NOT NULL DEFAULT 'http',
                    `last_endpoint` TEXT NOT NULL DEFAULT '',
                    `deps` TEXT NOT NULL DEFAULT '[]',
                    `installed_deps` TEXT NOT NULL DEFAULT '[]',
                    `termux_deps` TEXT NOT NULL DEFAULT '[]',
                    `runtime` TEXT NOT NULL DEFAULT 'python',
                    `config_schema` TEXT NOT NULL DEFAULT '[]',
                    `state` TEXT NOT NULL DEFAULT 'DISABLED',
                    `created_at` INTEGER NOT NULL,
                    `last_crash_at` INTEGER NOT NULL DEFAULT 0,
                    PRIMARY KEY(`id`)
                )
                """.trimIndent()
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_dynamic_modules_state` ON `dynamic_modules` (`state`)")
        }
    }

    /** M4.1（A-II-3 全局层）：跨会话崩溃计数列——crashAutoDisableK（§9.1）消费 */
    private val MIGRATION_19_20 = object : Migration(19, 20) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `dynamic_modules` ADD COLUMN `crash_count` INTEGER NOT NULL DEFAULT 0")
        }
    }

    /** S2R.2（DB v21 · 自驱留痕）：messages 三列——source/system_source/trigger_id（§2.3 消息模型）。
     *  全可空列，存量行 NULL=普通消息（老库升级零丢失——ALTER 不触既有数据）；
     *  triggerId 清理策略一期不做（D-P5/6 裁定）。
     *  与 Entity/版本变更/Repository 同 commit；缺失迁移将中止升级并保留原库；老库升级 = L3 必测。 */
    private val MIGRATION_20_21 = object : Migration(20, 21) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `messages` ADD COLUMN `source` TEXT")
            db.execSQL("ALTER TABLE `messages` ADD COLUMN `system_source` TEXT")
            db.execSQL("ALTER TABLE `messages` ADD COLUMN `trigger_id` TEXT")
        }
    }

    /** P2-5b: AgentTrace 结构化工具关联字段；旧记录保持 NULL，不从 summary 猜测回填。 */
    private val MIGRATION_21_22 = object : Migration(21, 22) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE agent_trace_events ADD COLUMN `callId` TEXT")
            db.execSQL("ALTER TABLE agent_trace_events ADD COLUMN `functionName` TEXT")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_agent_trace_events_callId` ON `agent_trace_events` (`callId`)")
        }
    }

    /** R1: 记忆提取作业 outbox（替代 MemoryModule.pendingAiBuffer 易失队列）。
     *  唯一键 UNIQUE 幂等；state/next_attempt_at 索引支撑查询；纯新增表，老库升级零数据丢失。
     *  与 Entity/Dao/版本变更同 commit；缺失迁移将中止升级并保留原库；老库升级 = L3 必测。 */
    private val MIGRATION_22_23 = object : Migration(22, 23) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `memory_extraction_jobs` (
                    `id` TEXT NOT NULL,
                    `source_type` TEXT NOT NULL,
                    `conversation_id` TEXT,
                    `source_message_id` TEXT,
                    `source_digest` TEXT NOT NULL,
                    `unique_key` TEXT NOT NULL,
                    `state` TEXT NOT NULL DEFAULT 'PENDING',
                    `attempts` INTEGER NOT NULL DEFAULT 0,
                    `last_error` TEXT,
                    `created_at` INTEGER NOT NULL,
                    `updated_at` INTEGER NOT NULL,
                    `next_attempt_at` INTEGER NOT NULL DEFAULT 0,
                    PRIMARY KEY(`id`)
                )
                """.trimIndent()
            )
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_memory_extraction_jobs_unique_key` " +
                "ON `memory_extraction_jobs` (`unique_key`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_memory_extraction_jobs_state` " +
                "ON `memory_extraction_jobs` (`state`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_memory_extraction_jobs_next_attempt_at` " +
                "ON `memory_extraction_jobs` (`next_attempt_at`)")
        }
    }

    /**
     * R2: memories 表补 embed_model 列（KA5 记忆域换档防护——检索按当前模型预筛，不匹配走词法降级）。
     * P2 债收口：补 model-mismatch 触发的 re-embed 路径（getForReembedding + backfill 重嵌 stale）。
     * 纯加列+索引，老库升级零数据丢失——存量 embedding 保留但 embed_model 默认 ''（未知模型）→
     * 有界查询排除旧向量走词法，backfill 经 getForReembedding 重嵌后向量路恢复（自稳）。
     * 与 Entity/Dao/版本变更同 commit；老库升级 = L3 必测。 */
    private val MIGRATION_23_24 = object : Migration(23, 24) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `memories` ADD COLUMN `embed_model` TEXT NOT NULL DEFAULT ''")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_memories_embed_model` ON `memories` (`embed_model`)")
        }
    }

    /**
     * P1: 动态角色状态三表（D4：亲密度按角色长期 / 情绪按会话短期 分表）。
     * character_relation_states（character_id PK+UNIQUE，FK→characters CASCADE）+
     * conversation_mood_states（conversation_id PK+UNIQUE，FK→conversations CASCADE）+
     * character_state_events（幂等 idempotency_key UNIQUE + 双 FK CASCADE + 有界保留）。
     * 纯新增表，老库升级零数据丢失——无行=默认中性（旧角色/会话无状态记录，reducer P2 按需创建）。
     * 与 Entity/Dao/版本变更同 commit；老库升级 = L3 必测。 */
    private val MIGRATION_24_25 = object : Migration(24, 25) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `character_relation_states` (
                    `character_id` TEXT NOT NULL,
                    `affinity_band` TEXT NOT NULL DEFAULT 'ACQUAINTANCE',
                    `interaction_count` INTEGER NOT NULL DEFAULT 0,
                    `updated_at` INTEGER NOT NULL,
                    `revision` INTEGER NOT NULL DEFAULT 0,
                    PRIMARY KEY(`character_id`),
                    FOREIGN KEY(`character_id`) REFERENCES `characters`(`id`)
                        ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent()
            )
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_character_relation_states_character_id` " +
                "ON `character_relation_states` (`character_id`)")
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `conversation_mood_states` (
                    `conversation_id` TEXT NOT NULL,
                    `mood` TEXT NOT NULL DEFAULT 'NEUTRAL',
                    `intensity` REAL NOT NULL DEFAULT 0,
                    `expires_at` INTEGER NOT NULL DEFAULT 0,
                    `updated_at` INTEGER NOT NULL,
                    `revision` INTEGER NOT NULL DEFAULT 0,
                    PRIMARY KEY(`conversation_id`),
                    FOREIGN KEY(`conversation_id`) REFERENCES `conversations`(`id`)
                        ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent()
            )
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_conversation_mood_states_conversation_id` " +
                "ON `conversation_mood_states` (`conversation_id`)")
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `character_state_events` (
                    `id` TEXT NOT NULL,
                    `character_id` TEXT NOT NULL,
                    `conversation_id` TEXT,
                    `source_message_id` TEXT,
                    `change_type` TEXT NOT NULL,
                    `reason` TEXT NOT NULL,
                    `idempotency_key` TEXT NOT NULL,
                    `created_at` INTEGER NOT NULL,
                    PRIMARY KEY(`id`),
                    FOREIGN KEY(`character_id`) REFERENCES `characters`(`id`)
                        ON UPDATE NO ACTION ON DELETE CASCADE,
                    FOREIGN KEY(`conversation_id`) REFERENCES `conversations`(`id`)
                        ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent()
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_character_state_events_character_id` " +
                "ON `character_state_events` (`character_id`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_character_state_events_conversation_id` " +
                "ON `character_state_events` (`conversation_id`)")
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_character_state_events_idempotency_key` " +
                "ON `character_state_events` (`idempotency_key`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_character_state_events_created_at` " +
                "ON `character_state_events` (`created_at`)")
        }
    }

    /** E1：部署 journal（受控自迭代状态机持久化）。纯新增表，老库升级零数据丢失。 */
    private val MIGRATION_25_26 = object : Migration(25, 26) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `deployment_journal` (
                    `deployment_id` TEXT NOT NULL,
                    `module_id` TEXT NOT NULL,
                    `content_hash` TEXT NOT NULL,
                    `manifest` TEXT NOT NULL,
                    `state` TEXT NOT NULL,
                    `code_size` INTEGER NOT NULL,
                    `attempts` INTEGER NOT NULL DEFAULT 0,
                    `created_at` INTEGER NOT NULL,
                    `updated_at` INTEGER NOT NULL,
                    `last_error` TEXT NOT NULL DEFAULT '',
                    PRIMARY KEY(`deployment_id`)
                )
                """.trimIndent()
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_deployment_journal_module_id` " +
                "ON `deployment_journal` (`module_id`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_deployment_journal_content_hash` " +
                "ON `deployment_journal` (`content_hash`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_deployment_journal_module_id_state` " +
                "ON `deployment_journal` (`module_id`, `state`)")
        }
    }

    /** A2b: additive derived-index tables; existing source rows and legacy embeddings remain intact. */
    private val MIGRATION_26_27 = object : Migration(26, 27) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("""CREATE TABLE IF NOT EXISTS `vector_versions` (
                `id` TEXT NOT NULL, `domain` TEXT NOT NULL, `space` TEXT NOT NULL,
                `dimensions` INTEGER NOT NULL, `expectedCount` INTEGER NOT NULL, `baseVersion` TEXT,
                `state` TEXT NOT NULL, `digest` TEXT NOT NULL, `createdAt` INTEGER NOT NULL,
                PRIMARY KEY(`id`))""")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_vector_versions_domain` ON `vector_versions` (`domain`)")
            db.execSQL("""CREATE TABLE IF NOT EXISTS `vector_entries` (
                `versionId` TEXT NOT NULL, `sourceId` TEXT NOT NULL, `sourceText` TEXT NOT NULL,
                `sourceTitle` TEXT NOT NULL, `sourceHeading` TEXT NOT NULL, `vector` BLOB NOT NULL,
                PRIMARY KEY(`versionId`, `sourceId`),
                FOREIGN KEY(`versionId`) REFERENCES `vector_versions`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)""")
            db.execSQL("""CREATE TABLE IF NOT EXISTS `vector_active` (
                `domain` TEXT NOT NULL, `versionId` TEXT NOT NULL, PRIMARY KEY(`domain`),
                FOREIGN KEY(`versionId`) REFERENCES `vector_versions`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT)""")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_vector_active_versionId` ON `vector_active` (`versionId`)")
            db.execSQL("""CREATE TABLE IF NOT EXISTS `vector_validation` (
                `versionId` TEXT NOT NULL, `candidateDigest` TEXT NOT NULL, `datasetVersion` TEXT NOT NULL,
                `datasetDigest` TEXT NOT NULL, `expectedSamples` INTEGER NOT NULL, `evaluatedSamples` INTEGER NOT NULL,
                `baselineRecall` REAL NOT NULL, `candidateRecall` REAL NOT NULL, `minimumRecall` REAL NOT NULL,
                `report` TEXT NOT NULL, PRIMARY KEY(`versionId`),
                FOREIGN KEY(`versionId`) REFERENCES `vector_versions`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)""")
        }
    }

    private val MIGRATION_27_28 = object : Migration(27, 28) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE user_graph_relationships ADD COLUMN evidence TEXT")
        }
    }

    private val MIGRATION_28_29 = object : Migration(28, 29) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("CREATE TABLE IF NOT EXISTS memory_deletions (id TEXT NOT NULL PRIMARY KEY, deletedAt INTEGER NOT NULL)")
        }
    }

    private val MIGRATION_29_30 = object : Migration(29, 30) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE user_graph_entities ADD COLUMN memoryDerived INTEGER NOT NULL DEFAULT 0")
        }
    }

    private val MIGRATION_30_31 = object : Migration(30, 31) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("""CREATE TABLE IF NOT EXISTS `usage_requests` (
                `id` TEXT NOT NULL, `startedAt` INTEGER NOT NULL, `finishedAt` INTEGER,
                `connectionId` TEXT NOT NULL, `profileId` TEXT NOT NULL, `model` TEXT NOT NULL,
                `kind` TEXT NOT NULL, `status` TEXT NOT NULL, `inputTokens` INTEGER,
                `cachedTokens` INTEGER, `outputTokens` INTEGER, `continuity` TEXT NOT NULL,
                `budgetSource` TEXT NOT NULL, `local` INTEGER NOT NULL, PRIMARY KEY(`id`))""")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_usage_requests_startedAt` ON `usage_requests` (`startedAt`)")
        }
    }

    @Provides
    fun provideUsageRequestDao(db: ZeroDatabase): com.zero.v2.core.data.dao.UsageRequestDao = db.usageRequestDao()

    private val MIGRATION_31_32 = object : Migration(31, 32) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE memories ADD COLUMN origin TEXT NOT NULL DEFAULT 'UNKNOWN'")
            db.execSQL("ALTER TABLE memories ADD COLUMN source_message_id TEXT")
            db.execSQL("ALTER TABLE memories ADD COLUMN occurred_at INTEGER")
            db.execSQL("ALTER TABLE memories ADD COLUMN valid_from INTEGER")
            db.execSQL("ALTER TABLE memories ADD COLUMN valid_until INTEGER")
            db.execSQL("ALTER TABLE memories ADD COLUMN supersedes_id TEXT")
            db.execSQL("ALTER TABLE memories ADD COLUMN revision INTEGER NOT NULL DEFAULT 1")
            db.execSQL("ALTER TABLE memories ADD COLUMN scope TEXT NOT NULL DEFAULT 'personal'")
        }
    }

    /** 受支持的完整升级链；数据库注册与测试共用。 */
    private val MIGRATION_32_33 = object : Migration(32, 33) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("CREATE TABLE IF NOT EXISTS memory_sources (summaryId TEXT NOT NULL, sourceId TEXT NOT NULL, PRIMARY KEY(summaryId, sourceId))")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_memory_sources_sourceId ON memory_sources(sourceId)")
        }
    }

    internal val ALL_MIGRATIONS: Array<Migration> = arrayOf(
        MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9,
        MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13,
        MIGRATION_13_14, MIGRATION_14_15, MIGRATION_15_16, MIGRATION_16_17,
        MIGRATION_17_18, MIGRATION_18_19, MIGRATION_19_20, MIGRATION_20_21,
        MIGRATION_21_22, MIGRATION_22_23, MIGRATION_23_24, MIGRATION_24_25,
        MIGRATION_25_26, MIGRATION_26_27, MIGRATION_27_28, MIGRATION_28_29, MIGRATION_29_30, MIGRATION_30_31, MIGRATION_31_32, MIGRATION_32_33,
        MemoryReviewMigration, ChronicleMigration, MemoryDerivationMigration, MemoryMaintenanceMigration,
        object : Migration(33, 34) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS memory_conflicts (id TEXT NOT NULL PRIMARY KEY, leftId TEXT NOT NULL, rightId TEXT NOT NULL, leftVersion TEXT NOT NULL, rightVersion TEXT NOT NULL, judgment TEXT NOT NULL, state TEXT NOT NULL, createdAt INTEGER NOT NULL, resolvedAt INTEGER)")
            }
        }
    )

    @Provides
    fun provideVectorIndexDao(db: ZeroDatabase): com.zero.v2.core.data.dao.VectorIndexDao = db.vectorIndexDao()

    @Provides @Singleton
    fun provideDatabase(@ApplicationContext context: Context): ZeroDatabase {
        return Room.databaseBuilder(context, ZeroDatabase::class.java, "zero_v2.db")
            .addMigrations(*ALL_MIGRATIONS)
            .addCallback(ChroniclePrivacy)
            .build()
    }

    @Provides
    fun provideMemoryDao(db: ZeroDatabase): MemoryDao = db.memoryDao()

    @Provides
    fun provideMemoryReviewDao(db: ZeroDatabase): com.zero.v2.core.data.dao.MemoryReviewDao = db.memoryReviewDao()

    @Provides
    fun provideChronicleSourceDao(db: ZeroDatabase): com.zero.v2.core.data.dao.ChronicleSourceDao = db.chronicleSourceDao()

    @Provides
    fun provideMemoryDerivationDao(db: ZeroDatabase): com.zero.v2.core.data.dao.MemoryDerivationDao = db.memoryDerivationDao()

    @Provides
    fun provideConversationDao(db: ZeroDatabase): ConversationDao = db.conversationDao()

    @Provides
    fun provideMessageDao(db: ZeroDatabase): MessageDao = db.messageDao()

    @Provides
    fun provideCharacterDao(db: ZeroDatabase): CharacterDao = db.characterDao()

    @Provides
    fun provideAutomationDao(db: ZeroDatabase): AutomationDao = db.automationDao()

    @Provides
    fun provideAgentTraceEventDao(db: ZeroDatabase): AgentTraceEventDao = db.agentTraceEventDao()

    @Provides
    fun provideSystemStateDao(db: ZeroDatabase): com.zero.v2.core.data.dao.SystemStateDao =
        db.systemStateDao()

    @Provides
    fun provideUserGraphDao(db: ZeroDatabase): UserGraphDao = db.userGraphDao()

    @Provides
    fun provideMusicDao(db: ZeroDatabase): com.zero.v2.core.data.dao.MusicDao = db.musicDao()

    @Provides
    fun provideKbDao(db: ZeroDatabase): com.zero.v2.core.data.dao.KbDao = db.kbDao()

    /** M2.1（模块化轨）：动态模块注册表 */
    @Provides
    fun provideDynamicModuleDao(db: ZeroDatabase): com.zero.v2.core.data.dao.DynamicModuleDao =
        db.dynamicModuleDao()

    /** R1：记忆提取作业 outbox DAO */
    @Provides
    fun provideMemoryExtractionJobDao(db: ZeroDatabase): com.zero.v2.core.data.dao.MemoryExtractionJobDao =
        db.memoryExtractionJobDao()

    /** P1：角色状态（亲密度/情绪/事件）DAO */
    @Provides
    fun provideCharacterStateDao(db: ZeroDatabase): com.zero.v2.core.data.dao.CharacterStateDao =
        db.characterStateDao()

    @Provides
    fun provideDeploymentJournalDao(db: ZeroDatabase): com.zero.v2.core.data.dao.DeploymentJournalDao =
        db.deploymentJournalDao()
}
