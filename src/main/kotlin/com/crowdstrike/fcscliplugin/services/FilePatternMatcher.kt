package com.crowdstrike.fcscliplugin.services

object FilePatternMatcher {

    /**
     * Returns true if [fileName] matches any pattern in [patterns].
     *
     * Three pattern forms are supported (all case-insensitive):
     *   *.ext        — matches any file whose extension equals ext
     *   Dockerfile   — exact filename match (no dot, no wildcard)
     *   tf           — bare extension, treated identically to *.tf
     */
    fun matches(fileName: String, patterns: List<String>): Boolean {
        val lowerName = fileName.lowercase()
        val extension = lowerName.substringAfterLast('.', "")

        for (pattern in patterns) {
            val lowerPattern = pattern.lowercase().trim()
            when {
                lowerPattern.startsWith("*.") -> {
                    val ext = lowerPattern.removePrefix("*.")
                    if (extension == ext) return true
                }
                '.' !in lowerPattern && '*' !in lowerPattern -> {
                    // Either a bare extension ("tf") or an exact filename ("dockerfile")
                    if (lowerName == lowerPattern || extension == lowerPattern) return true
                }
                else -> {
                    if (lowerName == lowerPattern) return true
                }
            }
        }
        return false
    }
}
