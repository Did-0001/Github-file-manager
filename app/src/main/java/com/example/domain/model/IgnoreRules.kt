package com.example.domain.model

data class IgnoreRules(
    val excludeGit: Boolean = true,
    val excludeBuildAndGradle: Boolean = true,
    val excludeIdeFiles: Boolean = true,
    val excludeNodeModules: Boolean = true,
    val excludeHiddenFiles: Boolean = true,
    val maxFileSizeMb: Long = 100L, // GitHub standard file size limit warning
    val customPatterns: List<String> = emptyList()
) {
    fun isIgnored(relativePath: String, isDirectory: Boolean, sizeBytes: Long): Pair<Boolean, String?> {
        val normalized = relativePath.trimStart('/').replace('\\', '/')
        val fileName = normalized.substringAfterLast('/')

        // Size check for files
        if (!isDirectory && maxFileSizeMb > 0 && sizeBytes > maxFileSizeMb * 1024 * 1024) {
            return Pair(true, "Exceeds max file size limit of ${maxFileSizeMb}MB ($sizeBytes bytes)")
        }

        // Hidden files
        if (excludeHiddenFiles && (fileName.startsWith(".") && fileName != ".gitignore")) {
            return Pair(true, "Hidden file or folder")
        }

        // .git folder
        if (excludeGit && (normalized == ".git" || normalized.startsWith(".git/"))) {
            return Pair(true, "Git metadata (.git)")
        }

        // build / .gradle
        if (excludeBuildAndGradle) {
            if (normalized == "build" || normalized.startsWith("build/") ||
                normalized.contains("/build/") ||
                normalized == ".gradle" || normalized.startsWith(".gradle/") ||
                normalized.contains("/.gradle/") ||
                fileName.endsWith(".apk") || fileName.endsWith(".aab")
            ) {
                return Pair(true, "Build output or Gradle cache")
            }
        }

        // IDE files
        if (excludeIdeFiles) {
            if (normalized.startsWith(".idea/") || normalized.contains("/.idea/") ||
                normalized.startsWith(".vscode/") || normalized.contains("/.vscode/") ||
                fileName.endsWith(".iml") || fileName == ".DS_Store"
            ) {
                return Pair(true, "IDE or OS temporary metadata")
            }
        }

        // Node modules
        if (excludeNodeModules && (normalized == "node_modules" || normalized.startsWith("node_modules/") || normalized.contains("/node_modules/"))) {
            return Pair(true, "Node dependencies (node_modules)")
        }

        // Custom glob-like patterns
        for (pattern in customPatterns) {
            val trimmed = pattern.trim()
            if (trimmed.isEmpty()) continue
            if (matchesPattern(normalized, trimmed)) {
                return Pair(true, "Matched custom ignore rule: $trimmed")
            }
        }

        return Pair(false, null)
    }

    private fun matchesPattern(path: String, pattern: String): Boolean {
        if (pattern.startsWith("*.")) {
            val ext = pattern.substring(1) // e.g. .tmp
            return path.endsWith(ext)
        }
        if (pattern.endsWith("/")) {
            val dir = pattern.trimEnd('/')
            return path == dir || path.startsWith("$dir/") || path.contains("/$dir/")
        }
        return path == pattern || path.endsWith("/$pattern")
    }
}
