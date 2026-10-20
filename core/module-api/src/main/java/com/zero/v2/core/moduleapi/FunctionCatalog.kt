package com.zero.v2.core.moduleapi

import com.zero.v2.core.common.FunctionDefinition

/**
 * 函数目录的窄 seam：只负责发现定义和按模块查询条目，不暴露执行/调度实现。
 * 默认放行与权限裁决属于后续 ToolDispatcher 纵切片，不在此接口重复建模。
 */
interface FunctionCatalog {
    fun getAllDefinitions(): List<FunctionDefinition>
    fun getEntriesByModule(moduleId: String): List<FunctionEntry>
}
