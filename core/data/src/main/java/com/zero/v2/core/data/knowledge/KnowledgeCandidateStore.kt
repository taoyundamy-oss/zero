package com.zero.v2.core.data.knowledge

import com.zero.v2.core.data.entity.KbChunkEntity
import com.zero.v2.core.data.entity.KbDocEntity
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** Private derived data only. Callers serialize access; source documents are never written here. */
class KnowledgeCandidateStore(private val directory: File) {
    data class Job(val source: String, val baseline: String, val path: String?, val state: String)
    data class Candidate(val sourceHash: String, val doc: KbDocEntity, val chunks: List<KbChunkEntity>)

    fun job(): Job? {
        if (!File(directory, "job").exists()) return null
        return read("job") { Job(string(), string(), string().ifEmpty { null }, string()) }
    }

    fun begin(job: Job) {
        clear()
        save(job)
    }

    fun save(job: Job) = write("job") {
        string(job.source); string(job.baseline); string(job.path.orEmpty()); string(job.state)
    }

    fun candidate(index: Int): Candidate? {
        require(index in 0 until 5000)
        if (!File(directory, "$index.bin").exists()) return null
        return read("$index.bin") {
            val hash = string()
            val doc = KbDocEntity(string(), string(), string(), readLong(), readLong(), readInt(), readLong(), string())
            require(doc.chunkCount in 0..2000)
            val chunks = List(doc.chunkCount) {
                KbChunkEntity(string(), doc.id, readInt(), string(), string(), readInt(), updatedAt = readLong())
            }
            require(chunks.map { it.id }.distinct().size == chunks.size)
            require(chunks.withIndex().all { (i, c) -> c.seq == i && c.charLen == c.text.length })
            Candidate(hash, doc, chunks)
        }
    }

    fun put(index: Int, candidate: Candidate) = write("$index.bin") {
        require(index in 0 until 5000)
        val doc = candidate.doc
        require(doc.chunkCount == candidate.chunks.size && candidate.chunks.all { it.docId == doc.id })
        string(candidate.sourceHash)
        string(doc.id); string(doc.relativePath); string(doc.title); writeLong(doc.mtime); writeLong(doc.size)
        writeInt(doc.chunkCount); writeLong(doc.indexedAt); string(doc.tags)
        candidate.chunks.forEach {
            string(it.id); writeInt(it.seq); string(it.headingPath); string(it.text); writeInt(it.charLen); writeLong(it.updatedAt)
        }
    }

    /** Names are generated internally, never from SAF paths or manifest input. */
    fun clear() {
        directory.listFiles()?.filter { it.name == "job" || it.name == "pending" || it.name.matches(Regex("[0-9]+\\.bin")) }
            ?.forEach { check(it.delete()) { "candidate_cleanup_failed" } }
    }

    private fun write(name: String, block: DataOutputStream.() -> Unit) {
        check(directory.isDirectory || directory.mkdirs())
        val bytes = ByteArrayOutputStream().also { buffer ->
            DataOutputStream(buffer).use { it.writeInt(1); it.block() }
        }.toByteArray()
        require(bytes.size <= MAX_FILE_BYTES) { "candidate_file_limit" }
        val target = File(directory, name)
        val used = directory.listFiles().orEmpty().filter { it.name != "pending" }.sumOf { it.length() }
        require(used - target.length() + bytes.size + 32 <= MAX_DISK_BYTES) { "candidate_disk_limit" }
        val temporary = File(directory, "pending")
        FileOutputStream(temporary).use { out ->
            out.write(MessageDigest.getInstance("SHA-256").digest(bytes)); out.write(bytes); out.fd.sync()
        }
        Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    private fun <T> read(name: String, block: DataInputStream.() -> T): T {
        val file = File(directory, name)
        require(file.length() in 36..(MAX_FILE_BYTES + 32).toLong()) { "candidate_file_size" }
        val bytes = file.readBytes()
        val payload = bytes.copyOfRange(32, bytes.size)
        require(MessageDigest.isEqual(bytes.copyOfRange(0, 32), MessageDigest.getInstance("SHA-256").digest(payload))) {
            "candidate_checksum"
        }
        return DataInputStream(ByteArrayInputStream(payload)).use {
            require(it.readInt() == 1) { "candidate_version" }
            val value = it.block()
            require(it.available() == 0)
            value
        }
    }

    private fun DataOutputStream.string(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        writeInt(bytes.size); write(bytes)
    }

    private fun DataInputStream.string(): String {
        val size = readInt()
        require(size in 0..available())
        return ByteArray(size).also { readFully(it) }.toString(Charsets.UTF_8)
    }

    companion object {
        private const val MAX_FILE_BYTES = 24 * 1024 * 1024
        private const val MAX_DISK_BYTES = 128L * 1024 * 1024
        fun fingerprint(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}
