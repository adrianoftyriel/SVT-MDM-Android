package org.svt.mdm.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Self-update: checks the GitHub Releases API for a newer APK and installs it
 * via PackageInstaller. As Device Owner the install is silent; otherwise the
 * system shows a confirmation (needs REQUEST_INSTALL_PACKAGES + the user
 * allowing installs from this app). Updates install in place because every
 * release is signed with the same key.
 */
class UpdateManager(private val context: Context) {

    private val http = OkHttpClient()
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class GhRelease(val tag_name: String = "", val assets: List<GhAsset> = emptyList())

    @Serializable
    private data class GhAsset(val name: String = "", val browser_download_url: String = "")

    data class UpdateInfo(val latest: String, val current: String, val url: String)

    val currentVersion: String
        get() = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "?"

    /** Returns update details if a newer release exists, else null. */
    suspend fun check(): UpdateInfo? = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("https://api.github.com/repos/$REPO/releases/latest")
            .header("Accept", "application/vnd.github+json")
            .build()
        http.newCall(req).execute().use { resp ->
            val body = resp.body?.string()
            if (!resp.isSuccessful || body.isNullOrBlank()) return@withContext null
            val release = json.decodeFromString<GhRelease>(body)
            val latest = release.tag_name.removePrefix("v").trim()
            val asset = release.assets.firstOrNull { it.name == "svt-mdm-latest.apk" }
                ?: release.assets.firstOrNull { it.name.endsWith(".apk") }
                ?: return@withContext null
            if (isNewer(latest, currentVersion)) {
                UpdateInfo(latest, currentVersion, asset.browser_download_url)
            } else {
                null
            }
        }
    }

    /** Download the APK and hand it to PackageInstaller. */
    suspend fun downloadAndInstall(info: UpdateInfo) = withContext(Dispatchers.IO) {
        val apk = File(context.cacheDir, "update.apk")
        http.newCall(Request.Builder().url(info.url).build()).execute().use { resp ->
            check(resp.isSuccessful) { "download failed: HTTP ${resp.code}" }
            resp.body!!.byteStream().use { input -> apk.outputStream().use { input.copyTo(it) } }
        }
        install(apk)
    }

    private fun install(apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(
            PackageInstaller.SessionParams.MODE_FULL_INSTALL
        )
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            session.openWrite("apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val statusIntent = Intent(context, InstallReceiver::class.java)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
            val pending = PendingIntent.getBroadcast(context, sessionId, statusIntent, flags)
            session.commit(pending.intentSender)
        }
    }

    private fun isNewer(latest: String, current: String): Boolean {
        fun parts(v: String) = v.split(".").mapNotNull { it.toIntOrNull() }
        val a = parts(latest); val b = parts(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }; val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    private companion object {
        // The repository that hosts the release APKs.
        const val REPO = "adrianoftyriel/svt-mdm-android"
    }
}
