package com.zero.v2.framework.chat

import android.content.Context
import android.util.AtomicFile
import com.zero.v2.core.common.FunctionDefinition
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.Properties
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ToolManifestStore @Inject constructor(@ApplicationContext private val context: Context) {
    private val memory = mutableMapOf<String, Pair<Long, List<FunctionDefinition>>>()
    var persistenceFailed: Boolean = false
        private set

    @Synchronized internal fun select(conversation: String, epoch: Long, catalog: List<FunctionDefinition>, pinned: Set<String>): List<FunctionDefinition> {
        val key = GenerationProjection.digest(conversation)
        memory[key]?.takeIf { it.first == epoch }?.let { return it.second }
        val stored = read(key)
        val restored = ToolManifestSnapshot.restore(stored, epoch)
        val recentState = read("recent")
        val recent = recentState.stringPropertyNames().associateWith { recentState.getProperty(it)?.toLongOrNull() ?: 0L }
        val tools = restored ?: ToolManifestSnapshot.choose(catalog, recent, pinned, System.currentTimeMillis())
        if (restored == null) write(key, ToolManifestSnapshot.checkpoint(epoch, tools))
        if (memory.size >= 32) memory.remove(memory.keys.first())
        memory[key] = epoch to tools
        return tools
    }

    @Synchronized internal fun successful(name: String) {
        val recent = read("recent")
        recent.setProperty(name, System.currentTimeMillis().toString())
        val keep = recent.stringPropertyNames().sortedByDescending { recent.getProperty(it).toLongOrNull() ?: 0 }.take(256).toSet()
        recent.stringPropertyNames().filterNot { it in keep }.forEach { recent.remove(it) }
        write("recent", recent)
    }

    private fun file(key: String): AtomicFile {
        val root = File(context.noBackupFilesDir, "tool-manifests").apply { mkdirs() }
        return AtomicFile(File(root, "$key.properties"))
    }

    private fun read(key: String): Properties = Properties().apply {
        try { file(key).openRead().use { load(it) } }
        catch (_: java.io.IOException) { clear() }
        catch (_: IllegalArgumentException) { clear() }
    }

    private fun write(key: String, state: Properties) {
        try {
            val target = file(key)
            val stream = target.startWrite()
            try { state.store(stream, "tool manifest v1"); target.finishWrite(stream) }
            catch (failure: Exception) { target.failWrite(stream); throw failure }
        } catch (_: java.io.IOException) { persistenceFailed = true }
    }
}
