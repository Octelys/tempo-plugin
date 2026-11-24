package com.octelys.plugins.tempo.core

/**
 * Recognizes Jira issue keys (`ABC-123`) in free text such as branch names.
 */
object IssueKeys {
    private val keyInText = Regex("""(?<![A-Za-z0-9])([A-Z][A-Z0-9_]+-[1-9][0-9]*)(?![0-9])""")
    private val wholeKey = Regex("""[A-Z][A-Z0-9_]+-[1-9][0-9]*""")

    /** Name of the capturing group that, when present in a custom pattern, holds the issue key. */
    const val KEY_GROUP = "key"

    /**
     * Returns the first issue key found in [text], matching case-insensitively so that
     * `features/abc-123-some-slug` yields `ABC-123`.
     */
    fun find(text: String?): String? {
        if (text.isNullOrBlank()) return null
        return keyInText.find(text.uppercase())?.groupValues?.get(1)
    }

    /**
     * Returns the issue key that [pattern] extracts from [text], or uses [find] when there is no
     * [pattern]. The key is the group named [KEY_GROUP], else group 1, else the whole match; it
     * must still look like an issue key once uppercased.
     */
    fun find(text: String?, pattern: Regex?): String? {
        if (pattern == null) return find(text)
        if (text.isNullOrBlank()) return null
        val match = pattern.find(text) ?: return null
        val candidate = runCatching { match.groups[KEY_GROUP] }.getOrNull()?.value
            ?: match.groups.drop(1).firstOrNull()?.value
            ?: match.value
        return normalize(candidate)
    }

    /**
     * Compiles a user-supplied branch pattern. Blank means "use the built-in detection" and yields
     * `null`; an invalid regular expression throws [IllegalArgumentException] with the reason.
     */
    fun compilePattern(source: String?): Regex? {
        if (source.isNullOrBlank()) return null
        return try {
            Regex(source.trim())
        } catch (e: Exception) {
            throw IllegalArgumentException(e.message ?: "Invalid regular expression", e)
        }
    }

    /** Normalizes user input (`  abc-12 `) to a key (`ABC-12`), or `null` when it is not one. */
    fun normalize(input: String?): String? {
        val candidate = input?.trim()?.uppercase() ?: return null
        return candidate.takeIf { wholeKey.matches(it) }
    }
}
