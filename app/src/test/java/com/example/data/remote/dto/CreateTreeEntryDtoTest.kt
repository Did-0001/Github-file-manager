package com.example.data.remote.dto

import com.example.data.remote.ApiClient
import com.squareup.moshi.Moshi
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CreateTreeEntryDtoTest {

    @Test
    fun `null sha field serializes explicitly for Git API deletion`() {
        val moshi = Moshi.Builder()
            .add(CreateTreeEntryDto::class.java, CreateTreeEntryJsonAdapter())
            .build()
        val adapter = moshi.adapter(CreateTreeEntryDto::class.java)

        val deleteEntry = CreateTreeEntryDto(
            path = "old_file.txt",
            mode = "100644",
            type = "blob",
            sha = null
        )

        val json = adapter.toJson(deleteEntry)

        // GitHub API requires explicit "sha":null to delete a file when committing a tree!
        assertTrue("Serialized JSON must contain explicit null sha: $json", json.contains("\"sha\":null"))
        assertTrue("Serialized JSON must contain path", json.contains("\"path\":\"old_file.txt\""))
    }

    @Test
    fun `non-null sha field serializes normal string`() {
        val moshi = Moshi.Builder()
            .add(CreateTreeEntryDto::class.java, CreateTreeEntryJsonAdapter())
            .build()
        val adapter = moshi.adapter(CreateTreeEntryDto::class.java)

        val normalEntry = CreateTreeEntryDto(
            path = "file.txt",
            mode = "100644",
            type = "blob",
            sha = "3b18e512dba79e4c8300dd08aeb37f8e728b8dad"
        )

        val json = adapter.toJson(normalEntry)

        assertTrue(json.contains("\"sha\":\"3b18e512dba79e4c8300dd08aeb37f8e728b8dad\""))
        assertFalse(json.contains("\"sha\":null"))
    }
}
