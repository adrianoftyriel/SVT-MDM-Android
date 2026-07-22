package org.svt.mdm.backup

import android.content.Context
import android.util.Log
import java.security.MessageDigest
import java.time.Instant
import org.svt.mdm.transport.MdmApi
import org.svt.mdm.transport.StreamingRequestBody
import org.svt.mdm.transport.dto.BackupFileMeta
import org.svt.mdm.transport.dto.ManifestRequest
import org.svt.mdm.transport.dto.RunCompleteRequest

/**
 * Runs a backup: enumerate files, hash them (with a cache), ask the server
 * which it's missing, stream up the missing ones, and record the run.
 */
class BackupManager(private val context: Context, private val api: MdmApi) {

    private val enumerator = MediaEnumerator(context)
    private val shaCache = ShaCache(context)

    suspend fun run(): BackupSummary {
        val entries = enumerator.enumerate()
        val resolver = context.contentResolver

        // Compute (meta, entry) pairs, reusing cached hashes where possible.
        val items = entries.mapNotNull { entry ->
            val sha = runCatching { shaOf(entry) }.getOrNull() ?: return@mapNotNull null
            val meta = BackupFileMeta(
                sha256 = sha,
                size = entry.size,
                relPath = entry.relPath,
                category = entry.category,
                mtime = Instant.ofEpochMilli(entry.mtimeMs).toString(),
            )
            meta to entry
        }

        val runId = api.backupStart().runId

        // Which hashes does the server still need? (batched)
        val missing = HashSet<String>()
        items.map { it.first }.chunked(MANIFEST_BATCH).forEach { batch ->
            missing += api.backupManifest(ManifestRequest(batch)).missing
        }

        var uploaded = 0
        var totalBytes = 0L
        for ((meta, entry) in items) {
            totalBytes += meta.size
            if (meta.sha256 !in missing) continue
            try {
                api.backupUpload(
                    sha256 = meta.sha256,
                    path = meta.relPath,
                    category = meta.category,
                    body = StreamingRequestBody({ entry.open(resolver) }),
                )
                missing.remove(meta.sha256) // don't re-upload identical content this run
                uploaded++
            } catch (e: Exception) {
                Log.w(TAG, "Upload failed for ${entry.relPath}: ${e.message}")
            }
        }

        api.backupComplete(
            runId,
            RunCompleteRequest(fileCount = items.size, totalBytes = totalBytes, status = "complete"),
        )
        Log.i(TAG, "Backup complete: ${items.size} files, $uploaded uploaded")
        return BackupSummary(fileCount = items.size, uploaded = uploaded, totalBytes = totalBytes)
    }

    private fun shaOf(entry: BackupEntry): String {
        shaCache.get(entry.cacheKey)?.let { return it }
        val digest = MessageDigest.getInstance("SHA-256")
        entry.open(context.contentResolver).use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        val hex = digest.digest().joinToString("") { "%02x".format(it) }
        shaCache.put(entry.cacheKey, hex)
        return hex
    }

    private companion object {
        const val TAG = "BackupManager"
        const val MANIFEST_BATCH = 200
    }
}

data class BackupSummary(val fileCount: Int, val uploaded: Int, val totalBytes: Long)
