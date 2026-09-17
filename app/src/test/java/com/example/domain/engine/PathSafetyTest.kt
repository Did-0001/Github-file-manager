package com.example.domain.engine

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class PathSafetyTest {

    @Test
    fun `detects directory traversal patterns`() {
        val maliciousPaths = listOf(
            "../etc/passwd",
            "foo/../../bar",
            "..",
            "sub/../../../danger",
            "dir/..",
            "a/b/c/../../../../root"
        )

        for (path in maliciousPaths) {
            val normalized = File(path).normalize().path.replace('\\', '/')
            val isTraversal = path.split('/', '\\').any { it == ".." } || normalized.startsWith("../") || normalized == ".."
            assertTrue("Path '$path' (normalized: '$normalized') must be flagged as traversal", isTraversal)
        }
    }

    @Test
    fun `safe paths are preserved`() {
        val safePaths = listOf(
            "src/main/java/App.kt",
            "readme.md",
            "docs/images/logo.png",
            "nested/dir/level2/file.txt"
        )

        for (path in safePaths) {
            val normalized = File(path).normalize().path.replace('\\', '/')
            val isTraversal = path.split('/', '\\').any { it == ".." } || normalized.startsWith("../") || normalized == ".."
            assertFalse("Path '$path' must be recognized as safe", isTraversal)
        }
    }
}
