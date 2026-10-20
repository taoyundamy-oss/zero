package com.zero.v2.framework.memory

import com.zero.v2.core.common.TaskMemory
import com.zero.v2.core.data.entity.MemoryDerivationEntity
import com.zero.v2.core.data.store.MemoryDerivationStore
import org.json.JSONObject

internal object MemoryWriteReceipt {
    fun render(saved: MemoryDerivationEntity, receipt: MemoryDerivationStore.WriteReceipt, wakeFailed: Boolean): String =
        JSONObject().put("derivation_id", saved.id).put("memory_id", saved.memoryId)
            .put("scope", saved.scope).put("claim_key", saved.claimKey).put("type", saved.type.name)
            .put("persisted", true).put("state", receipt.queueState)
            .put("materialized", receipt.memory != null).put("status", receipt.memory?.status ?: "NOT_MATERIALIZED")
            .put("version", receipt.memory?.version ?: JSONObject.NULL)
            .put("task_status", TaskMemory.decode(saved.content)?.status ?: JSONObject.NULL)
            .put("background_wake_requested", !wakeFailed).put("usable_as_current_fact", false)
            .put("notice", "已确认落盘；state为当前派生队列状态，materialized仅指条目已生成，非语义认证或当前可用证明。" +
                "PENDING等待后台处理，无需为确认落盘再次召回；失效/冲突/撤回仍按当前视图核对。" +
                if (wakeFailed) "后台唤醒未确认，保留持久队列等待恢复，不虚报已生效。" else "")
            .toString()
}
