package com.example.hamkit.data.ai

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.charset.StandardCharsets

/**
 * 会话历史的加密文件存储。
 *
 * 磁盘布局（全部 AES-GCM 加密，密钥在 Android Keystore）：
 * ```
 * filesDir/ai_sessions/index.enc     // 摘要索引，AAD = "index"
 * filesDir/ai_sessions/{id}.enc      // 单个会话，AAD = 会话 id
 * ```
 *
 * 设计要点：
 * - **不引入 Room 迁移**：会话结构在迭代期变化频繁，JSON 文件更灵活；
 * - **原子写**：先写 `.tmp` 再 rename，避免流式保存中断留下半文件；
 * - **解密失败按空处理**：换机、清数据、密钥被删时不能让历史页崩溃。
 */
class AiSessionStore(context: Context) {

    private val dir = File(context.applicationContext.filesDir, DIR_NAME)
    private val mutex = Mutex()

    suspend fun summaries(): List<AiSessionSummary> = withContext(Dispatchers.IO) {
        mutex.withLock {
            readSummariesLocked()
        }
    }

    suspend fun load(id: String): AiSession? = withContext(Dispatchers.IO) {
        mutex.withLock {
            val file = sessionFile(id)
            if (!file.isFile) return@withLock null
            val plain = decryptFile(file, id) ?: return@withLock null
            AiSessionCodec.decode(plain)
        }
    }

    suspend fun save(session: AiSession) {
        withContext(Dispatchers.IO) {
            mutex.withLock {
                runCatching {
                dir.mkdirs()
                val json = AiSessionCodec.encode(session)
                writeEncrypted(sessionFile(session.id), json, session.id)

                // 更新索引：同 id 替换，按更新时间倒序，最多 20 个会话
                val current = readSummariesLocked()
                val merged = (current.filterNot { it.id == session.id } + AiSessionSummary(
                    id = session.id,
                    title = session.title,
                    updatedAt = session.updatedAt,
                    messageCount = session.messages.size,
                ))
                    .sortedByDescending { it.updatedAt }
                    .take(MAX_SESSIONS)
                    writeEncrypted(indexFile, AiSessionCodec.encodeSummaries(merged), INDEX_AAD)
                }
            }
        }
    }

    suspend fun delete(id: String) {
        withContext(Dispatchers.IO) {
            mutex.withLock {
                runCatching {
                    sessionFile(id).delete()
                    val remaining = readSummariesLocked().filterNot { it.id == id }
                    writeEncrypted(indexFile, AiSessionCodec.encodeSummaries(remaining), INDEX_AAD)
                }
            }
        }
    }

    suspend fun clear() {
        withContext(Dispatchers.IO) {
            mutex.withLock {
                runCatching {
                    dir.listFiles()?.forEach { it.delete() }
                }
            }
        }
    }

    // ── 内部实现 ───────────────────────────────────────────────────────────

    private fun readSummariesLocked(): List<AiSessionSummary> {
        if (!indexFile.isFile) return emptyList()
        val plain = decryptFile(indexFile, INDEX_AAD) ?: return emptyList()
        return AiSessionCodec.decodeSummaries(plain)
    }

    private fun sessionFile(id: String): File = File(dir, "$id.enc")

    private val indexFile: File get() = File(dir, "index.enc")

    private fun decryptFile(file: File, aad: String): String? {
        val blob = runCatching { file.readBytes() }.getOrNull() ?: return null
        val plain = AiCrypto.decrypt(HISTORY_ALIAS, blob, aad.toByteArray(StandardCharsets.UTF_8))
            ?: return null
        return String(plain, StandardCharsets.UTF_8)
    }

    private fun writeEncrypted(file: File, plain: String, aad: String) {
        file.parentFile?.mkdirs()
        val blob = AiCrypto.encrypt(
            HISTORY_ALIAS,
            plain.toByteArray(StandardCharsets.UTF_8),
            aad.toByteArray(StandardCharsets.UTF_8),
        )
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeBytes(blob)
        if (!tmp.renameTo(file)) {
            // 少数文件系统不允许覆盖式 rename：先删再重命名，仍失败则抛给上层
            file.delete()
            check(tmp.renameTo(file)) { "无法写入 ${file.name}" }
        }
    }

    companion object {
        private const val DIR_NAME = "ai_sessions"
        private const val HISTORY_ALIAS = "hamkit_ai_history_v1"
        private const val INDEX_AAD = "index"
        private const val MAX_SESSIONS = 20
    }
}
