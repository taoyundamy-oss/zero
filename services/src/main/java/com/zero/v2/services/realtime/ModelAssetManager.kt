package com.zero.v2.services.realtime

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * 模型资产管理器 — v5.0 模块化版本。
 *
 * 按模块独立提取（asr/tts/vad），互不阻塞。
 * sherpa-onnx JAR 无 AssetManager 构造器 → 必须复制到可写路径。
 */
object ModelAssetManager {

    private const val ASSET_BASE = "models"
    private const val INTERNAL_BASE = "sherpa"
    private const val MARKER_NAME = ".asset_sync"

    data class AsrPaths(val encoder: String, val decoder: String, val joiner: String, val tokens: String)
    /** v5.7 本地 TTS = vits-melo-tts-zh_en（VITS 架构，官方包：model.onnx/tokens.txt/lexicon.txt/dict//espeak-ng-data/） */
    data class MeloPaths(val model: String, val tokens: String, val lexicon: String?, val dataDir: String, val dictDir: String?)
    data class VadPaths(val model: String)

    /** ASR 模型路径（首次调用时提取，后续直接返回） */
    suspend fun getAsrPaths(context: Context): AsrPaths? = withContext(Dispatchers.IO) {
        val base = File(context.filesDir, "$INTERNAL_BASE/asr")
        if (!base.exists() || base.listFiles()?.isEmpty() == true) {
            copyAssetDir(context, "$ASSET_BASE/asr", base)
        }
        val enc = File(base, "encoder.onnx")
        if (!enc.exists()) return@withContext null
        AsrPaths(
            encoder = enc.absolutePath,
            decoder = File(base, "decoder.onnx").absolutePath,
            joiner = File(base, "joiner.onnx").absolutePath,
            tokens = File(base, "tokens.txt").absolutePath
        )
    }

    /** Melo（本地 TTS）模型路径 — assets/models/tts_melo/（官方 vits-melo-tts-zh_en 包内容） */
    suspend fun getMeloPaths(context: Context): MeloPaths? = withContext(Dispatchers.IO) {
        val base = File(context.filesDir, "$INTERNAL_BASE/tts_melo")
        // 资产一致性同步：APK 是模型的唯一来源（D49 内置）。逐文件校验大小，
        // 被外部替换或标记缺失 → 整目录重建
        syncAssetDir(context, "$ASSET_BASE/tts_melo", base)
        val model = File(base, "model.onnx")
        if (!model.exists()) return@withContext null
        MeloPaths(
            model = model.absolutePath,
            tokens = File(base, "tokens.txt").absolutePath,
            lexicon = File(base, "lexicon.txt").takeIf { it.exists() }?.absolutePath,
            dataDir = File(base, "espeak-ng-data").takeIf { it.isDirectory }?.absolutePath ?: "",
            dictDir = File(base, "dict").takeIf { it.isDirectory }?.absolutePath
        )
    }

    /** VAD 模型路径 */
    suspend fun getVadPaths(context: Context): VadPaths? = withContext(Dispatchers.IO) {
        val base = File(context.filesDir, "$INTERNAL_BASE/vad")
        if (!base.exists()) copyAssetDir(context, "$ASSET_BASE/vad", base)
        val model = File(base, "silero_vad.onnx")
        if (!model.exists()) return@withContext null
        VadPaths(model.absolutePath)
    }

    /** 检查某模块是否已提取（不触发复制） */
    fun isModuleReady(context: Context, module: String): Boolean {
        val base = File(context.filesDir, "$INTERNAL_BASE/$module")
        return when (module) {
            "asr" -> File(base, "encoder.onnx").exists()
            "tts_melo" -> File(base, "model.onnx").exists()
            "vad" -> File(base, "silero_vad.onnx").exists()
            else -> false
        }
    }

    /** 同步 assets 目录到 destDir，保证与 APK 内资产一致（被外部篡改即整目录重建） */
    private fun syncAssetDir(context: Context, assetPath: String, destDir: File) {
        val marker = File(destDir, MARKER_NAME)
        if (destDir.exists()) {
            if (marker.exists() && verifyMarker(destDir, marker)) return
            destDir.deleteRecursively()
        }
        copyAssetDir(context, assetPath, destDir)
        writeMarker(destDir, marker)
    }

    /** 逐行 "相对路径:大小" 校验设备端文件与提取时一致 */
    private fun verifyMarker(destDir: File, marker: File): Boolean = try {
        val lines = marker.readLines()
        lines.isNotEmpty() && lines.all { line ->
            val name = line.substringBeforeLast(':')
            val size = line.substringAfterLast(':').toLongOrNull() ?: return@all false
            File(destDir, name).length() == size
        }
    } catch (_: Exception) { false }

    private fun writeMarker(destDir: File, marker: File) {
        try {
            destDir.walkTopDown()
                .filter { it.isFile && it != marker }
                .joinToString("\n") { "${it.relativeTo(destDir).path}:${it.length()}" }
                .let { marker.writeText(it) }
        } catch (_: Exception) { marker.delete() }
    }

    /** 递归复制 assets 目录到内部存储 */
    private fun copyAssetDir(context: Context, assetPath: String, destDir: File) {
        try {
            val am = context.assets
            val files = am.list(assetPath) ?: return
            if (files.isEmpty()) return
            destDir.mkdirs()
            for (name in files) {
                val subPath = "$assetPath/$name"
                val subFiles = am.list(subPath)
                if (subFiles != null && subFiles.isNotEmpty()) {
                    copyAssetDir(context, subPath, File(destDir, name))
                } else {
                    val dest = File(destDir, name)
                    if (!dest.exists() || dest.length() == 0L) {
                        am.open(subPath).use { input ->
                            FileOutputStream(dest).use { output ->
                                input.copyTo(output, bufferSize = 64 * 1024)
                            }
                        }
                    }
                }
            }
        } catch (_: Exception) {}
    }
}
