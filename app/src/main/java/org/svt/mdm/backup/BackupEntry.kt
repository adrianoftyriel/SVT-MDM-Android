package org.svt.mdm.backup

import android.content.ContentResolver
import android.net.Uri
import java.io.File
import java.io.InputStream

/**
 * One item to back up: either a MediaStore [uri] or a local [file] (e.g. an
 * exported contacts vCard). Exactly one source is set.
 */
data class BackupEntry(
    val relPath: String,
    val size: Long,
    val mtimeMs: Long,
    val category: String,
    val uri: Uri? = null,
    val file: File? = null,
) {
    fun open(resolver: ContentResolver): InputStream =
        when {
            uri != null -> resolver.openInputStream(uri)
                ?: error("Cannot open $uri")
            file != null -> file.inputStream()
            else -> error("BackupEntry has no source")
        }

    /** Stable identity for the SHA cache (path + size + mtime). */
    val cacheKey: String get() = "$relPath|$size|$mtimeMs"
}
