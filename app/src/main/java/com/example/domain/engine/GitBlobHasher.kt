package com.example.domain.engine

import android.content.Context
import android.net.Uri
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest

object GitBlobHasher {

    /**
     * Calculates the exact Git blob SHA-1 for a file by streaming.
     * Git calculates blob identity as: SHA-1("blob <size>\u0000" + [file content bytes]).
     */
    fun calculateSha(context: Context, uri: Uri, size: Long): String {
        val stream = context.contentResolver.openInputStream(uri)
            ?: throw IOException("Cannot open stream for $uri")
        return stream.use { calculateSha(it, size) }
    }

    fun calculateSha(stream: InputStream, size: Long): String {
        val md = MessageDigest.getInstance("SHA-1")
        val header = "blob $size\u0000".toByteArray(Charsets.US_ASCII)
        md.update(header)
        val buffer = ByteArray(16384)
        var read: Int
        while (stream.read(buffer).also { read = it } != -1) {
            md.update(buffer, 0, read)
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    fun calculateSha(bytes: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-1")
        val header = "blob ${bytes.size}\u0000".toByteArray(Charsets.US_ASCII)
        md.update(header)
        md.update(bytes)
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
