package com.example.domain.engine

import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Base64
import kotlin.random.Random

class StreamingBase64SinkTest {

    @Test
    fun `short single byte writes encode correctly without early padding`() {
        val input = "Hello, World!".toByteArray(Charsets.UTF_8)
        val expectedBase64 = Base64.getEncoder().encodeToString(input)

        val destBuffer = Buffer()
        val base64Sink = StreamingBase64Sink(destBuffer)

        for (b in input) {
            base64Sink.write(byteArrayOf(b))
        }
        base64Sink.finish()

        val actualBase64 = destBuffer.readUtf8()
        assertEquals(expectedBase64, actualBase64)
    }

    @Test
    fun `two byte chunk writes encode correctly`() {
        val input = "Testing chunked base64 stream".toByteArray(Charsets.UTF_8)
        val expectedBase64 = Base64.getEncoder().encodeToString(input)

        val destBuffer = Buffer()
        val base64Sink = StreamingBase64Sink(destBuffer)

        var offset = 0
        while (offset < input.size) {
            val chunkLen = minOf(2, input.size - offset)
            base64Sink.write(input, offset, chunkLen)
            offset += chunkLen
        }
        base64Sink.finish()

        val actualBase64 = destBuffer.readUtf8()
        assertEquals(expectedBase64, actualBase64)
    }

    @Test
    fun `random chunk sizes match standard base64 encoding`() {
        val random = Random(42)
        for (trial in 1..20) {
            val dataSize = random.nextInt(1, 2048)
            val data = ByteArray(dataSize) { random.nextInt().toByte() }
            val expectedBase64 = Base64.getEncoder().encodeToString(data)

            val destBuffer = Buffer()
            val base64Sink = StreamingBase64Sink(destBuffer)

            var offset = 0
            while (offset < data.size) {
                val chunkSize = random.nextInt(1, 17).coerceAtMost(data.size - offset)
                base64Sink.write(data, offset, chunkSize)
                offset += chunkSize
            }
            base64Sink.finish()

            val actualBase64 = destBuffer.readUtf8()
            assertEquals("Trial $trial failed for size $dataSize", expectedBase64, actualBase64)
        }
    }
}
