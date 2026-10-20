package com.zero.v2.core.moduleapi

/** Expected business rejection at the execution guard; it does not mean the module crashed. */
class ModuleOperationRejectedException(val code: String) : RuntimeException(code)
