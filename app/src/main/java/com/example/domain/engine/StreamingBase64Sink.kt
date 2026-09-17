package com.example.domain.engine

import okio.BufferedSink

/**
 * Memory-safe streaming Base64 sink that handles arbitrary chunk splits and short reads.
 * Preserves triplet alignment across chunk boundaries using a carry buffer of up to 2 bytes,
 * guaranteeing that padding characters ('=') are only emitted upon [finish] at true EOF.
 */
class StreamingBase64Sink(private val sink: BufferedSink) {

    private val carry = ByteArray(3)
    private var carryLen = 0
    private val outChunk = ByteArray(8192)

    companion object {
        private val BASE64_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
            .toByteArray(Charsets.US_ASCII)
    }

    fun write(data: ByteArray) {
        write(data, 0, data.size)
    }

    fun write(data: ByteArray, offset: Int, length: Int) {
        if (length <= 0) return
        var pos = offset
        var remaining = length

        // If carry has leftover bytes from prior write, try to complete a triplet
        if (carryLen > 0) {
            val needed = 3 - carryLen
            if (remaining >= needed) {
                for (i in 0 until needed) {
                    carry[carryLen + i] = data[pos + i]
                }
                pos += needed
                remaining -= needed
                writeTriplet(carry[0], carry[1], carry[2])
                carryLen = 0
            } else {
                for (i in 0 until remaining) {
                    carry[carryLen + i] = data[pos + i]
                }
                carryLen += remaining
                return
            }
        }

        // Process full triplets directly from incoming data
        val fullTriplets = remaining / 3
        val bytesToProcess = fullTriplets * 3
        val endPos = pos + bytesToProcess

        var outPos = 0
        while (pos < endPos) {
            val b0 = data[pos++].toInt() and 0xFF
            val b1 = data[pos++].toInt() and 0xFF
            val b2 = data[pos++].toInt() and 0xFF

            outChunk[outPos++] = BASE64_ALPHABET[(b0 ushr 2) and 0x3F]
            outChunk[outPos++] = BASE64_ALPHABET[((b0 and 0x03) shl 4) or ((b1 ushr 4) and 0x0F)]
            outChunk[outPos++] = BASE64_ALPHABET[((b1 and 0x0F) shl 2) or ((b2 ushr 6) and 0x03)]
            outChunk[outPos++] = BASE64_ALPHABET[b2 and 0x3F]

            if (outPos >= outChunk.size - 4) {
                sink.write(outChunk, 0, outPos)
                outPos = 0
            }
        }
        if (outPos > 0) {
            sink.write(outChunk, 0, outPos)
        }

        // Retain any remaining 1 or 2 bytes in carry
        val leftover = remaining - bytesToProcess
        for (i in 0 until leftover) {
            carry[i] = data[pos + i]
        }
        carryLen = leftover
    }

    fun finish() {
        if (carryLen == 1) {
            val b0 = carry[0].toInt() and 0xFF
            sink.writeByte(BASE64_ALPHABET[(b0 ushr 2) and 0x3F].toInt())
            sink.writeByte(BASE64_ALPHABET[(b0 and 0x03) shl 4].toInt())
            sink.writeByte('='.code)
            sink.writeByte('='.code)
        } else if (carryLen == 2) {
            val b0 = carry[0].toInt() and 0xFF
            val b1 = carry[1].toInt() and 0xFF
            sink.writeByte(BASE64_ALPHABET[(b0 ushr 2) and 0x3F].toInt())
            sink.writeByte(BASE64_ALPHABET[((b0 and 0x03) shl 4) or ((b1 ushr 4) and 0x0F)].toInt())
            sink.writeByte(BASE64_ALPHABET[(b1 and 0x0F) shl 2].toInt())
            sink.writeByte('='.code)
        }
        carryLen = 0
        sink.flush()
    }

    private fun writeTriplet(byte0: Byte, byte1: Byte, byte2: Byte) {
        val b0 = byte0.toInt() and 0xFF
        val b1 = byte1.toInt() and 0xFF
        val b2 = byte2.toInt() and 0xFF
        sink.writeByte(BASE64_ALPHABET[(b0 ushr 2) and 0x3F].toInt())
        sink.writeByte(BASE64_ALPHABET[((b0 and 0x03) shl 4) or ((b1 ushr 4) and 0x0F)].toInt())
        sink.writeByte(BASE64_ALPHABET[((b1 and 0x0F) shl 2) or ((b2 ushr 6) and 0x03)].toInt())
        sink.writeByte(BASE64_ALPHABET[b2 and 0x3F].toInt())
    }
}
