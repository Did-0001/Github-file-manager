package com.example.domain.engine

import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.util.Base64

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class StreamingBlobRequestBodyTest {

    @Test
    fun `zero byte file has exact content length and valid json`() {
        verifyBlobBody(ByteArray(0))
    }

    @Test
    fun `1 byte file has exact content length and valid json`() {
        verifyBlobBody(byteArrayOf(65)) // "A"
    }

    @Test
    fun `2 byte file has exact content length and valid json`() {
        verifyBlobBody(byteArrayOf(65, 66)) // "AB"
    }

    @Test
    fun `3 byte file has exact content length and valid json`() {
        verifyBlobBody(byteArrayOf(65, 66, 67)) // "ABC"
    }

    @Test
    fun `4 byte file has exact content length and valid json`() {
        verifyBlobBody(byteArrayOf(65, 66, 67, 68)) // "ABCD"
    }

    @Test
    fun `5 byte file has exact content length and valid json`() {
        verifyBlobBody(byteArrayOf(65, 66, 67, 68, 69)) // "ABCDE"
    }

    @Test
    fun `6 byte file has exact content length and valid json`() {
        verifyBlobBody(byteArrayOf(1, 2, 3, 4, 5, 6))
    }

    @Test
    fun `7 byte file has exact content length and valid json`() {
        verifyBlobBody(byteArrayOf(10, 20, 30, 40, 50, 60, 70))
    }

    @Test
    fun `arbitrary payload sizes 8 to 256 bytes match content length exactly`() {
        for (len in 8..256) {
            val payload = ByteArray(len) { (it % 256).toByte() }
            verifyBlobBody(payload)
        }
    }

    private fun verifyBlobBody(rawBytes: ByteArray) {
        val body = StreamingBlobRequestBody(
            size = rawBytes.size.toLong(),
            openInputStream = { ByteArrayInputStream(rawBytes) }
        )

        val reportedLength = body.contentLength()

        val buffer = Buffer()
        body.writeTo(buffer)
        val writtenBytes = buffer.readByteArray()

        // 1. Content-Length MUST match actual written bytes exactly
        assertEquals(
            "Reported contentLength must match actual byte count for payload size ${rawBytes.size}",
            writtenBytes.size.toLong(),
            reportedLength
        )

        // 2. Wrapper overhead verification: 34 bytes + base64 length
        val expectedBase64 = if (rawBytes.isEmpty()) "" else Base64.getEncoder().encodeToString(rawBytes)
        val expectedWrapperLength = 34L + expectedBase64.length
        assertEquals(
            "Expected wrapper length formula (34 + base64Len) must match for size ${rawBytes.size}",
            expectedWrapperLength,
            reportedLength
        )

        // 3. Written output must be valid JSON: {"content":"<base64>","encoding":"base64"}
        val jsonString = String(writtenBytes, Charsets.UTF_8)
        val json = JSONObject(jsonString)
        assertEquals("base64", json.getString("encoding"))
        val contentInJson = json.getString("content")
        assertEquals(expectedBase64, contentInJson)

        // 4. Decoded content in JSON must equal original raw bytes
        val decoded = if (contentInJson.isEmpty()) ByteArray(0) else Base64.getDecoder().decode(contentInJson)
        assertArrayEquals(rawBytes, decoded)
    }
}
