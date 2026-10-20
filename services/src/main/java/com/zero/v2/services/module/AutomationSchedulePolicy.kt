package com.zero.v2.services.module

import java.util.Calendar

/** 自动化调度的纯解析/推导策略，隔离 AutomationModule 的 DB 与执行编排。 */
internal object AutomationSchedulePolicy {
    fun derive(description: String): Pair<String, String> {
        val d = description.replace('：', ':')
        val interval = Regex("每\\s*(\\d+)\\s*(秒|分钟|小时)").find(d)
        if (interval != null) {
            val n = interval.groupValues[1].toLong()
            val sec =
                when (interval.groupValues[2]) {
                    "秒" -> n
                    "分钟" -> n * 60
                    else -> n * 3600
                }
            return "INTERVAL" to sec.toString()
        }
        if (d.contains(Regex("每[天日]"))) {
            val hm = Regex("(\\d{1,2}):(\\d{2})").find(d)
            if (hm != null) return "DAILY" to "${hm.groupValues[1].padStart(2, '0')}:${hm.groupValues[2]}"
            val hour = Regex("(\\d{1,2})\\s*点").find(d)
            if (hour != null) return "DAILY" to "${hour.groupValues[1].padStart(2, '0')}:00"
        }
        return "MANUAL" to ""
    }

    fun nextRun(
        type: String,
        value: String,
        now: Long = System.currentTimeMillis(),
    ): Long {
        return when (type.uppercase()) {
            "DAILY" -> {
                val m = Regex("(\\d{1,2}):(\\d{2})").find(value) ?: return Long.MAX_VALUE
                val cal =
                    Calendar.getInstance().apply {
                        timeInMillis = now
                        set(Calendar.HOUR_OF_DAY, m.groupValues[1].toInt())
                        set(Calendar.MINUTE, m.groupValues[2].toInt())
                        set(Calendar.SECOND, 0)
                        set(Calendar.MILLISECOND, 0)
                    }
                if (cal.timeInMillis <= now) cal.add(Calendar.DAY_OF_YEAR, 1)
                cal.timeInMillis
            }
            "INTERVAL" -> value.toLongOrNull()?.takeIf { it > 0 }?.let { now + it * 1000 } ?: Long.MAX_VALUE
            else -> Long.MAX_VALUE
        }
    }
}
