package com.zero.v2.app.acceptance

import java.security.MessageDigest

/** Human-authored labels; separate from the unchanged production vector promotion benchmark. */
internal object ScaleRecallFixture {
    const val VERSION = "zero-scale-recall-v1"
    data class Source(val text: String, val title: String = "独立验收资料")
    data class Sample(val kind: String, val query: String, val relevant: Set<Int>, val forbidden: Set<Int> = emptySet())
    val sources = listOf(
        Source("备用电源每周检查一次，停电后为网络设备供电。"),
        Source("设备报修需要提供设备编号、故障时间和错误截图。"),
        Source("青岚项目负责人是林澄，交付联系人也是林澄。"),
        Source("白水洞游览的集合地点是东门游客中心。"),
        Source("订单编号ZX-2048的交付日期为10月18日。"),
        Source("设备SN-701的维修电话是0739-6123456。"),
        Source("排查故障时禁止清空数据库，必须保留个人数据。"),
        Source("偏好说明：不喝冷饮，喜欢示例食物A和温水。"),
        Source("培训地点更正为五楼501室，原三楼302室安排失效。"),
        Source("旧培训安排：培训地点是三楼302室，后续已被更正。"),
        Source("备份时间调整为凌晨三点，旧凌晨一点配置停止执行。"),
        Source("旧备份配置：每天凌晨一点运行，此配置已失效。"),
        Source("目前临时办公地点是北楼203室，期限截至10月12日。"),
        Source("之前临时办公地点是南楼102室，已结束使用。"),
        Source("本周值班人员为周宁，值班表从10月5日起生效。"),
        Source("上周值班人员为陈远，旧值班表已结束。"),
        Source("青岚发布需要完成签名校验，详情参见部署手册。", "发布要求"),
        Source("青岚部署手册规定签名校验失败时停止部署并保留旧版本。", "部署手册"),
        Source("远程会议先预约会议室，设备连接步骤参见设备指南。", "会议预约"),
        Source("远程会议设备指南要求连接投影仪后测试麦克风。", "设备指南"),
        Source("白水洞游览预约需要提前一天登记游客姓名。"),
        Source("青岚发布的审核人是林澄。"),
    )
    val samples = listOf(
        Sample("中文口语", "断电后网络还能靠什么继续工作？", setOf(0)),
        Sample("中文口语", "机器坏了找人修，要准备什么信息？", setOf(1)),
        Sample("专名", "青岚项目找谁负责交付？", setOf(2)),
        Sample("专名", "白水洞游览在哪个门集合？", setOf(3)),
        Sample("数字", "ZX-2048订单哪天交付？", setOf(4)),
        Sample("数字", "SN-701维修联系电话？", setOf(5)),
        Sample("否定", "排查故障禁止做什么以保护个人数据？", setOf(6)),
        Sample("否定", "冷饮和示例食物A有哪些喜好或禁忌？", setOf(7)),
        Sample("纠正", "更正后培训应该去哪个房间？", setOf(8), setOf(9)),
        Sample("纠正", "调整后的备份执行时间？", setOf(10), setOf(11)),
        Sample("时效", "目前临时办公地点及有效期限？", setOf(12), setOf(13)),
        Sample("时效", "本周生效的值班安排是谁？", setOf(14), setOf(15)),
        Sample("跨文档", "青岚发布前需要什么校验，校验失败怎么办？", setOf(16, 17)),
        Sample("跨文档", "远程会议如何预约并检查设备？", setOf(18, 19)),
        Sample("转写误差", "白水东游览怎么预越？", setOf(20)),
        Sample("转写误差", "清岚发布谁来申核？", setOf(21)),
    )
    fun corpus(size: Int): List<Source> {
        require(size in listOf(100, 1000, 10000))
        return sources + (sources.size until size).map {
            Source("档案${it.toString().padStart(5, '0')}：办公设备巡检登记完成，维修配件数量${it % 97}，旧值班资料归档。", "巡检档案$it")
        }
    }
    fun digest(size: Int): String {
        val canonical = VERSION + corpus(size).joinToString { "${it.text.length}:${it.text}${it.title.length}:${it.title}" } + samples.toString()
        return MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
