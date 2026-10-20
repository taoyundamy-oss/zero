package com.zero.v2.framework.chat

import android.content.Context
import android.util.AtomicFile
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** Private no-backup snapshots; current Room sources are rechecked before every outgoing request. */
@Singleton
class MemoryContextStore @Inject constructor(@ApplicationContext private val context: Context) {
    private fun file(id: String): AtomicFile = AtomicFile(File(
        File(context.noBackupFilesDir, "memory-context").apply { mkdirs() }, GenerationProjection.digest(id) + ".json"))
    internal fun load(id: String): MemoryContextProjection {
        val raw = try { file(id).openRead().use { input ->
            val buffer = ByteArray(4096); val bytes = java.io.ByteArrayOutputStream()
            while (true) {
                val n = input.read(buffer); if (n < 0) break
                require(bytes.size() + n <= 100_000); bytes.write(buffer, 0, n)
            }
            bytes.toString("UTF-8")
        } } catch (_: java.io.IOException) { null } catch (_: IllegalArgumentException) { null }
        return MemoryContextProjection(raw)
    }
    internal fun save(id: String, projection: MemoryContextProjection) {
        val target = file(id); val output = target.startWrite()
        try { output.write(projection.encode().toByteArray(Charsets.UTF_8)); target.finishWrite(output) }
        catch (failure: Exception) { target.failWrite(output); throw failure }
    }
}
