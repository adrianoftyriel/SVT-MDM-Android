package org.svt.mdm.collect

import android.app.usage.UsageStatsManager
import android.content.Context
import java.time.Instant
import org.svt.mdm.transport.dto.UsageEntry

/**
 * Aggregates per-app foreground time over the last [rangeDays] days using
 * UsageStatsManager. Requires the "Usage access" special permission
 * (see MainActivity for the grant flow).
 */
class UsageCollector(private val context: Context) {

    fun collect(rangeDays: Int = 7): List<UsageEntry> {
        val usm =
            context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val end = System.currentTimeMillis()
        val start = end - rangeDays.toLong() * 24 * 60 * 60 * 1000

        val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_BEST, start, end)
            ?: return emptyList()

        // A package can appear multiple times; keep the max foreground total and
        // the latest last-used timestamp.
        val byPackage = HashMap<String, UsageEntry>()
        for (s in stats) {
            if (s.totalTimeInForeground <= 0) continue
            val existing = byPackage[s.packageName]
            val fg = maxOf(existing?.foregroundMs ?: 0, s.totalTimeInForeground)
            val lastUsed = maxOf(
                existing?.lastUsed?.let { Instant.parse(it).toEpochMilli() } ?: 0,
                s.lastTimeUsed,
            )
            byPackage[s.packageName] = UsageEntry(
                pkg = s.packageName,
                foregroundMs = fg,
                lastUsed = Instant.ofEpochMilli(lastUsed).toString(),
            )
        }
        return byPackage.values.sortedByDescending { it.foregroundMs }
    }
}
