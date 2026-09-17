package com.example.domain.engine

import android.content.Context
import android.net.Uri
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody
import okio.BufferedSink
import java.io.IOException

/**
 * Memory-safe streaming RequestBody for GitHub Git Data API createBlob.
 * Encodes file content to Base64 in bounded chunks directly into the OkHttp sink,
 * avoiding large ByteArray allocations in heap memory.
 */
class StreamingBlobRequestBody(
    private val context: Context,
    private val uri: Uri,
    private val size: Long,
    private val onProgress: ((bytesRead: Long) -> Unit)? = null
) : RequestBody() {

    override fun contentType(): MediaType? = "application/json; charset=utf-8".toMediaTypeOrNull()

    override fun contentLength(): Long {
        // Base64 expands 3 raw bytes to 4 ASCII characters: ceil(size / 3) * 4
        val base64Len = if (size == 0L) 0L else ((size + 2) / 3) * 4
        // Prefix {"content":" is 12 bytes
        // Suffix ","encoding":"base64"} is 21 bytes
        return 33L + base64Len
    }

    override fun writeTo(sink: BufferedSink) {
        sink.writeUtf8("{\"content\":\"")
        context.contentResolver.openInputStream(uri)?.use { input ->
            // Chunk size MUST be a multiple of 3 (12288 bytes) so intermediate base64 blocks don't produce padding
            val buffer = ByteArray(3 * 4096)
            var read: Int
            var totalRead = 0L
            while (input.read(buffer).also { read = it } != -1) {
                val encodedChunk = android.util.Base64.encode(buffer, 0, read, android.util.Base64.NO_WRAP)
                sink.write(encodedChunk)
                totalRead += read
                onProgress?.invoke(totalRead)
            }
        } ?: throw IOException("Could not open stream for $uri")
        sink.writeUtf8("\",\"encoding\":\"base64\"}")
    }
}
