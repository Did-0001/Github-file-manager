package com.example.domain.engine

import android.content.Context
import android.net.Uri
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody
import okio.BufferedSink
import java.io.IOException
import java.io.InputStream

/**
 * Memory-safe streaming RequestBody for GitHub Git Data API createBlob.
 * Wraps file content into JSON `{"content":"<base64>","encoding":"base64"}`
 * directly into the OkHttp sink without loading files into heap memory.
 */
class StreamingBlobRequestBody(
    private val size: Long,
    private val onProgress: ((bytesRead: Long) -> Unit)? = null,
    private val openInputStream: () -> InputStream
) : RequestBody() {

    constructor(
        context: Context,
        uri: Uri,
        size: Long,
        onProgress: ((bytesRead: Long) -> Unit)? = null
    ) : this(
        size = size,
        onProgress = onProgress,
        openInputStream = {
            context.contentResolver.openInputStream(uri)
                ?: throw IOException("Could not open stream for $uri")
        }
    )

    override fun contentType(): MediaType? = "application/json; charset=utf-8".toMediaTypeOrNull()

    override fun contentLength(): Long {
        // Base64 expands raw bytes: if size == 0, base64 length is 0, otherwise ((size + 2) / 3) * 4
        val base64Len = if (size == 0L) 0L else ((size + 2) / 3) * 4
        // Prefix {"content":" is 12 bytes
        // Suffix ","encoding":"base64"} is 22 bytes
        // Total wrapper overhead is exactly 34 bytes
        return 34L + base64Len
    }

    override fun writeTo(sink: BufferedSink) {
        sink.writeUtf8("{\"content\":\"")
        val base64Sink = StreamingBase64Sink(sink)

        openInputStream().use { input ->
            val buffer = ByteArray(8192)
            var read: Int
            var totalRead = 0L
            while (input.read(buffer).also { read = it } != -1) {
                base64Sink.write(buffer, 0, read)
                totalRead += read
                onProgress?.invoke(totalRead)
            }
        }
        base64Sink.finish()

        sink.writeUtf8("\",\"encoding\":\"base64\"}")
        sink.flush()
    }
}
