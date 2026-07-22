package org.svt.mdm.backup

import android.content.Context

/**
 * Caches the SHA-256 of a file keyed by path+size+mtime, so unchanged files
 * aren't re-hashed on every backup run. Plain SharedPreferences is fine — the
 * values are not sensitive (hashes of the user's own files).
 */
class ShaCache(context: Context) {
    private val prefs = context.getSharedPreferences("svt_mdm_sha_cache", Context.MODE_PRIVATE)

    fun get(key: String): String? = prefs.getString(key, null)

    fun put(key: String, sha: String) {
        prefs.edit().putString(key, sha).apply()
    }
}
