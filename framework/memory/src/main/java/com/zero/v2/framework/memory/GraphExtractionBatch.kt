package com.zero.v2.framework.memory

import com.zero.v2.core.data.entity.*
import com.zero.v2.services.module.JsonExtractor
import org.json.JSONObject

internal data class GraphExtractionBatch(
    val entities: List<UserGraphEntity>, val attributes: List<UserGraphAttribute>,
    val relationships: List<UserGraphRelationship>,
) {
    companion object {
        const val PROMPT = """从编号的用户原句提取知识图谱，只返回JSON：
{"entities":[{"id":"user或其他实体ID","name":"名称","type":"PERSON|PLACE|ORGANIZATION|CONCEPT"}],
"attributes":[{"entityId":"user","key":"小写英文属性名","memoryIndex":1}],
"relationships":[{"from":"user","to":"其他实体ID","type":"关系类型","memoryIndex":1,"active":true}]}
三个数组必须都有，无内容时返回空数组。每条属性/关系必须引用输入序号memoryIndex。
用户特征使用attributes。宿主保存完整原句，不接受改写或自报置信度。
仅提取原句明确支持的信息，保留否定、更正和条件，不把假设或提示模板当作事实。
原句明确否认/解除关系时，用相同实体ID及关系类型并设置active=false；成立的关系设置active=true。
同实体同属性有冲突时优先明确的更正，不根据常识补全。"""

        fun decode(raw: String, sources: List<MemoryEntity>, now: Long): GraphExtractionBatch {
            val root = JSONObject(JsonExtractor.extract(raw) ?: raw)
            val attrs = root.getJSONArray("attributes")
            val relations = root.getJSONArray("relationships")
            val entities = root.getJSONArray("entities")
            require(listOf(attrs, relations, entities).all { it.length() <= 200 }) { "graph_output_limit" }
            val attributes = (0 until attrs.length()).map {
                requireNotNull(GraphAttributeGrounding.resolve(attrs.getJSONObject(it), sources, now)) { "graph_attribute_source" }
            }.sortedWith(compareByDescending<GraphAttributeGrounding.Candidate> { it.source.createdAt }.thenByDescending { it.source.id })
                .distinctBy { it.attribute.entityId to it.attribute.key }.map { it.attribute }
            val relationships = (0 until relations.length()).map {
                requireNotNull(GraphRelationshipGrounding.resolve(relations.getJSONObject(it), sources, now)) { "graph_relationship_source" }
            }.sortedWith(compareByDescending<GraphRelationshipGrounding.Candidate> { it.source.createdAt }.thenByDescending { it.source.id })
                .distinctBy { Triple(it.relationship.fromEntityId, it.relationship.toEntityId, it.relationship.type) }
                .map { it.relationship }
            val needed = (attributes.map { it.entityId } + relationships.flatMap { listOf(it.fromEntityId, it.toEntityId) }).toSet()
            val names = (0 until entities.length()).map { entities.getJSONObject(it) }.associateBy { it.getString("id") }
            val nodes = needed.map { id ->
                val item = names[id]
                UserGraphEntity(id, item?.optString("name")?.takeIf { it.isNotBlank() } ?: id,
                    runCatching { EntityType.valueOf(item?.optString("type") ?: "CONCEPT") }.getOrDefault(EntityType.CONCEPT), now)
            }
            return GraphExtractionBatch(nodes, attributes, relationships)
        }
    }
}
