package org.svt.mdm.backup

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.ContactsContract
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.net.URLEncoder

/**
 * Enumerates the user files this agent can back up: images, video, audio and
 * downloads via MediaStore, plus a contacts vCard. Other apps' private data is
 * not accessible without root and is intentionally out of scope.
 */
class MediaEnumerator(private val context: Context) {

    fun enumerate(): List<BackupEntry> {
        val entries = mutableListOf<BackupEntry>()
        entries += query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "media")
        entries += query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, "media")
        entries += query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, "media")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            entries += query(MediaStore.Downloads.EXTERNAL_CONTENT_URI, "document")
        }
        exportContacts()?.let { entries += it }
        return entries
    }

    private fun query(collection: Uri, category: String): List<BackupEntry> {
        val hasRelPath = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        val projection = buildList {
            add(MediaStore.MediaColumns._ID)
            add(MediaStore.MediaColumns.DISPLAY_NAME)
            add(MediaStore.MediaColumns.SIZE)
            add(MediaStore.MediaColumns.DATE_MODIFIED)
            if (hasRelPath) add(MediaStore.MediaColumns.RELATIVE_PATH)
        }.toTypedArray()

        val out = mutableListOf<BackupEntry>()
        try {
            context.contentResolver.query(collection, projection, null, null, null)?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val nameCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val sizeCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                val dateCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
                val relCol = if (hasRelPath)
                    c.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH) else -1
                while (c.moveToNext()) {
                    val id = c.getLong(idCol)
                    val name = c.getString(nameCol) ?: "$id"
                    val size = c.getLong(sizeCol)
                    if (size <= 0) continue
                    val mtime = c.getLong(dateCol) * 1000L
                    val rel = if (relCol >= 0) c.getString(relCol) else null
                    val relPath = ((rel ?: "$category/").trimEnd('/') + "/" + name)
                    out += BackupEntry(
                        relPath = relPath,
                        size = size,
                        mtimeMs = mtime,
                        category = category,
                        uri = ContentUris.withAppendedId(collection, id),
                    )
                }
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "No permission to read $collection: ${e.message}")
        }
        return out
    }

    /** Export all contacts to a single vCard file in the cache dir. */
    private fun exportContacts(): BackupEntry? {
        return try {
            val keys = mutableListOf<String>()
            context.contentResolver.query(
                ContactsContract.Contacts.CONTENT_URI,
                arrayOf(ContactsContract.Contacts.LOOKUP_KEY),
                null, null, null,
            )?.use { c ->
                val k = c.getColumnIndexOrThrow(ContactsContract.Contacts.LOOKUP_KEY)
                while (c.moveToNext()) c.getString(k)?.let(keys::add)
            }
            if (keys.isEmpty()) return null

            val encoded = URLEncoder.encode(keys.joinToString(":"), "UTF-8")
            val uri = Uri.withAppendedPath(
                ContactsContract.Contacts.CONTENT_MULTI_VCARD_URI, encoded
            )
            val out = File(context.cacheDir, "contacts.vcf")
            context.contentResolver.openInputStream(uri)?.use { input ->
                out.outputStream().use { input.copyTo(it) }
            } ?: return null
            if (out.length() <= 0) return null

            BackupEntry(
                relPath = "contacts/contacts.vcf",
                size = out.length(),
                mtimeMs = out.lastModified(),
                category = "contacts",
                file = out,
            )
        } catch (e: SecurityException) {
            Log.w(TAG, "No permission to read contacts: ${e.message}")
            null
        }
    }

    private companion object {
        const val TAG = "MediaEnumerator"
    }
}
