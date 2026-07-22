package org.svt.mdm.backup

import android.content.Context
import android.provider.CallLog
import android.provider.CalendarContract
import android.provider.Telephony
import android.util.Log
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.put

private const val TAG = "DataCollectors"

private fun cacheEntry(context: Context, name: String, category: String, content: ByteArray): BackupEntry? {
    if (content.isEmpty()) return null
    val file = File(context.cacheDir, name)
    file.writeBytes(content)
    return BackupEntry(
        relPath = "$category/$name",
        size = file.length(),
        mtimeMs = file.lastModified(),
        category = category,
        file = file,
    )
}

/** Exports SMS/MMS text messages as JSON. Requires READ_SMS. */
class SmsCollector(private val context: Context) {
    fun export(): BackupEntry? = try {
        val arr = buildJsonArray {
            context.contentResolver.query(
                Telephony.Sms.CONTENT_URI,
                arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.DATE, Telephony.Sms.TYPE, Telephony.Sms.BODY),
                null, null, "${Telephony.Sms.DATE} DESC",
            )?.use { c ->
                val a = c.getColumnIndex(Telephony.Sms.ADDRESS)
                val d = c.getColumnIndex(Telephony.Sms.DATE)
                val t = c.getColumnIndex(Telephony.Sms.TYPE)
                val b = c.getColumnIndex(Telephony.Sms.BODY)
                while (c.moveToNext()) {
                    addJsonObject {
                        put("address", c.getString(a))
                        put("date", c.getLong(d))
                        put("type", c.getInt(t))
                        put("body", c.getString(b))
                    }
                }
            }
        }
        cacheEntry(context, "sms.json", "sms", arr.toString().toByteArray())
    } catch (e: SecurityException) {
        Log.w(TAG, "No READ_SMS permission: ${e.message}"); null
    }
}

/** Exports the call log as JSON. Requires READ_CALL_LOG. */
class CallLogCollector(private val context: Context) {
    fun export(): BackupEntry? = try {
        val arr = buildJsonArray {
            context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.DATE, CallLog.Calls.DURATION,
                        CallLog.Calls.TYPE, CallLog.Calls.CACHED_NAME),
                null, null, "${CallLog.Calls.DATE} DESC",
            )?.use { c ->
                val n = c.getColumnIndex(CallLog.Calls.NUMBER)
                val d = c.getColumnIndex(CallLog.Calls.DATE)
                val dur = c.getColumnIndex(CallLog.Calls.DURATION)
                val t = c.getColumnIndex(CallLog.Calls.TYPE)
                val name = c.getColumnIndex(CallLog.Calls.CACHED_NAME)
                while (c.moveToNext()) {
                    addJsonObject {
                        put("number", c.getString(n))
                        put("date", c.getLong(d))
                        put("duration_s", c.getLong(dur))
                        put("type", c.getInt(t))
                        put("name", if (name >= 0) c.getString(name) else null)
                    }
                }
            }
        }
        cacheEntry(context, "calllog.json", "calllog", arr.toString().toByteArray())
    } catch (e: SecurityException) {
        Log.w(TAG, "No READ_CALL_LOG permission: ${e.message}"); null
    }
}

/** Exports calendar events as an ICS file. Requires READ_CALENDAR. */
class CalendarCollector(private val context: Context) {
    private val utc = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)

    fun export(): BackupEntry? = try {
        val sb = StringBuilder("BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//SVT MDM//EN\r\n")
        context.contentResolver.query(
            CalendarContract.Events.CONTENT_URI,
            arrayOf(CalendarContract.Events._ID, CalendarContract.Events.TITLE,
                    CalendarContract.Events.DTSTART, CalendarContract.Events.DTEND,
                    CalendarContract.Events.EVENT_LOCATION, CalendarContract.Events.DESCRIPTION),
            null, null, null,
        )?.use { c ->
            val id = c.getColumnIndex(CalendarContract.Events._ID)
            val title = c.getColumnIndex(CalendarContract.Events.TITLE)
            val start = c.getColumnIndex(CalendarContract.Events.DTSTART)
            val end = c.getColumnIndex(CalendarContract.Events.DTEND)
            val loc = c.getColumnIndex(CalendarContract.Events.EVENT_LOCATION)
            val desc = c.getColumnIndex(CalendarContract.Events.DESCRIPTION)
            while (c.moveToNext()) {
                val startMs = c.getLong(start)
                if (startMs <= 0) continue
                sb.append("BEGIN:VEVENT\r\n")
                sb.append("UID:${c.getLong(id)}@svtmdm\r\n")
                sb.append("DTSTART:${utc.format(Instant.ofEpochMilli(startMs))}\r\n")
                val endMs = if (end >= 0) c.getLong(end) else 0
                if (endMs > 0) sb.append("DTEND:${utc.format(Instant.ofEpochMilli(endMs))}\r\n")
                sb.append("SUMMARY:${icsEscape(c.getString(title))}\r\n")
                if (loc >= 0) c.getString(loc)?.let { sb.append("LOCATION:${icsEscape(it)}\r\n") }
                if (desc >= 0) c.getString(desc)?.let { sb.append("DESCRIPTION:${icsEscape(it)}\r\n") }
                sb.append("END:VEVENT\r\n")
            }
        }
        sb.append("END:VCALENDAR\r\n")
        cacheEntry(context, "calendar.ics", "calendar", sb.toString().toByteArray())
    } catch (e: SecurityException) {
        Log.w(TAG, "No READ_CALENDAR permission: ${e.message}"); null
    }

    private fun icsEscape(s: String?): String =
        (s ?: "").replace("\\", "\\\\").replace("\n", "\\n").replace(",", "\\,").replace(";", "\\;")
}
