package com.zero.v2.services.llm

import com.zero.v2.services.embedding.EmbeddingPurpose
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EmbeddingRequestSpecTest {
    private val base = "https://dashscope.aliyuncs.com"
    private val compatible = "$base/compatible-mode/v1"
    @Test fun officialDashScopeQueriesCarryQueryPurpose() {
        val spec = EmbeddingRequestSpec.create(compatible,
            "text-embedding-v3", listOf("测试查询"), EmbeddingPurpose.QUERY)
        assertTrue(spec.native)
        assertEquals("$base/api/v1/services/embeddings/text-embedding/text-embedding", spec.url)
        assertEquals("query", spec.body.getJSONObject("parameters").getString("text_type"))
        assertEquals("测试查询", spec.body.getJSONObject("input").getJSONArray("texts").getString(0))
    }

    @Test fun customCompatibleProvidersKeepTheirSelectedEndpointAndBody() {
        val spec = EmbeddingRequestSpec.create("https://embeddings.example/v1/",
            "text-embedding-v3", listOf("query"), EmbeddingPurpose.QUERY)
        assertFalse(spec.native)
        assertEquals("https://embeddings.example/v1/embeddings", spec.url)
        assertFalse(spec.body.has("parameters"))
        assertEquals("query", spec.body.getJSONArray("input").getString(0))
    }

    @Test fun documentsAndQueriesHaveDistinctPurposesButSameVersionedSpace() {
        val url = compatible
        val doc = EmbeddingRequestSpec.create(url, "text-embedding-v3", listOf("document"), EmbeddingPurpose.DOCUMENT)
        assertEquals("document", doc.body.getJSONObject("parameters").getString("text_type"))
        assertEquals("$url|text-embedding-v3|dashscope-query-document:v1",
            EmbeddingRequestSpec.identity(url, "text-embedding-v3"))
        assertEquals("https://other.example/v1|text-embedding-v3",
            EmbeddingRequestSpec.identity("https://other.example/v1", "text-embedding-v3"))
    }

    @Test fun unofficialHostsPortsAndPathsCannotTriggerNativeRouting() {
        listOf("https://dashscope.aliyuncs.com.attacker.example/compatible-mode/v1",
            "$base:9000/compatible-mode/v1", "http://dashscope.aliyuncs.com/compatible-mode/v1",
            "https://dashscope.aliyuncs.com/other/v1").forEach {
            assertNull(EmbeddingRequestSpec.nativeUrl(it, "text-embedding-v3"))
        }
        assertNull(EmbeddingRequestSpec.nativeUrl(compatible, "unknown-model"))
    }

    @Test fun documentedWorkspaceKeepsSameHost() {
        val workspace = "https://ws-example.cn-beijing.maas.aliyuncs.com/api/v1"
        assertEquals("$workspace/services/embeddings/text-embedding/text-embedding",
            EmbeddingRequestSpec.nativeUrl(workspace, "text-embedding-v3"))
    }
}
