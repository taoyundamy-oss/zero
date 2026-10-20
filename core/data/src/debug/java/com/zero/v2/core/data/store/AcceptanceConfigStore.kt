package com.zero.v2.core.data.store

import kotlinx.coroutines.CoroutineScope

/** Debug acceptance owns the retry worker and its isolated database lifetime. */
fun acceptanceConfigStore(dao: ModuleConfigDao, scope: CoroutineScope): ModuleConfigStore = ModuleConfigStore(dao, scope)
