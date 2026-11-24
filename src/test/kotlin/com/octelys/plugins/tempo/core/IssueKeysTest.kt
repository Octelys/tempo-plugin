package com.octelys.plugins.tempo.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IssueKeysTest {

    @Test
    fun `find returns the key of a feature branch`() {
        assertEquals("ABC-123", IssueKeys.find("features/ABC-123-new-login"))
    }

    @Test
    fun `find matches lowercase branch names`() {
        assertEquals("ABC-7", IssueKeys.find("bugs/abc-7-fix-crash"))
    }

    @Test
    fun `find returns the first key when there are several`() {
        assertEquals("ABC-1", IssueKeys.find("ABC-1-and-DEF-2"))
    }

    @Test
    fun `find ignores branches without a key`() {
        assertNull(IssueKeys.find("main"))
        assertNull(IssueKeys.find("release/2026-11"))
        assertNull(IssueKeys.find(null))
    }

    @Test
    fun `find ignores keys glued to other words or numbers`() {
        assertNull(IssueKeys.find("1ABC-12"))
        assertNull(IssueKeys.find("ABC-0"))
    }

    @Test
    fun `find with no pattern uses the built-in detection`() {
        assertEquals("ABC-123", IssueKeys.find("features/ABC-123-new-login", null))
    }

    @Test
    fun `find with a pattern uses its named key group`() {
        val pattern = IssueKeys.compilePattern("""^\w+/(?<prefix>\w+)/(?<key>[A-Z]+-\d+)""")
        assertEquals("PAY-42", IssueKeys.find("team/web/PAY-42-checkout", pattern))
    }

    @Test
    fun `find with a pattern uses group 1 when there is no key group`() {
        val pattern = IssueKeys.compilePattern("""^dev/(\w+-\d+)""")
        assertEquals("ABC-9", IssueKeys.find("dev/abc-9_fix", pattern))
    }

    @Test
    fun `find with a pattern uses the whole match when there are no groups`() {
        val pattern = IssueKeys.compilePattern("""OPS-\d+""")
        assertEquals("OPS-3", IssueKeys.find("feature/ABC-1/OPS-3", pattern))
    }

    @Test
    fun `find with a pattern rejects matches that are not issue keys`() {
        val pattern = IssueKeys.compilePattern("""^release/(.+)$""")
        assertNull(IssueKeys.find("release/2026-11", pattern))
        assertNull(IssueKeys.find("main", pattern))
    }

    @Test
    fun `compilePattern treats blank as no pattern`() {
        assertNull(IssueKeys.compilePattern(""))
        assertNull(IssueKeys.compilePattern("  "))
        assertNull(IssueKeys.compilePattern(null))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `compilePattern rejects an invalid regular expression`() {
        IssueKeys.compilePattern("(unclosed")
    }

    @Test
    fun `normalize accepts and uppercases a key`() {
        assertEquals("ABC-12", IssueKeys.normalize("  abc-12 "))
    }

    @Test
    fun `normalize rejects anything that is not exactly a key`() {
        assertNull(IssueKeys.normalize("ABC-12 extra"))
        assertNull(IssueKeys.normalize("ABC"))
        assertNull(IssueKeys.normalize(""))
        assertNull(IssueKeys.normalize(null))
    }
}
