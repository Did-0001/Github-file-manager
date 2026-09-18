package com.example.domain.engine

import org.junit.Assert.*
import org.junit.Test

class ZipSecurityTest {

    @Test
    fun `test zip slip protection against relative traversals`() {
        assertTrue(ZipSecurityUtils.isPathTraversal("../evil.txt"))
        assertTrue(ZipSecurityUtils.isPathTraversal("../../evil.txt"))
        assertTrue(ZipSecurityUtils.isPathTraversal("foo/../../evil"))
        assertTrue(ZipSecurityUtils.isPathTraversal("a/b/c/../../../../escape.txt"))
    }

    @Test
    fun `test zip slip protection against absolute paths`() {
        assertTrue(ZipSecurityUtils.isPathTraversal("/absolute/path"))
        assertTrue(ZipSecurityUtils.isPathTraversal("/etc/passwd"))
    }

    @Test
    fun `test zip slip protection against windows backslash separators`() {
        assertTrue(ZipSecurityUtils.isPathTraversal("..\\..\\evil.txt"))
        assertTrue(ZipSecurityUtils.isPathTraversal("foo\\..\\..\\evil.txt"))
        assertTrue(ZipSecurityUtils.isPathTraversal("..\\evil.txt"))
    }

    @Test
    fun `test safe relative paths are accepted`() {
        assertFalse(ZipSecurityUtils.isPathTraversal("src/main/java/Main.kt"))
        assertFalse(ZipSecurityUtils.isPathTraversal("README.md"))
        assertFalse(ZipSecurityUtils.isPathTraversal("assets/images/logo.png"))
        assertFalse(ZipSecurityUtils.isPathTraversal("nested/folder/subfolder/file.json"))
        assertFalse(ZipSecurityUtils.isPathTraversal("folder with spaces/file name.txt"))
        assertFalse(ZipSecurityUtils.isPathTraversal("unicode_日本語_test/file.txt"))
    }

    @Test
    fun `test sanitizeRelativePath normalizes and cleans paths`() {
        assertEquals("src/Main.kt", ZipSecurityUtils.sanitizeRelativePath("src/./Main.kt"))
        assertEquals("src/Main.kt", ZipSecurityUtils.sanitizeRelativePath("src//Main.kt"))
        assertEquals("Main.kt", ZipSecurityUtils.sanitizeRelativePath("foo/../Main.kt"))
        assertEquals("src/Main.kt", ZipSecurityUtils.sanitizeRelativePath("src\\Main.kt"))
        assertEquals("file with space.txt", ZipSecurityUtils.sanitizeRelativePath("file with space.txt"))
        assertEquals("unicode_üñïçødé.txt", ZipSecurityUtils.sanitizeRelativePath("unicode_üñïçødé.txt"))
        assertNull(ZipSecurityUtils.sanitizeRelativePath("../foo"))
        assertNull(ZipSecurityUtils.sanitizeRelativePath("foo/../../bar"))
    }

    @Test
    fun `test gitHub archive root determination for empty repository`() {
        val root = ZipSecurityUtils.determineGitHubArchiveRoot(emptyList())
        assertNull(root)
    }

    @Test
    fun `test gitHub archive root determination for single file`() {
        val entries = listOf("octocat-Hello-World-7fd1a60/README.md")
        val root = ZipSecurityUtils.determineGitHubArchiveRoot(entries)
        assertEquals("octocat-Hello-World-7fd1a60", root)
        assertEquals("README.md", ZipSecurityUtils.stripArchiveRoot(entries[0], root))
    }

    @Test
    fun `test gitHub archive root determination for nested repository`() {
        val entries = listOf(
            "octocat-Hello-World-7fd1a60/",
            "octocat-Hello-World-7fd1a60/repo/",
            "octocat-Hello-World-7fd1a60/repo/src/",
            "octocat-Hello-World-7fd1a60/repo/src/Main.kt",
            "octocat-Hello-World-7fd1a60/repo/src/utils/Helper.kt"
        )
        val root = ZipSecurityUtils.determineGitHubArchiveRoot(entries)
        assertEquals("octocat-Hello-World-7fd1a60", root)

        assertEquals("", ZipSecurityUtils.stripArchiveRoot("octocat-Hello-World-7fd1a60/", root))
        assertEquals("repo/", ZipSecurityUtils.stripArchiveRoot("octocat-Hello-World-7fd1a60/repo/", root))
        assertEquals("repo/src/", ZipSecurityUtils.stripArchiveRoot("octocat-Hello-World-7fd1a60/repo/src/", root))
        assertEquals("repo/src/Main.kt", ZipSecurityUtils.stripArchiveRoot("octocat-Hello-World-7fd1a60/repo/src/Main.kt", root))
    }

    @Test
    fun `test gitHub archive root handling with unusual file names`() {
        val entries = listOf(
            "my-repo-main/.github/workflows/ci.yml",
            "my-repo-main/scripts/run test (1).sh",
            "my-repo-main/data/résumé_日本語.json",
            "my-repo-main/config/settings-v2.0.1.xml"
        )
        val root = ZipSecurityUtils.determineGitHubArchiveRoot(entries)
        assertEquals("my-repo-main", root)

        assertEquals(".github/workflows/ci.yml", ZipSecurityUtils.stripArchiveRoot(entries[0], root))
        assertEquals("scripts/run test (1).sh", ZipSecurityUtils.stripArchiveRoot(entries[1], root))
        assertEquals("data/résumé_日本語.json", ZipSecurityUtils.stripArchiveRoot(entries[2], root))
        assertEquals("config/settings-v2.0.1.xml", ZipSecurityUtils.stripArchiveRoot(entries[3], root))
    }
}
