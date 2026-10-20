package com.zero.v2.app.ui.settings

import com.zero.v2.core.moduleapi.ModelRouteStatus
import com.zero.v2.core.moduleapi.StatusFact

object ModelStatusPresentation {
    fun text(routes: List<ModelRouteStatus>): String {
        val enabled = routes.filter { it.enabled }
        val success = enabled.count { it.availability == StatusFact.LIVE }
        val expired = enabled.count { it.availability == StatusFact.STALE }
        val errors = enabled.filter { it.availability == StatusFact.UNKNOWN && it.requestOutcome != null }
        return buildString {
            append("${enabled.size} 已启用 · ${enabled.count { it.keyConfigured == true }} 已配置凭据")
            append("\n近期 HTTP 正常 $success · 过期 $expired · 未验证 ${enabled.size - success - expired - errors.size}")
            if (errors.isNotEmpty()) append(" · 请求异常 ${errors.size}")
            append("\n仅记录响应状态，内容与能力需实际调用验证")
        }
    }
}
