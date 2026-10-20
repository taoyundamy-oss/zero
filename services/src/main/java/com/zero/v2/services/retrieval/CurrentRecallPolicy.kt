package com.zero.v2.services.retrieval

/** Read-only exclusion of explicitly retired snapshots when the query asks for current information. */
object CurrentRecallPolicy {
    const val VERSION = "current-retired-snapshot-v2"
    fun eligible(query: String, text: String): Boolean {
        if (!CURRENT.containsMatchIn(query) || HISTORY.containsMatchIn(query)) return true
        val body = text.trim()
        // A quote can describe the old value and the replacement in the same sentence.
        return NEW_VALUE.containsMatchIn(body) || !RETIRED.containsMatchIn(body)
    }

    private val CURRENT = Regex("更正后|调整后|最新|目前|现在|当前|本周|生效|" +
        "\\b(?:now|current|currently|latest|updated|in effect|this week)\\b", RegexOption.IGNORE_CASE)
    private val HISTORY = Regex("更正前|调整前|原来|曾经|之前|历史|对比|前后|旧.*(?:安排|配置|记录|版本)|" +
        "\\b(?:previous|previously|formerly|before|history|historical|compare|comparison|old)\\b",
        RegexOption.IGNORE_CASE)
    private val NEW_VALUE = Regex("调整为|更正为|改为|变为|现在(?:是|在|为)")
    private val RETIRED = Regex("^(?:已废弃|已否决)|^(?:(?:旧|原)(?=.{0,40}" +
        "(?:安排|配置|方案|通知|记录|版本|计划))|之前|上周).{0,180}" +
        "(?:已失效|已被更正|已结束|后续已调整执行时间|停止执行|已被.*替代)")
}
