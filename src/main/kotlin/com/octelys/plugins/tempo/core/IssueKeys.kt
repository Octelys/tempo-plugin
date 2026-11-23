package com.octelys.plugins.tempo.core

/**
 * Recognizes Jira issue keys (`ABC-123`) in free text such as branch names.
 */
object IssueKeys {
    private val keyInText = Regex("""(?<![A-Za-z0-9])([A-Z][A-Z0-9_]+-[1-9][0-9]*)(?![0-9])""")
    private val wholeKey = Regex("""[A-Z][A-Z0-9_]+-[1-9][0-9]*""")

    /**
     * Returns the first issue key found in [text], matching case-insensitively so that
     * `features/abc-123-some-slug` yields `ABC-123`.
     */
    fun find(text: String?): String? {
        if (text.isNullOrBlank()) return null
        return keyInText.find(text.uppercase())?.groupValues?.get(1)
    }

    /** Normalizes user input (`  abc-12 `) to a key (`ABC-12`), or `null` when it is not one. */
    fun normalize(input: String?): String? {
        val candidate = input?.trim()?.uppercase() ?: return null
        return candidate.takeIf { wholeKey.matches(it) }
    }
}
