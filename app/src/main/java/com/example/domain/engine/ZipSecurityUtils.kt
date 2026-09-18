package com.example.domain.engine

import java.io.File

object ZipSecurityUtils {

    /**
     * Checks if a ZIP entry name or file path attempts directory traversal.
     * Rejects:
     * - "../evil.txt"
     * - "../../evil.txt"
     * - "/absolute/path"
     * - "foo/../../evil"
     * - Windows separators: "..\..\evil.txt"
     * - Any path whose normalized form escapes or points outside its root.
     */
    fun isPathTraversal(rawPath: String): Boolean {
        if (rawPath.isBlank()) return false

        // Normalize separators
        val normalizedSeparators = rawPath.replace('\\', '/')

        // Check for absolute path
        if (normalizedSeparators.startsWith("/")) return true

        // Split by '/' and check for any traversal token
        val tokens = normalizedSeparators.split('/')
        if (tokens.any { it == ".." }) {
            // Check if directory depth ever goes below zero
            var depth = 0
            for (token in tokens) {
                if (token == "..") {
                    depth--
                    if (depth < 0) return true
                } else if (token.isNotEmpty() && token != ".") {
                    depth++
                }
            }
            if (depth < 0) return true
        }

        // Additional File.normalize check
        val normalized = File(normalizedSeparators).normalize().path.replace('\\', '/')
        if (normalized.startsWith("../") || normalized == ".." || normalized.startsWith("/")) {
            return true
        }

        return false
    }

    /**
     * Sanitizes and validates a relative path. Returns null if invalid or traversal.
     */
    fun sanitizeRelativePath(rawPath: String): String? {
        if (isPathTraversal(rawPath)) return null
        val clean = rawPath.trim().replace('\\', '/').trimStart('/')
        val parts = clean.split('/').filter { it.isNotEmpty() && it != "." }
        val resolvedParts = mutableListOf<String>()
        for (part in parts) {
            if (part == "..") {
                if (resolvedParts.isEmpty()) return null
                resolvedParts.removeAt(resolvedParts.lastIndex)
            } else {
                resolvedParts.add(part)
            }
        }
        return resolvedParts.joinToString("/")
    }

    /**
     * Inspects the list of ZIP entry names to explicitly determine the common
     * GitHub archive root folder (typically `<repo>-<branchOrCommit>/`).
     * If all non-empty entry names share the same first segment, returns that segment.
     */
    fun determineGitHubArchiveRoot(entryNames: List<String>): String? {
        val nonBlank = entryNames.map { it.trim().replace('\\', '/').trimStart('/') }.filter { it.isNotEmpty() }
        if (nonBlank.isEmpty()) return null

        val firstSegments = nonBlank.map { entry ->
            val slashIndex = entry.indexOf('/')
            if (slashIndex > 0) entry.substring(0, slashIndex) else entry
        }.distinct()

        return if (firstSegments.size == 1) firstSegments.first() else null
    }

    /**
     * Strips the explicit archive root if present, preserving the relative path inside.
     */
    fun stripArchiveRoot(entryName: String, archiveRoot: String?): String {
        val clean = entryName.trim().replace('\\', '/').trimStart('/')
        if (archiveRoot.isNullOrEmpty()) return clean

        val prefix = if (archiveRoot.endsWith("/")) archiveRoot else "$archiveRoot/"
        return if (clean.startsWith(prefix)) {
            clean.removePrefix(prefix)
        } else if (clean == archiveRoot) {
            ""
        } else {
            clean
        }
    }
}
