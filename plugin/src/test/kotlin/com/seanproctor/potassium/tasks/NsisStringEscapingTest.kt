package com.seanproctor.potassium.tasks

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Values interpolated into the generated NSIS protocol script are app-supplied (a protocol name or
 * `appName`), and NSIS reads `$` as the start of a variable reference and `"` as the end of a
 * string. Without escaping, `Cash$App` or `Acme"Tools` produce a `WriteRegStr` line that registers
 * the wrong value or fails to compile.
 */
class NsisStringEscapingTest {
    @Test
    fun `a dollar sign is doubled`() {
        assertEquals("Cash$\$App", "Cash\$App".escapeForNsisString())
    }

    @Test
    fun `a quote is escaped with the NSIS dollar-backslash form`() {
        assertEquals("Acme$\\\"Tools", "Acme\"Tools".escapeForNsisString())
    }

    @Test
    fun `backslashes are left alone so registry key paths stay intact`() {
        assertEquals("Software\\Classes\\myapp", "Software\\Classes\\myapp".escapeForNsisString())
    }

    @Test
    fun `ordinary and non-ASCII names are unchanged`() {
        assertEquals("My App", "My App".escapeForNsisString())
        assertEquals("יישום", "יישום".escapeForNsisString())
    }

    @Test
    fun `a dollar is escaped before a quote so the escape is not itself doubled`() {
        // "$\"" must come out as an NSIS-escaped quote, not as an escaped dollar followed by a
        // stray quote that would terminate the string.
        assertEquals("a$\$b$\\\"c", "a\$b\"c".escapeForNsisString())
    }
}
