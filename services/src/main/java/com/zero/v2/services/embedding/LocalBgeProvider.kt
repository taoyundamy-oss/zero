package com.zero.v2.services.embedding

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

@Singleton
class LocalBgeProvider @Inject constructor(@ApplicationContext context: Context) :
    EmbeddingProvider {
    private val root = File(context.filesDir, "local-embedding/bge-small-zh-v1.5")
    private val mutex = Mutex()
    private var session: OrtSession? = null
    private var tokenizer: BgeTokenizer? = null
    private var loadedIdentity: String? = null
    override val modelId = "local:bge-small-zh-v1.5"
    override val identity: String
        get() =
            "$modelId:cls-l2:case-sensitive-wordpiece-v1:symmetric:512:" +
                runCatching {
                        val f = File(root, "manifest.json")
                        require(f.length() in 1..MANIFEST_LIMIT)
                        sha(f)
                    }
                    .getOrDefault("unavailable")

    override suspend fun isAvailable(): Boolean =
        File(root, "manifest.json").isFile &&
            File(root, "model.onnx").isFile &&
            File(root, "vocab.txt").isFile

    override suspend fun embed(text: String): FloatArray? = embedBatch(listOf(text)).firstOrNull()

    override suspend fun embedBatch(texts: List<String>): List<FloatArray?> =
        texts.map { text ->
            coroutineContext.ensureActive()
            withContext(Dispatchers.Default) {
                mutex.withLock {
                    try {
                        val expected = identity
                        ensureSession(expected)
                    val ids = tokenizer!!.encode(text)
                        val env = OrtEnvironment.getEnvironment()
                        val tensors = mutableMapOf<String, OnnxTensor>()
                        try {
                            tensors["input_ids"] = OnnxTensor.createTensor(env, arrayOf(ids))
                            tensors["attention_mask"] =
                                OnnxTensor.createTensor(env, arrayOf(LongArray(ids.size) { 1 }))
                            tensors["token_type_ids"] =
                                OnnxTensor.createTensor(env, arrayOf(LongArray(ids.size)))
                            session!!.run(tensors).use { result ->
                                @Suppress("UNCHECKED_CAST")
                                val vector =
                                    (result.get("sentence_embedding").get().value
                                            as Array<FloatArray>)
                                        .single()
                                require(vector.size == DIMENSIONS && vector.all { it.isFinite() })
                                val norm = kotlin.math.sqrt(vector.sumOf { it.toDouble() * it })
                                require(norm > MIN_NORM && norm < MAX_NORM && identity == expected)
                                coroutineContext.ensureActive()
                                vector.copyOf()
                            }
                        } finally {
                            tensors.values.forEach { it.close() }
                        }
                    } catch (c: kotlinx.coroutines.CancellationException) {
                        throw c
                    } catch (_: Exception) {
                        null
                    } catch (_: LinkageError) {
                        null
                    }
                }
            }
        }

    private fun ensureSession(expected: String) {
                    if (session == null || loadedIdentity != expected) {
                            session?.close()
                            session = null
                            tokenizer = null
                            val manifestFile = File(root, "manifest.json")
                            require(manifestFile.length() in 1..MANIFEST_LIMIT)
                            val m = JSONObject(manifestFile.readText())
                            require(
                                m.getString("contract") ==
                                    "bge-zh-cls-l2-wordpiece-case-sensitive-symmetric-512-v1")
                            require(m.getString("modelSha256") == sha(File(root, "model.onnx")))
                            require(m.getString("vocabSha256") == sha(File(root, "vocab.txt")))
                            require(
                                File(root, "model.onnx").length() <= MODEL_LIMIT &&
                                    File(root, "vocab.txt").length() <= VOCAB_LIMIT)
                            tokenizer = BgeTokenizer(File(root, "vocab.txt").readLines())
                            OrtSession.SessionOptions().use { options ->
                                options.setIntraOpNumThreads(2)
                                options.setInterOpNumThreads(1)
                                session =
                                    OrtEnvironment.getEnvironment()
                                        .createSession(
                                            File(root, "model.onnx").absolutePath, options)
                            }
                            loadedIdentity = expected
                        }
        }

    private companion object {
        const val MANIFEST_LIMIT = 16384
        const val MODEL_LIMIT = 200_000_000
        const val VOCAB_LIMIT = 1_000_000
        const val DIMENSIONS = 512
        const val MIN_NORM = 0.99
        const val MAX_NORM = 1.01
        const val HASH_BUFFER = 65536
    }

    private fun sha(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val bytes = ByteArray(HASH_BUFFER)
            while (true) {
                val count = input.read(bytes)
                if (count < 0) {
                    break
                }
                digest.update(bytes, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
