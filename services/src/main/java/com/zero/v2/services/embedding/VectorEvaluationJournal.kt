package com.zero.v2.services.embedding

import android.content.Context
import android.util.AtomicFile
import com.zero.v2.core.data.entity.VectorValidationEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import org.json.JSONObject

/** One atomic, bounded diagnostic receipt per domain; never an approval source. */
@Singleton
class VectorEvaluationJournal internal constructor(private val directory: File?) {
    @Inject constructor(@ApplicationContext context: Context) :
        this(File(context.noBackupFilesDir, "vector-evaluation"))

    @Synchronized
    fun record(domain: VectorDomain, evidence: VectorValidationEntity, published: Boolean) {
        val root = directory ?: return
        check(root.isDirectory || root.mkdirs()) { "vector_report_directory_unavailable" }
        val value = JSONObject().put("versionId", evidence.versionId)
            .put("candidateDigest", evidence.candidateDigest).put("datasetVersion", evidence.datasetVersion)
            .put("datasetDigest", evidence.datasetDigest).put("published", published)
            .put("expectedSamples", evidence.expectedSamples).put("evaluatedSamples", evidence.evaluatedSamples)
            .put("baselineRecall", evidence.baselineRecall).put("candidateRecall", evidence.candidateRecall)
            .put("minimumRecall", evidence.minimumRecall).put("metrics", JSONObject(evidence.report))
        val bytes = value.toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= REPORT_BYTE_LIMIT)
        val file = AtomicFile(File(root, "${domain.key}.json"))
        val stream = file.startWrite()
        var completed = false
        try {
            stream.write(bytes)
            file.finishWrite(stream)
            completed = true
        } finally {
            if (!completed) file.failWrite(stream)
        }
    }

    @Synchronized
    fun latest(domain: VectorDomain): String? {
        val root = directory ?: return null
        val file = AtomicFile(File(root, "${domain.key}.json"))
        return try {
            file.openRead().use(::readBounded)
        } catch (_: java.io.FileNotFoundException) { null }
    }

    fun rejected(domain: VectorDomain, id: String, candidateDigest: String, datasetDigest: String,
        rankingPolicy: String = "vector-only-v1"): Boolean {
        val previous = latest(domain)?.let { JSONObject(it) } ?: return false
        val sameCandidate = previous.optString("versionId") == id &&
            previous.optString("candidateDigest") == candidateDigest
        val metrics = previous.getJSONObject("metrics")
        return sameCandidate && previous.optString("datasetDigest") == datasetDigest &&
            metrics.optString("rankingPolicy", "vector-only-v1") == rankingPolicy &&
            !metrics.optBoolean("retryable") && !metrics.getBoolean("accepted")
    }

    private fun readBounded(input: java.io.InputStream): String {
        val bytes = ByteArray(REPORT_BYTE_LIMIT + 1)
        var size = 0
        while (size < bytes.size) {
            val count = input.read(bytes, size, bytes.size - size)
            if (count < 0) break
            size += count
        }
        check(size <= REPORT_BYTE_LIMIT) { "vector_report_too_large" }
        return String(bytes, 0, size, Charsets.UTF_8).also { JSONObject(it) }
    }

    private companion object { const val REPORT_BYTE_LIMIT = 128 * 1024 }
}
