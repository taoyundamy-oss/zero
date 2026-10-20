package com.zero.v2.services.module

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import com.zero.v2.core.common.ActionPermission
import com.zero.v2.core.common.FunctionDefinition
import com.zero.v2.core.moduleapi.IZeroModule
import com.zero.v2.core.moduleapi.ModuleCategory
import com.zero.v2.core.moduleapi.ModuleConfigBase
import com.zero.v2.core.moduleapi.ModuleLifecycle
import com.zero.v2.core.moduleapi.ModuleMetadata
import com.zero.v2.core.common.FunctionResult
import com.zero.v2.core.trace.AgentTraceManager
import com.zero.v2.core.trace.NodeType
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 日历模块 — Step 9.3（D58 系统级感知）。
 *
 * ContentResolver 操作 Android CalendarProvider：
 *   FC calendar_list_events（AUTO）——查询未来日程
 *   FC calendar_create_event（CONFIRM）——创建日程
 *   FC calendar_delete_event（CONFIRM）——删除日程
 *
 * 时间格式：yyyy-MM-dd HH:mm（LLM 负责转换人类语言为标准格式）。
 */
@Singleton
class CalendarModule @Inject constructor(
    @ApplicationContext private val context: Context,

    private val traceManager: AgentTraceManager
) : IZeroModule, com.zero.v2.core.moduleapi.ToolContributor {

    companion object {
        private const val TRACE_ID = "calendar"
        private val EVENT_PROJECTION = arrayOf(
            CalendarContract.Events._ID,
            CalendarContract.Events.TITLE,
            CalendarContract.Events.DESCRIPTION,
            CalendarContract.Events.DTSTART,
            CalendarContract.Events.DTEND,
            CalendarContract.Events.EVENT_LOCATION,
            CalendarContract.Events.ALL_DAY
        )
    }

    override val metadata = ModuleMetadata(
        id = "calendar",
        displayName = "日历",
        description = "读取和管理系统日历日程（查看/创建/删除）",
        category = ModuleCategory.SYSTEM,
        launchOrder = 120
    )

    private val _lifecycle = MutableStateFlow(ModuleLifecycle.UNINITIALIZED)
    override val lifecycle = _lifecycle.asStateFlow()
    override var isEnabled: Boolean = true
    override val requiredPermissions: List<String> = listOf(
        Manifest.permission.READ_CALENDAR,
        Manifest.permission.WRITE_CALENDAR
    )
    override val config = object : ModuleConfigBase() {
        override fun toJson() = "{}"
        override fun fromJson(json: String) {}
        override fun defaultConfig() = this
    }

    override suspend fun onAwake() {

        _lifecycle.value = ModuleLifecycle.AWAKE
    }
    override suspend fun onStart() { _lifecycle.value = ModuleLifecycle.STARTED }
    override suspend fun onDestroy() { _lifecycle.value = ModuleLifecycle.DESTROYED }
    override suspend fun onConfigChanged(newConfig: ModuleConfigBase) {}
    override suspend fun checkAvailability(): Boolean = hasReadPermission()
    override fun markDisabled() { _lifecycle.value = ModuleLifecycle.DISABLED }

    override fun contributions(): List<com.zero.v2.core.moduleapi.ToolContribution> =
        com.zero.v2.core.moduleapi.toolDeclarations(metadata.id) {
        val functionRegistry = this
        functionRegistry.register(
            FunctionDefinition(
                name = "calendar_list_events",
                description = "查询系统日历中的未来日程安排。返回事件标题、时间、地点、描述。",
                parameters = mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "count" to mapOf("type" to "number",
                            "description" to "返回条数 1-20（默认 5）")
                    )
                )
            ),
            metadata.id, permission = ActionPermission.AUTO
        ) { args ->
            val count = ((args["count"] as? Number)?.toInt() ?: 5).coerceIn(1, 20)
            listEvents(count)
        }

        functionRegistry.register(
            FunctionDefinition(
                name = "calendar_create_event",
                description = "在系统日历中创建一个日程。时间格式 yyyy-MM-dd HH:mm（24小时制）。" +
                        "示例 start_time=\"2026-08-30 14:00\" end_time=\"2026-08-30 15:00\"",
                parameters = mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "title" to mapOf("type" to "string", "description" to "日程标题"),
                        "start_time" to mapOf("type" to "string",
                            "description" to "开始时间 yyyy-MM-dd HH:mm"),
                        "end_time" to mapOf("type" to "string",
                            "description" to "结束时间 yyyy-MM-dd HH:mm（可选，默认开始后1小时）"),
                        "description" to mapOf("type" to "string",
                            "description" to "日程描述（可选）"),
                        "location" to mapOf("type" to "string",
                            "description" to "地点（可选）")
                    ),
                    "required" to listOf("title", "start_time")
                )
            ),
            metadata.id, permission = ActionPermission.CONFIRM
        ) { args ->
            val title = args["title"] as? String ?: ""
            val startTimeStr = args["start_time"] as? String ?: ""
            val endTimeStr = args["end_time"] as? String
            val description = args["description"] as? String
            val location = args["location"] as? String
            createEvent(title, startTimeStr, endTimeStr, description, location)
        }

        functionRegistry.register(
            FunctionDefinition(
                name = "calendar_delete_event",
                description = "删除系统日历中的指定日程（通过 calendar_list_events 获取 event_id）。",
                parameters = mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "event_id" to mapOf("type" to "string",
                            "description" to "日程 ID（从 calendar_list_events 返回）")
                    ),
                    "required" to listOf("event_id")
                )
            ),
            metadata.id, permission = ActionPermission.CONFIRM
        ) { args ->
            val eventId = args["event_id"] as? String ?: ""
            deleteEvent(eventId)
        }
    }

    // ═══ 权限检查 ═══

    private fun hasReadPermission(): Boolean = try {
        context.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED
    } catch (_: Exception) { false }

    private fun hasWritePermission(): Boolean = try {
        context.checkSelfPermission(Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED
    } catch (_: Exception) { false }

    // ═══ FC 实现 ═══

    private suspend fun listEvents(count: Int): FunctionResult = withContext(Dispatchers.IO) {
        if (!hasReadPermission()) {
            return@withContext FunctionResult.failure("日历读取权限未授予。请引导用户到 设置→权限中心 授权日历权限。")
        }
        try {
            val now = System.currentTimeMillis()
            val selection = "${CalendarContract.Events.DTSTART} >= ?"
            val selectionArgs = arrayOf(now.toString())
            val sortOrder = "${CalendarContract.Events.DTSTART} ASC"

            val events = context.contentResolver.query(
                CalendarContract.Events.CONTENT_URI,
                EVENT_PROJECTION, selection, selectionArgs, sortOrder
            )?.use { cursor ->
                val result = mutableListOf<String>()
                while (cursor.moveToNext() && result.size < count) {
                    val id = cursor.getString(0)
                    val title = cursor.getString(1) ?: "(无标题)"
                    val desc = cursor.getString(2) ?: ""
                    val start = cursor.getLong(3)
                    val end = cursor.getLong(4)
                    val loc = cursor.getString(5) ?: ""
                    val allDay = cursor.getInt(6) == 1
                    val fmt = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
                    val startStr = if (allDay) SimpleDateFormat("MM-dd", Locale.getDefault()).format(Date(start))
                                   else fmt.format(Date(start))
                    val sb = StringBuilder("📅 [$id] $startStr $title")
                    if (loc.isNotBlank()) sb.append(" 📍$loc")
                    if (desc.isNotBlank()) sb.append(" — ${desc.take(40)}")
                    result.add(sb.toString())
                }
                result
            } ?: emptyList()

            if (events.isEmpty()) {
                FunctionResult.success("📭 近期无日程安排")
            } else {
                traceManager.record(TRACE_ID, NodeType.SYSTEM_EVENT, metadata.id,
                    "events_listed: count=${events.size}")
                FunctionResult.success("📅 未来日程（${events.size} 条）：\n${events.joinToString("\n")}")
            }
        } catch (e: Exception) {
            traceManager.record(TRACE_ID, NodeType.ERROR, metadata.id,
                "list_failed: ${e.message?.take(40)}")
            FunctionResult.failure("❌ 日历读取失败: ${e.message?.take(50)}")
        }
    }

    private suspend fun createEvent(
        title: String, startTimeStr: String, endTimeStr: String?,
        description: String?, location: String?
    ): FunctionResult = withContext(Dispatchers.IO) {
        if (!hasWritePermission()) {
            return@withContext FunctionResult.failure("日历写入权限未授予。请引导用户到 设置→权限中心 授权日历权限。")
        }
        val startMs = parseTime(startTimeStr)
            ?: return@withContext FunctionResult.failure(
                "开始时间格式错误「$startTimeStr」——请用 yyyy-MM-dd HH:mm 格式（如 2026-08-30 14:00）")
        val endMs = endTimeStr?.let { parseTime(it) } ?: (startMs + 60 * 60 * 1000)

        try {
            val calendarId = getDefaultCalendarId()
                ?: return@withContext FunctionResult.failure("未找到可写入的系统日历账户")

            val values = ContentValues().apply {
                put(CalendarContract.Events.CALENDAR_ID, calendarId)
                put(CalendarContract.Events.TITLE, title)
                put(CalendarContract.Events.DTSTART, startMs)
                put(CalendarContract.Events.DTEND, endMs)
                put(CalendarContract.Events.EVENT_TIMEZONE, java.util.TimeZone.getDefault().id)
                description?.let { put(CalendarContract.Events.DESCRIPTION, it) }
                location?.let { put(CalendarContract.Events.EVENT_LOCATION, it) }
            }

            val uri = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
                ?: return@withContext FunctionResult.failure("日历写入失败（contentResolver.insert 返回 null）")
            val eventId = uri.lastPathSegment

            val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
            traceManager.record(TRACE_ID, NodeType.SYSTEM_EVENT, metadata.id,
                "event_created: id=$eventId title=${title.take(30)}")
            FunctionResult.success("✅ 日程已创建：「$title」${fmt.format(Date(startMs))}（ID=$eventId）")
        } catch (e: Exception) {
            traceManager.record(TRACE_ID, NodeType.ERROR, metadata.id,
                "create_failed: ${e.message?.take(40)}")
            FunctionResult.failure("❌ 日程创建失败: ${e.message?.take(50)}")
        }
    }

    private suspend fun deleteEvent(eventId: String): FunctionResult = withContext(Dispatchers.IO) {
        if (!hasWritePermission()) {
            return@withContext FunctionResult.failure("日历写入权限未授予")
        }
        try {
            val uri = android.net.Uri.withAppendedPath(
                CalendarContract.Events.CONTENT_URI, eventId)
            val deleted = context.contentResolver.delete(uri, null, null)
            if (deleted > 0) {
                traceManager.record(TRACE_ID, NodeType.SYSTEM_EVENT, metadata.id,
                    "event_deleted: id=$eventId")
                FunctionResult.success("✅ 日程 $eventId 已删除")
            } else {
                FunctionResult.failure("日程 $eventId 不存在或已删除")
            }
        } catch (e: Exception) {
            FunctionResult.failure("❌ 删除失败: ${e.message?.take(50)}")
        }
    }

    // ═══ 辅助 ═══

    /** 获取第一个可写入的日历 ID */
    private fun getDefaultCalendarId(): Long? {
        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL
        )
        val selection = "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ?"
        val selectionArgs = arrayOf(
            CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR.toString()
        )
        return context.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            projection, selection, selectionArgs, null
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getLong(0) else null
        }
    }

    /** 解析 yyyy-MM-dd HH:mm 格式时间为毫秒时间戳 */
    private fun parseTime(timeStr: String): Long? = try {
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        fmt.isLenient = true
        fmt.parse(timeStr)?.time
    } catch (_: Exception) { null }
}
