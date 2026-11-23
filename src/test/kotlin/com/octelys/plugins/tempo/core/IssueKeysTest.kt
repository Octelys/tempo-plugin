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
