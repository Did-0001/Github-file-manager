package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.domain.engine.GitBlobHasher
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    @Test
    fun `read string from context`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("GitHub File Manager", appName)
    }

    @Test
    fun `git blob hasher computes standard git sha1 correctly`() {
        // Standard git hash-object for "hello\n":
        // echo "hello" | git hash-object --stdin -> ce013625030ba8dba906f756967f9e9ca394464a
        val text = "hello\n"
        val bytes = text.toByteArray(Charsets.UTF_8)
        val sha = GitBlobHasher.calculateSha(bytes)
        assertEquals("ce013625030ba8dba906f756967f9e9ca394464a", sha)
    }
}
