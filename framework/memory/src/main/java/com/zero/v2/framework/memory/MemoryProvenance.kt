package com.zero.v2.framework.memory

import com.zero.v2.core.data.entity.GraphMemoryEvidence
import com.zero.v2.core.data.entity.MemoryEntity

/** Association with a conversation alone cannot authenticate legacy model summaries as user facts. */
internal fun memoryProvenance(memory: MemoryEntity): String =
    if (memory.origin == "USER_QUOTE" && memory.sourceMessageId != null || GraphMemoryEvidence.eligible(memory)) "用户原文提取"
    else if (memory.origin == "USER_CONFIRMED") "用户复核确认，非历史原话认证；适用范围：${memory.scope}"
    else if (memory.origin == "MODEL_CLASSIFIED") "主对话分类候选，绑定来源版本，尚非语义认证"
    else if (memory.origin == "MODEL_NOTE") "模型笔记，未获用户确认" else "历史记录，来源未核验"

/** Only new explicit confirmations carry scope here; existing stable memories remain byte-identical. */
internal fun memoryContextText(memory: MemoryEntity): String =
    if (memory.origin == "USER_CONFIRMED") "适用范围：${memory.scope}\n${memory.content}" else memory.content
