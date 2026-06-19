package app.shadey.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** A GitHub release that is newer than the installed build. */
data class UpdateInfo(
    val tag: String,
    val htmlUrl: String,
    val releaseNotes: String,
)

/**
 * Checks GitHub Releases for a newer Shadey build. Unauthenticated (60 req/hr/IP, no key),
 * opt-in, and gated by the network-roaming preference at the call site. Never throws —
 * any network/parse failure resolves to null so a failed check is simply a no-op.
 */
object UpdateChecker {
    private const val LATEST_URL = "https://api.github.com/repos/phrag/shadey/releases/latest"
    private const val RELEASES_URL = "https://github.com/phrag/shadey/releases"
    private const val USER_AGENT = "Shadey/1.0 (+https://github.com/phrag/shadey)"

    /** Returns the latest release when its tag is strictly newer than [currentVersion], else null. */
    suspend fun checkLatest(currentVersion: String): UpdateInfo? = withContext(Dispatchers.IO) {
        val body = runCatching { httpGet(LATEST_URL) }.getOrNull() ?: return@withContext null
        val o = runCatching { JSONObject(body) }.getOrNull() ?: return@withContext null
        val tag = o.optString("tag_name").ifBlank { return@withContext null }
        if (!isNewer(tag, currentVersion)) return@withContext null
        UpdateInfo(
            tag = tag,
            htmlUrl = o.optString("html_url").ifBlank { RELEASES_URL },
            releaseNotes = o.optString("body"),
        )
    }

    /**
     * True when [tag] is a strictly higher version than [current]. Both may carry a leading "v"
     * or trailing suffixes ("v1.2.0-beta"); only the dotted numeric components are compared.
     * A blank/unparseable [current] returns false — we never claim an update we can't justify.
     */
    internal fun isNewer(tag: String, current: String): Boolean {
        val a = versionParts(tag)
        val b = versionParts(current)
        if (a.isEmpty() || b.isEmpty()) return false
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    /** Dotted numeric components of a version string ("v1.2.0-beta" → [1, 2, 0]). */
    private fun versionParts(v: String): List<Int> =
        Regex("\\d+").findAll(v).mapNotNull { it.value.toIntOrNull() }.toList()

    private fun httpGet(url: String): String? {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 15_000
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", "application/vnd.github+json")
        }
        return try {
            if (conn.responseCode !in 200..299) null
            else conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }
}
