package org.svt.mdm.transport

import java.io.InputStream
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okio.BufferedSink
import okio.source

/**
 * Streams an [InputStream] as an OkHttp request body without buffering the
 * whole file in memory. [open] is called each time the body is written (OkHttp
 * may retry), so it must return a fresh stream.
 */
class StreamingRequestBody(
    private val open: () -> InputStream,
    private val mediaType: MediaType = "application/octet-stream".toMediaType(),
) : RequestBody() {

    override fun contentType(): MediaType = mediaType

    // Unknown length -> chunked transfer encoding, which the server streams.
    override fun contentLength(): Long = -1

    override fun writeTo(sink: BufferedSink) {
        open().use { input -> sink.writeAll(input.source()) }
    }
}
