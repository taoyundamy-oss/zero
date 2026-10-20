package com.zero.v2.services.embedding

import com.zero.v2.core.data.entity.VectorSourceRow

/** Hand-authored, fixed independent benchmark. Never inserted into personal memory or knowledge. */
internal object VectorRecallBenchmark {
    val sources = listOf(
        VectorSourceRow("backup", "备份完成后，应校验文件摘要与源文件一致，再报告备份成功。", "操作手册", "备份验收"),
        VectorSourceRow("backup-wrong", "只看复制进度达到百分之百就报告成功，不检查备份文件。", "错误示例", "备份误区"),
        VectorSourceRow("support", "设备故障报修需要提供设备编号、故障出现时间和错误截图。", "操作手册", "报修"),
        VectorSourceRow("support-wrong", "领用办公用品只需填写品名与数量，无需设备编号或故障截图。", "操作手册", "领用"),
        VectorSourceRow("delete", "保留用户数据；排查应用故障时禁止清空数据库或删除用户目录。", "操作手册", "数据保护"),
        VectorSourceRow("delete-wrong", "已废弃的排障方案：清空数据库并删除用户目录，然后重新启动。", "历史档案", "旧排障方案"),
        VectorSourceRow("network", "断网期间保留已完成的批次，等待网络恢复后继续；不要从头重做。", "操作手册", "网络中断"),
        VectorSourceRow("network-wrong", "已否决方案：网络中断后丢弃所有已完成批次，从头重新执行。", "历史档案", "旧网络方案"),
        VectorSourceRow("room", "更正通知：培训地点由三楼302室改为五楼501室，以本通知为准。", "最新通知", "培训地点"),
        VectorSourceRow("room-wrong", "原培训安排：培训地点为三楼302室。本安排已被更正通知替代。", "历史档案", "原培训地点"),
        VectorSourceRow("time", "更新时间：备份任务从凌晨一点调整为凌晨三点，旧时刻不再执行。", "最新通知", "备份时刻"),
        VectorSourceRow("time-wrong", "旧配置记录：每天凌晨一点执行备份，后续已调整执行时间。", "历史档案", "旧备份时刻"),
    )

    fun dataset(domain: VectorDomain): VectorRecallDataset {
        val samples = listOf(
            sample("backup", "备份文件拷完后，怎样确认它确实可用？", VectorRecallDataset.Kind.SOURCE),
            sample("support", "机器出问题要提交维修申请，应准备哪些信息？", VectorRecallDataset.Kind.SOURCE),
            sample("delete", "排查故障时哪些操作禁止执行，以免丢失个人数据？", VectorRecallDataset.Kind.NEGATION),
            sample("network", "网络暂时不可用时，如何处理已经完成的工作，避免重复？", VectorRecallDataset.Kind.NEGATION),
            sample("room", "按更正后的最新安排，培训现在应去哪个房间？", VectorRecallDataset.Kind.CORRECTION),
            sample("time", "时间调整之后，备份现在几点运行？", VectorRecallDataset.Kind.CORRECTION),
        )
        return VectorRecallDataset("zero-independent-recall-zh-en-v2", domain, samples + englishSamples(), limit = 1,
            sourceDigests = sources.associate { it.id to VectorRecallSources.digest(it.text, it.title, it.heading) })
            .snapshot()
    }

    private fun sample(id: String, query: String, kind: VectorRecallDataset.Kind) =
        VectorRecallDataset.Sample(id, query, kind, setOf(id), setOf("$id-wrong"))

    /** Exact former corpus: only this known subset may supply a legacy Chinese baseline. */
    fun legacyDataset(domain: VectorDomain): VectorRecallDataset = dataset(domain).let {
        it.copy(version = "zero-independent-recall-v1", samples = it.samples.filterNot { row ->
            row.id.endsWith(":en")
        }).snapshot()
    }

    // Query language is a frozen benchmark stratum, not inferred from user text at runtime.
    fun stratum(sample: VectorRecallDataset.Sample): String =
        "${if (sample.id.endsWith(":en")) "en" else "zh"}:${sample.kind.name}"

    private fun englishSamples(): List<VectorRecallDataset.Sample> = listOf(
        sample("backup", "After copying a backup file, how can I confirm that it is usable?",
            VectorRecallDataset.Kind.SOURCE),
        sample("support", "What information should I prepare to request repair of a faulty device?",
            VectorRecallDataset.Kind.SOURCE),
        sample("delete", "Which troubleshooting actions are forbidden to prevent loss of personal data?",
            VectorRecallDataset.Kind.NEGATION),
        sample("network", "During a temporary network outage, " +
            "how should completed work be handled to avoid repeating it?",
            VectorRecallDataset.Kind.NEGATION),
        sample("room", "According to the latest corrected schedule, which room should I go to for training now?",
            VectorRecallDataset.Kind.CORRECTION),
        sample("time", "After the schedule change, at what time does the backup run now?",
            VectorRecallDataset.Kind.CORRECTION),
    ).map { it.copy(id = "${it.id}:en") }
}
