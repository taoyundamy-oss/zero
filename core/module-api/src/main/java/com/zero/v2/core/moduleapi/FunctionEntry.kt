package com.zero.v2.core.moduleapi

import com.zero.v2.core.common.*

/** 函数注册条目 */
data class FunctionEntry(
    val definition: FunctionDefinition,
    val sourceModuleId: String,
    val handler: suspend (Map<String, Any?>) -> FunctionResult,
    /** v2.3: 统一行动权限（DESIGN D9/D24），替代 SAFE/SENSITIVE 双轨 */
    val permission: ActionPermission = ActionPermission.AUTO
)
