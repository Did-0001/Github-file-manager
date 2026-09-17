package com.example.domain.engine

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream

class GitBlobHasherTest {

    @Test
    fun `empty file produces standard git empty blob sha`() {
        // Standard git hash for empty blob:
        // git hash-object -t blob /dev/null -> e69de29bb2d1d6434b8b29ae775ad8c2e48c5391
        val emptyBytes = ByteArray(0)
        val sha = GitBlobHasher.calculateSha(emptyBytes)
        assertEquals("e69de29bb2d1d6434b8b29ae775ad8c2e48c5391", sha)

        val streamSha = GitBlobHasher.calculateSha(ByteArrayInputStream(emptyBytes), 0L)
        assertEquals("e69de29bb2d1d6434b8b29ae775ad8c2e48c5391", streamSha)
    }

    @Test
    fun `hello world produces standard git sha`() {
        // echo -n "hello world" | git hash-object --stdin -> 95d09f2b10159347eece71399a7e2e907ea3df4f
        val text = "hello world"
        val bytes = text.toByteArray(Charsets.UTF_8)
        val sha = GitBlobHasher.calculateSha(bytes)
        assertEquals("95d09f2b10159347eece71399a7e2e907ea3df4f", sha)

        val streamSha = GitBlobHasher.calculateSha(ByteArrayInputStream(bytes), bytes.size.toLong())
        assertEquals("95d09f2b10159347eece71399a7e2e907ea3df4f", streamSha)
    }

    @Test
    fun `inputstream calculation matches byte array calculation for multi-kilobyte payload`() {
        val payload = ByteArray(32768) { (it % 251).toByte() }
        val byteSha = GitBlobHasher.calculateSha(payload)
        val streamSha = GitBlobHasher.calculateSha(ByteArrayInputStream(payload), payload.size.toLong())
        assertEquals(byteSha, streamSha)
    }
}
