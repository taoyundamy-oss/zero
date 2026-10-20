package com.zero.v2.app.skills

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import org.json.JSONObject

/** Markdown is authoritative; index data is disposable. No executable content is run here. */
class SkillStore(private val root: File) {
    data class Document(val id: String, val version: String, val markdown: String, val modifiedAt: Long) {
        val status: String get() = JSONObject(markdown.substringAfter("<!-- zero-skill ").substringBefore(" -->"))
            .optString("status", "candidate")
    }
    companion object { const val PREFIX = "knowledge/.zero-skills/" }
    private fun path(id: String): File {
        require(id.matches(Regex("[a-z0-9][a-z0-9_-]{0,63}"))) { "skill_id_invalid" }
        root.mkdirs()
        return File(root, "$id.md").also { require(it.canonicalFile.parentFile == root.canonicalFile) }
    }
    @Synchronized fun read(id: String, version: String? = null): Document? {
        val live = path(id)
        val target = if (version == null) live else {
            require(version.matches(Regex("[0-9a-f]{64}"))) { "skill_version_invalid" }
            File(root, ".history/$id/$version.md").also {
                require(it.canonicalPath.startsWith(root.canonicalPath + File.separator))
            }
        }
        if (!target.isFile) return null
        require(target.length() <= 32768) { "skill_too_large" }
        val text = target.readText(Charsets.UTF_8)
        return Document(id, hash(text), text, target.lastModified())
    }
    @Synchronized fun list(): List<Document> = root.listFiles().orEmpty()
        .filter { it.isFile && it.extension == "md" }.sortedBy { it.name }.take(200)
        .mapNotNull { read(it.nameWithoutExtension) }

    @Synchronized fun write(id: String, expected: String, body: String, sourceTask: String, restore: String? = null): Document {
        val file = path(id); val old = read(id)
        require((old?.version ?: "") == expected) { "skill_version_conflict" }
        require(sourceTask.isNotBlank() && sourceTask.length <= 300 && "-->" !in sourceTask) { "skill_source_required" }
        val restored = restore?.let { requireNotNull(read(id, it)) { "skill_revision_missing" }.markdown.substringAfter("-->\n") }
        val content = restored ?: body
        require(listOf("用途", "触发条件", "前提", "工具", "步骤", "成功判据", "失败恢复").all { heading ->
            content.lineSequence().any { it.trim() == "## $heading" }
        }) { "skill_required_sections_missing" }
        val metadata = JSONObject().put("id", id).put("sourceTask", sourceTask).put("updatedAt", System.currentTimeMillis())
            .put("status", "candidate").put("previousVersion", old?.version.orEmpty())
        val markdown = "<!-- zero-skill $metadata -->\n$content"
        require(markdown.toByteArray(Charsets.UTF_8).size <= 32768) { "skill_too_large" }
        require(old != null || list().size < 200) { "skill_count_limit" }
        old?.let { archive(it) }
        atomic(file, markdown)
        return checkNotNull(read(id))
    }
    /** UI-only human attestation. No model tool exposes this transition. Edits always reset to candidate. */
    @Synchronized fun confirmVerification(id: String, expected: String, evidence: String): Document {
        val old = requireNotNull(read(id)) { "skill_not_found" }
        require(old.version == expected) { "skill_version_conflict" }
        require(evidence.isNotBlank() && evidence.length <= 2000 && "-->" !in evidence) { "skill_verification_evidence_required" }
        val metadata = JSONObject(old.markdown.substringAfter("<!-- zero-skill ").substringBefore(" -->"))
            .put("status", "user_confirmed").put("verificationEvidence", evidence)
            .put("verifiedVersion", old.version).put("verifiedAt", System.currentTimeMillis())
            .put("previousVersion", old.version)
        val markdown = "<!-- zero-skill $metadata -->\n" + old.markdown.substringAfter("-->\n")
        require(markdown.toByteArray(Charsets.UTF_8).size <= 32768) { "skill_too_large" }
        archive(old)
        atomic(path(id), markdown)
        return checkNotNull(read(id))
    }
    @Synchronized fun delete(id: String, expected: String): Boolean {
        val old = read(id) ?: return false
        require(old.version == expected) { "skill_version_conflict" }
        archive(old)
        Files.delete(path(id).toPath())
        return true
    }
    @Synchronized fun versions(id: String): List<String> {
        path(id)
        return File(root, ".history/$id").listFiles().orEmpty().filter { it.extension == "md" }
            .sortedByDescending { it.lastModified() }.map { it.nameWithoutExtension }
    }
    private fun archive(old: Document) {
        val file = File(root, ".history/${old.id}/${old.version}.md")
        require(file.canonicalPath.startsWith(root.canonicalPath + File.separator))
        if (!file.exists()) {
            require(versions(old.id).size < 100) { "skill_history_limit" }
            atomic(file, old.markdown)
        }
    }
    private fun atomic(file: File, content: String) {
        file.parentFile!!.mkdirs()
        val temp = Files.createTempFile(file.parentFile!!.toPath(), ".skill-", ".tmp")
        try {
            java.io.FileOutputStream(temp.toFile()).use { it.write(content.toByteArray(Charsets.UTF_8)); it.fd.sync() }
            Files.move(temp, file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { Files.deleteIfExists(temp) }
    }
    private fun hash(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}
