package com.zero.v2.app.acceptance

import android.os.Debug
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zero.v2.app.data.WorkDirRepository
import com.zero.v2.app.knowledge.KnowledgeModule
import com.zero.v2.app.knowledge.KnowledgeSource
import com.zero.v2.core.bus.ChatBusImpl
import com.zero.v2.core.bus.SystemBusImpl
import com.zero.v2.core.data.db.ZeroDatabase
import com.zero.v2.core.data.entity.*
import com.zero.v2.core.data.knowledge.KnowledgeCandidateStore
import com.zero.v2.core.trace.AgentTraceManager
import com.zero.v2.framework.memory.*
import com.zero.v2.services.embedding.EmbeddingProvider
import com.zero.v2.services.embedding.VectorIndexWorker
import com.zero.v2.services.llm.ModelSelector
import com.zero.v2.services.retrieval.BoundedSemanticReranker
import java.io.File
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith

/** Isolated production tools; optional real providers build only the synthetic database indexes. */
@RunWith(AndroidJUnit4::class)
class ScaleRecallAcceptanceTest {
    @Test fun offlineScaleBaseline() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val size = InstrumentationRegistry.getArguments().getString("corpusSize")!!.toInt()
        val grouped = InstrumentationRegistry.getArguments().getString("knowledgeLayout") == "grouped"
        val repetitions = InstrumentationRegistry.getArguments().getString("repetitions")?.toInt()?.coerceIn(1, 5) ?: 1
        val real = InstrumentationRegistry.getArguments().getString("allowRealProvider") == "true"
        val fusionOnly = InstrumentationRegistry.getArguments().getString("fusionOnly") == "true"
        val phased = InstrumentationRegistry.getArguments().getString("resourcePhases") == "true"
        val idle = InstrumentationRegistry.getArguments().getString("resourceIdle") == "true"
        val extendedIdle = InstrumentationRegistry.getArguments().getString("resourceExtendedIdle") == "true"
        val diagnostics = InstrumentationRegistry.getArguments().getString("resourceDiagnostics") == "true"
        val domainProbe = InstrumentationRegistry.getArguments().getString("resourceDomainProbe") == "true"
        check(!diagnostics || phased) { "diagnostics_require_resource_phases" }
        check(!domainProbe || diagnostics) { "domain_probe_requires_diagnostics" }
        val runTag = InstrumentationRegistry.getArguments().getString("runTag") ?: "first"
        check(runTag in setOf("first", "repeat"))
        check(!fusionOnly || grouped) { "fusion_fixture_requires_grouped" }
        check(!phased || !real) { "resource_fixture_requires_offline" }
        check(!idle || phased) { "idle_fixture_requires_resource_phases" }
        check(!extendedIdle || (idle && !real)) { "extended_idle_is_offline_diagnostic_only" }
        val entry = if (real) dagger.hilt.android.EntryPointAccessors.fromApplication(context, ProviderAcceptanceEntryPoint::class.java) else null
        entry?.chatProfiles()?.refreshProfiles()
        val budget = if (real) RecallRequestBudget(File(context.filesDir, "acceptance-a4-authorized-budget.json")) {
            entry!!.llmClient().usageTotal.value.inputTokens
        } else null
        val corpus = ScaleRecallFixture.corpus(size)
        fun docId(index: Int) = if (grouped && index >= ScaleRecallFixture.sources.size)
            "noise-${(index - ScaleRecallFixture.sources.size) / 25}" else "row-$index"
        val byText = corpus.indices.associateBy { corpus[it].text }
        val mapping = corpus.indices.joinToString { docId(it) + ":" +
            if (it >= ScaleRecallFixture.sources.size) "巡检档案" else corpus[it].title }
        val digest = if (grouped) java.security.MessageDigest.getInstance("SHA-256")
            .digest((ScaleRecallFixture.digest(size) + mapping).toByteArray()).joinToString("") { "%02x".format(it) }
            else ScaleRecallFixture.digest(size)
        val report = JSONObject().put("dataset", ScaleRecallFixture.VERSION).put("digest", ScaleRecallFixture.digest(size))
            .put("digest", digest).put("knowledgeLayout", if (grouped) "grouped-v2" else "flat-v1")
            .put("knowledgeDocuments", corpus.indices.map(::docId).distinct().size).put("repetitions", repetitions)
            .put("size", size).put("scope", if (real) "real_provider_isolated_production_handlers" else "offline_production_handlers_no_vector_no_cloud")
            .put("device", android.os.Build.MODEL).put("sdk", android.os.Build.VERSION.SDK_INT)
            .put("memoryMetric", "sampled_java_heap_plus_native_not_process_peak")
        val rows = JSONArray(); report.put("samples", rows).put("completed", false)
        val output = File(context.filesDir, if (phased) "acceptance-a4-phased-$runTag-$size.json"
            else if (fusionOnly && real) "acceptance-a4-semantic-fusion-$size.json"
            else if (fusionOnly) "acceptance-a4-fusion-$runTag-$size.json"
            else "acceptance-a4-${if (real) "real" else if (grouped) "representative" else "offline"}-$size.json")
        report.put("pipeline", if (fusionOnly) "production_evidence_provider" else "production_tools")
            .put("runTag", runTag)
        val db = Room.inMemoryDatabaseBuilder(context, ZeroDatabase::class.java).build()
        val root = File(context.cacheDir, "scale-${java.util.UUID.randomUUID()}")
        val offline = object : EmbeddingProvider {
            override val modelId = "offline"
            override suspend fun isAvailable() = false
            override suspend fun embed(text: String): FloatArray? = null
            override suspend fun embedBatch(texts: List<String>) = texts.map { null }
        }
        val responseCache = if (real) AcceptanceResponseCache(File(context.noBackupFilesDir, "acceptance-a4-response-cache-v1")) else null
        val provider = if (real) responseCache!!.provider(budget!!.provider(entry!!.embeddingProvider())) else offline
        val selector = ModelSelector(); val trace = AgentTraceManager(db.agentTraceEventDao())
        entry?.modelSelector()?.getAll()?.forEach { selector.register(responseCache!!.model(budget!!.model(it))) }
        val ranker = BoundedSemanticReranker(selector)
        val worker = VectorIndexWorker(db.vectorIndexDao(), provider,
            com.zero.v2.services.embedding.VectorEvaluationJournal(object : android.content.ContextWrapper(context) {
                override fun getNoBackupFilesDir(): File = root
            }),
            if (real) com.zero.v2.services.embedding.VectorBenchmarkGate(provider, ranker) else null)
        val memory = MemoryModule(db.memoryDao(), db.userGraphDao(), trace, provider,
            MemoryScoringEngine(), db.memoryExtractionJobDao(), worker, ranker, com.zero.v2.framework.memory.MemoryClassificationTools(db.chronicleSourceDao(), db.memoryDerivationDao(), com.zero.v2.core.data.store.MemoryDerivationStore(db)), com.zero.v2.core.data.store.MemoryDerivationStore(db))
        val source = object : KnowledgeSource {
            override fun identity() = "scale-fixture"
            override fun hasDirectory() = true
            override suspend fun createFolder(path: String): String? = error("read only")
            override suspend fun listTree() = WorkDirRepository.TreeListing(emptyList(), false)
            override suspend fun readText(path: String): String? = null
        }
        val knowledge = KnowledgeModule(source, db.kbDao(), SystemBusImpl(), provider, trace,
            KnowledgeCandidateStore(root), worker, ranker)
        knowledge.config.hybridEmbedding = real
        if (grouped) check(insertedDocCount(corpus.size) <= knowledge.config.maxDocs)
        var sampledPeak = 0L
        var sampledPss = 0
        val phase = java.util.concurrent.atomic.AtomicReference("seed")
        val phasePeaks = java.util.concurrent.ConcurrentHashMap<String, LongArray>()
        fun memorySnapshot(): JSONObject {
            val info = Debug.MemoryInfo(); Debug.getMemoryInfo(info)
            val runtime = Runtime.getRuntime()
            return JSONObject().put("javaHeapBytes", runtime.totalMemory() - runtime.freeMemory())
                .put("javaCommittedBytes", runtime.totalMemory()).put("memoryStats", JSONObject(info.memoryStats))
                .put("artStats", JSONObject(Debug.getRuntimeStats()))
                .put("nativeAllocatedBytes", Debug.getNativeHeapAllocatedSize()).put("pssKb", info.totalPss)
                .put("nativeHeapSizeBytes", Debug.getNativeHeapSize())
                .put("nativeHeapFreeBytes", Debug.getNativeHeapFreeSize())
                .put("javaHeapLimitBytes", runtime.maxMemory())
        }
        suspend fun retainedSnapshot(): JSONObject {
            phase.set("quiescence")
            System.gc()
            delay(300)
            return memorySnapshot()
        }
        val monitor = launch(Dispatchers.Default) {
            while (isActive) {
                val runtime = Runtime.getRuntime()
                val heap = runtime.totalMemory() - runtime.freeMemory() + Debug.getNativeHeapAllocatedSize()
                sampledPeak = maxOf(sampledPeak, heap)
                if (grouped) {
                    val info = Debug.MemoryInfo(); Debug.getMemoryInfo(info)
                    sampledPss = maxOf(sampledPss, info.totalPss)
                    if (phased) {
                        val values = phasePeaks.getOrPut(phase.get()) { longArrayOf(0, 0, 0) }
                        values[0] = maxOf(values[0], heap); values[1] = maxOf(values[1], info.totalPss.toLong())
                        values[2]++
                    }
                }
                delay(if (grouped) 250 else 20)
            }
        }
        try {
            output.writeText(report.toString())
            val insertedDocs = mutableSetOf<String>()
            corpus.chunked(100).forEachIndexed { batch, items ->
                val start = batch * 100
                db.memoryDao().insertAll(items.mapIndexed { i, row -> MemoryEntity("row-${start + i}", row.text, createdAt = (start + i).toLong()) })
                items.forEachIndexed { i, row ->
                    val id = "row-${start + i}"
                    val doc = docId(start + i)
                    val noise = grouped && start + i >= ScaleRecallFixture.sources.size
                    val seq = if (noise) (start + i - ScaleRecallFixture.sources.size) % 25 else 0
                    val count = if (noise) minOf(25, size - ScaleRecallFixture.sources.size - ((start + i - ScaleRecallFixture.sources.size) / 25) * 25) else 1
                    if (insertedDocs.add(doc)) db.kbDao().insertDoc(KbDocEntity(doc, "$doc.md", if (noise) "巡检档案" else row.title, 0, 0, count, 0))
                    db.kbDao().insertChunks(listOf(KbChunkEntity(id, doc, seq, headingPath = "", text = row.text, charLen = row.text.length, updatedAt = 0)))
                }
            }
            if (phased) report.put("resourceProtocol", if (idle) "phased-v3-requested-gc-idle-5s"
                else "phased-v2-deferred-report-requested-gc")
                .put("retainedAfterSeed", retainedSnapshot())
            if (real) {
                val builds = JSONObject(); report.put("builds", builds)
                for (domain in com.zero.v2.services.embedding.VectorDomain.entries) {
                    builds.put(domain.key, worker.build(domain).name)
                    report.put("gate-${domain.key}", worker.evaluationReport(domain)?.let { JSONObject(it) } ?: JSONObject.NULL)
                    val version = db.vectorIndexDao().versions(domain.key).firstOrNull()
                    report.put("candidateEntries-${domain.key}", version?.let { db.vectorIndexDao().countEntries(it.id) } ?: 0)
                    report.put("budget", budget!!.snapshot()); output.writeText(report.toString(2))
                    check(builds.getString(domain.key) == "CURRENT") { "scale_vector_build_incomplete" }
                }
            }
            val handlers = if (fusionOnly) emptyList() else listOf(
                "memory" to memory.contributions().single { it.definition.name == "memory_recall" }.handler,
                "knowledge" to knowledge.contributions().single { it.definition.name == "kb_search" }.handler)
            for ((domain, handler) in handlers) {
                ScaleRecallFixture.samples.forEachIndexed { index, sample ->
                    for (k in listOf(1, 3)) {
                        if (repetitions > 1) handler(mapOf("query" to sample.query, "top_k" to k))
                        repeat(repetitions) { repetition ->
                        val started = System.nanoTime()
                        val result = withTimeout(30_000) { handler(mapOf("query" to sample.query, "top_k" to k)) }
                        val elapsedMs = (System.nanoTime() - started) / 1_000_000.0
                        val returned = corpus.indices.filter { result.data.contains(corpus[it].text) }.toSet()
                        val row = JSONObject().put("domain", domain).put("sample", index).put("kind", sample.kind).put("k", k).put("repetition", repetition)
                            .put("success", result.success).put("recall", returned.intersect(sample.relevant).size.toDouble() / sample.relevant.size)
                            .put("forbiddenHits", returned.intersect(sample.forbidden).size)
                            .put("unrelatedHits", (returned - sample.relevant - sample.forbidden).size)
                            .put("sourceExactness", JSONObject.NULL)
                            .put("returnedSources", JSONArray(returned.sorted())).put("latencyMs", elapsedMs)
                        if (domain == "knowledge") {
                            val citations = result.data.lineSequence().filter { it.startsWith("[knowledge/") }.toList()
                            row.put("citationCount", citations.size).put("validCitations", citations.count { line ->
                                val id = byText[line.substringAfter("] ")]
                                id != null && line.startsWith("[knowledge/${docId(id)}.md] ")
                            })
                        }
                        if (domain == "memory") {
                            val citations = result.data.lineSequence().filter { it.startsWith("- [memory/") }.toList()
                            row.put("citationCount", citations.size).put("validCitations", citations.count { line ->
                                val id = line.substringAfter("- [memory/row-").substringBefore("]").toIntOrNull()
                                id != null && id in corpus.indices && line.endsWith(corpus[id].text.take(200))
                            })
                            val evidence = memory.evidenceCandidates(sample.query, k)
                            val found = evidence.map { it.id.removePrefix("row-").toInt() }.toSet()
                            row.put("evidenceRecall", found.intersect(sample.relevant).size.toDouble() / sample.relevant.size)
                                .put("evidenceForbidden", found.intersect(sample.forbidden).size)
                        }
                        if (row.optInt("citationCount") > 0) row.put("sourceExactness",
                            row.getInt("validCitations").toDouble() / row.getInt("citationCount"))
                        if (real) report.put("budget", budget!!.snapshot()).put("realResponseCacheHits", responseCache!!.hits)
                            .put("realResponseCacheMisses", responseCache.misses)
                        rows.put(row); output.writeText(report.toString())
                        }
                    }
                }
            }
            if (fusionOnly) {
                val warmups = JSONArray(); report.put("warmups", warmups)
                val evidenceProvider = createEvidenceProvider(memory, knowledge, if (real) worker else null,
                    if (real) ranker else null)
                ScaleRecallFixture.samples.forEachIndexed { index, sample ->
                    for (k in listOf(1, 3)) {
                        phase.set("warmup")
                        val warmStarted = System.nanoTime()
                        val initial = evidenceProvider.evidenceFor(sample.query, if (real) 3 else k)
                        val initialSources = initial.map { it.id.removePrefix("row-").toInt() }.toSet()
                        if (real) warmups.put(JSONObject().put("sample", index).put("beforeMeasuredK", k)
                            .put("latencyMs", (System.nanoTime() - warmStarted) / 1_000_000.0)
                            .put("recall3", initialSources.intersect(sample.relevant).size.toDouble() / sample.relevant.size)
                            .put("forbiddenHits", initialSources.intersect(sample.forbidden).size)
                            .put("unrelatedHits", (initialSources - sample.relevant - sample.forbidden).size)
                            .put("sourceKeys", JSONArray(initial.map { it.key() })))
                        repeat(repetitions) { repetition ->
                            phase.set("query")
                            val cpuBefore = if (diagnostics) android.os.Process.getElapsedCpuTime() else 0L
                            val gcBefore = if (diagnostics) Debug.getRuntimeStat("art.gc.gc-time").toLong() else 0L
                            val gcCountBefore = if (diagnostics) Debug.getRuntimeStat("art.gc.gc-count").toLong() else 0L
                            val allocatedBefore = if (phased) Debug.getRuntimeStat("art.gc.bytes-allocated").toLong() else 0L
                            val started = System.nanoTime()
                            val refs = withTimeout(if (real) 7_000 else 3_000) { evidenceProvider.evidenceFor(sample.query, k) }
                            val elapsed = (System.nanoTime() - started) / 1_000_000.0
                            val queryAllocated = if (phased) Debug.getRuntimeStat("art.gc.bytes-allocated").toLong() - allocatedBefore else 0L
                            val cpuMs = if (diagnostics) android.os.Process.getElapsedCpuTime() - cpuBefore else 0L
                            val gcMs = if (diagnostics) Debug.getRuntimeStat("art.gc.gc-time").toLong() - gcBefore else 0L
                            val gcCount = if (diagnostics) Debug.getRuntimeStat("art.gc.gc-count").toLong() - gcCountBefore else 0L
                            phase.set("report")
                            val found = refs.map { it.id.removePrefix("row-").toInt() }.toSet()
                            val valid = refs.count { ref ->
                                val id = ref.id.removePrefix("row-").toInt()
                                val source = corpus.getOrNull(id)
                                source != null && ref.content.endsWith(source.text.take(200)) &&
                                    ref.sourceRef == if (ref.domain == com.zero.v2.core.common.EvidenceDomain.MEMORY)
                                        "memory" else "knowledge/${docId(id)}.md"
                            }
                            val block = com.zero.v2.services.retrieval.EvidencePolicy.formatBlock(refs)
                            check(refs.size <= k && refs.map { it.key() }.distinct().size == refs.size)
                            check((block?.length ?: 0) <= com.zero.v2.services.retrieval.EvidencePolicy.BLOCK_MAX_CHARS)
                            rows.put(JSONObject().put("domain", "fused").put("sample", index).put("kind", sample.kind)
                                .put("k", k).put("repetition", repetition).put("latencyMs", elapsed)
                                .put("recall", found.intersect(sample.relevant).size.toDouble() / sample.relevant.size)
                                .put("forbiddenHits", found.intersect(sample.forbidden).size)
                                .put("unrelatedHits", (found - sample.relevant - sample.forbidden).size)
                                .put("citationCount", refs.size).put("validCitations", valid)
                                .put("duplicateContentSlots", refs.size - found.size)
                                .put("blockChars", block?.length ?: 0)
                                .put("sourceKeys", JSONArray(refs.map { it.key() })))
                            if (phased) rows.getJSONObject(rows.length() - 1).put("queryProcessAllocatedBytes", queryAllocated)
                            if (diagnostics) rows.getJSONObject(rows.length() - 1)
                                .put("queryProcessCpuMs", cpuMs)
                                .put("queryGcTimeMs", gcMs)
                                .put("queryGcCount", gcCount)
                            if (real) report.put("budget", budget!!.snapshot())
                                .put("completionObservations", responseCache!!.completionObservations())
                            if (!phased) output.writeText(report.toString())
                        }
                    }
                }
            }
            report.put("sampledHeapPeakBytes", sampledPeak).put("completed", true)
            if (phased) {
                report.put("retainedAfterQueries", retainedSnapshot())
                monitor.cancelAndJoin()
                if (idle) {
                    delay(5_000)
                    report.put("retainedAfterIdle", memorySnapshot())
                        .put("idleNote", "5 seconds after requested GC snapshot; no additional GC; outside query timing")
                    if (extendedIdle) {
                        delay(55_000)
                        report.put("retainedAfterExtendedIdle", memorySnapshot())
                            .put("extendedIdleNote", "Diagnostic only: 60 seconds after requested GC; no trim or additional GC; original 5s gate preserved")
                    }
                }
                val phases = JSONObject()
                phasePeaks.forEach { (name, values) -> phases.put(name, JSONObject()
                    .put("sampledHeapPeakBytes", values[0]).put("sampledPssPeakKb", values[1]).put("samples", values[2])) }
                report.put("phasePeaks", phases).put("resourceNote", "250ms sampled phases; requested GC outside query timing; no exact peak")
            }
            if (grouped) report.put("sampledPssPeakKb", sampledPss)
            if (real) report.put("budget", budget!!.snapshot()).put("realResponseCacheHits", responseCache!!.hits)
                .put("realResponseCacheMisses", responseCache.misses)
            if (domainProbe) {
                val domainRows = JSONArray()
                ScaleRecallFixture.samples.forEachIndexed { index, sample ->
                    for (domain in listOf("memory", "knowledge")) {
                        val cpuBefore = android.os.Process.getElapsedCpuTime()
                        val started = System.nanoTime()
                        val refs = withTimeout(3_000) {
                            if (domain == "memory") memory.evidenceCandidates(sample.query, 6)
                            else knowledge.evidenceCandidates(sample.query, 6)
                        }
                        domainRows.put(JSONObject().put("sample", index).put("domain", domain)
                            .put("latencyMs", (System.nanoTime() - started) / 1_000_000.0)
                            .put("processCpuMs", android.os.Process.getElapsedCpuTime() - cpuBefore)
                            .put("sourceKeys", JSONArray(refs.map { it.key() })))
                    }
                }
                report.put("domainDiagnostics", domainRows)
                    .put("domainProbeScope", "sequential warm domain reads after final resource snapshots; monitor stopped; not fusion timing")
            }
            output.writeText(report.toString(2))
            streamAcceptanceReport(report)
        } finally { monitor.cancelAndJoin(); memory.onDestroy(); knowledge.onDestroy(); db.close(); root.deleteRecursively() }
    }

    /** Baseline APK has the original two-argument seam; production uses its own installed implementation. */
    private fun createEvidenceProvider(
        memory: MemoryModule, knowledge: KnowledgeModule,
        worker: VectorIndexWorker?, ranker: BoundedSemanticReranker?,
    ): com.zero.v2.services.retrieval.EvidenceProvider {
        if (worker == null) {
            val legacy = com.zero.v2.app.retrieval.EvidenceProviderImpl::class.java.constructors
                .firstOrNull { it.parameterTypes.contentEquals(arrayOf(MemoryModule::class.java, KnowledgeModule::class.java)) }
            if (legacy != null) return legacy.newInstance(memory, knowledge) as com.zero.v2.services.retrieval.EvidenceProvider
        }
        return com.zero.v2.app.retrieval.EvidenceProviderImpl(memory, knowledge, worker, ranker)
    }

    private fun insertedDocCount(size: Int): Int = ScaleRecallFixture.sources.size +
        (size - ScaleRecallFixture.sources.size + 24) / 25
}

