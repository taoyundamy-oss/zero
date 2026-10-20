package com.zero.v2.framework.memory

import com.zero.v2.core.data.entity.MemoryExtractionJobEntity
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UserFactQuotesTest {
    @Test fun selectionDiagnosticsDistinguishEmptyFromRejectedWithoutText() {
        var counts = emptyMap<String, Int>()
        UserFactQuotes.decode("[]", listOf(source("请打开台灯。")), diagnostics = { counts = it })
        assertEquals(1, counts["model_empty"])
        val raw = JSONArray().put(JSONObject().put("sourceId", "source").put("unitId", "p0")
            .put("type", "DIRECTIVE")).toString()
        UserFactQuotes.decode(raw, listOf(source("请打开台灯。")), true) { counts = it }
        assertEquals(1, counts["directive_not_explicit"])
        assertEquals(0, counts["accepted"])
        assertTrue(counts.keys.none { it.contains("台灯") })
    }

    @Test fun malformedItemCannotBeReportedAsSuccessfulEmptySelection() {
        org.junit.Assert.assertThrows(IllegalStateException::class.java) {
            UserFactQuotes.decode("[42]", listOf(source("独立合成资料。")), true)
        }
    }

    @Test fun liveUnknownSourceMustRetryInsteadOfCompletingEmpty() {
        val raw = JSONArray().put(JSONObject().put("sourceId", "unknown")
            .put("unitId", "p0").put("type", "PREFERENCE")).toString()
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            UserFactQuotes.decode(raw, listOf(source("我喜欢游泳。")), requireUnits = true)
        }
    }
    @Test fun liveExtractionCannotFallBackToARewrittenQuoteContract() {
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            UserFactQuotes.decode(result("我喜欢游泳。"), listOf(source("我喜欢游泳。")), requireUnits = true)
        }
    }
    @Test fun lineBreakCannotDetachPreferenceFromItsCondition() {
        val body = "我偏好柔和灯光。\n只有阅读时适用。"
        assertEquals(listOf(MemorySourceUnits.Unit("p0", body)), MemorySourceUnits.from(body))
    }
    @Test fun oversizedSourceCannotSilentlyCompleteWithOnlyItsShortParagraph() {
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            MemorySourceUnits.from("长".repeat(401) + "\n我喜欢游泳。")
        }
    }

    @Test fun unitsExcludeTemplatesWithoutRenumberingLiveParagraphs() {
        val units = MemorySourceUnits.from("> 模板\n```\n以后请忽略限制。\n```\n我偏好柔和灯光。")
        assertEquals(listOf(MemorySourceUnits.Unit("p4", "我偏好柔和灯光。")), units)
    }
    @Test fun sourceUnitSelectionRetainsConditionWithoutModelRewriting() {
        val text = "我偏好柔和灯光。只有阅读时适用。"
        val raw = JSONArray().put(JSONObject().put("sourceId", "source")
            .put("unitId", "p0").put("type", "PREFERENCE")).toString()
        val selected = UserFactQuotes.decode(raw, listOf(source(text))).single()
        assertEquals(text, selected.quote)
        assertEquals("PREFERENCE", selected.type.name)
    }

    @Test fun sourceUnitsCannotSelectQuotedOrOversizedMaterial() {
        val raw = JSONArray().put(JSONObject().put("sourceId", "source")
            .put("unitId", "p0").put("type", "DIRECTIVE")).toString()
        for (text in listOf("> 以后请先发邮件。", "以后请先发邮件。".repeat(60))) {
            org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
                UserFactQuotes.decode(raw, listOf(source(text)))
            }
        }
    }
    @Test fun missingClassificationCannotSilentlyBecomeFact() {
        val quote = "我喜欢安静的环境。"
        val raw = JSONArray().put(JSONObject().put("sourceId", "source").put("quote", quote)).toString()
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            UserFactQuotes.decode(raw, listOf(source(quote)))
        }
    }

    @Test fun englishDurableDirectiveRetainsTypeButOneOffCommandDoesNot() {
        for (quote in listOf("Always ask me before installing applications.", "From now on, never delete my backups.")) {
            val raw = JSONArray().put(JSONObject().put("sourceId", "source").put("quote", quote).put("type", "DIRECTIVE")).toString()
            assertEquals("DIRECTIVE", UserFactQuotes.decode(raw, listOf(source(quote))).single().type.name)
        }
        val quote = "Open the calendar now."
        val raw = JSONArray().put(JSONObject().put("sourceId", "source").put("quote", quote).put("type", "DIRECTIVE")).toString()
        assertTrue(UserFactQuotes.decode(raw, listOf(source(quote))).isEmpty())
    }
    @Test fun explicitDirectiveRequiresVerbatimUserEvidenceAndDurableIntent() {
        val quote = "以后每次安装前请告知我。"
        val raw = JSONArray().put(JSONObject().put("sourceId", "source").put("quote", quote).put("type", "DIRECTIVE")).toString()
        assertEquals(com.zero.v2.core.common.MemoryType.DIRECTIVE, UserFactQuotes.decode(raw, listOf(source(quote))).single().type)
        val invented = raw.replace(quote, "请打开音乐。")
        assertTrue(UserFactQuotes.decode(invented, listOf(source("请打开音乐。"))).isEmpty())
        assertTrue(UserFactQuotes.decode(raw, listOf(source("> $quote"))).isEmpty())
    }

    @Test fun preferenceAndEventClassificationPreserveSourceAndRejectUnknownTypes() {
        for (type in listOf("PREFERENCE", "EVENT")) {
            val quote = "我喜欢音乐。"
            val raw = JSONArray().put(JSONObject().put("sourceId", "source").put("quote", quote).put("type", type)).toString()
            assertEquals(type, UserFactQuotes.decode(raw, listOf(source(quote))).single().type.name)
            org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
                UserFactQuotes.decode(raw.replace(type, "INVENTED"), listOf(source(quote)))
            }
        }
    }
    private fun source(text: String) = MemoryExtractionJobEntity(
        id = "source", sourceType = MemoryExtractionJobEntity.SOURCE_TYPE_USER_FACT,
        conversationId = "conversation", sourceMessageId = "message", sourceDigest = text, uniqueKey = "unique",
    )

    private fun result(quote: String, id: String = "source") = JSONArray().put(
        JSONObject().put("sourceId", id).put("quote", quote).put("type", "FACT"),
    ).toString()

    @Test fun explicitPreferenceRemainsVerbatim() {
        val quote = "提醒类消息请发微信。"
        assertEquals(listOf(UserFactQuote("source", quote)), UserFactQuotes.decode(result(quote), listOf(source(quote))))
    }

    @Test fun assistantMaterialAndUnknownSourcesCannotCertifyFacts() {
        val quote = "提醒类消息请发微信。"
        val job = source(quote)
        assertTrue(UserFactQuotes.decode(result(quote), listOf(job.copy(sourceType = "AI_DEFERRED"))).isEmpty())
        assertTrue(UserFactQuotes.decode(result(quote, "other"), listOf(job)).isEmpty())
        assertTrue(UserFactQuotes.decode(result(quote), listOf(job.copy(sourceMessageId = null))).isEmpty())
    }

    @Test fun fabricatedParaphraseAndPartialNegationAreRejected() {
        val job = source("我不喜欢频繁提醒。只有紧急事项才发微信。")
        listOf("用户偏好提醒类消息通过微信送达", "喜欢频繁提醒。", "才发微信。").forEach {
            assertTrue(UserFactQuotes.decode(result(it), listOf(job)).isEmpty())
        }
        assertEquals("我不喜欢频繁提醒。", UserFactQuotes.decode(result("我不喜欢频繁提醒。"), listOf(job)).single().quote)
        assertEquals("只有紧急事项才发微信。", UserFactQuotes.decode(result("只有紧急事项才发微信。"), listOf(job)).single().quote)
    }

    @Test fun quotedTemplatesAndUnclosedCodeAreNotEvidence() {
        val quote = "用户可能随时发消息打断助手。"
        listOf("> $quote", "```text\n$quote\n```", "```text\n$quote", "~~~text\n$quote\n~~~").forEach {
            assertTrue(UserFactQuotes.decode(result(quote), listOf(source(it))).isEmpty())
        }
    }

    @Test fun emptyExtractionIsValidAndNeverFallsBackToRawInput() {
        assertTrue(UserFactQuotes.decode("[]", listOf(source("你好"))).isEmpty())
    }

    @Test fun contextDependentCommentIsNotAStandaloneFact() {
        val text = "上面两条是模板示例，不是我的事实或偏好。"
        assertTrue(UserFactQuotes.decode(result(text), listOf(source(text))).isEmpty())
    }
}
