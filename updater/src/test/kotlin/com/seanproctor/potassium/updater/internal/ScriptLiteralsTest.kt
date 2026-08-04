package com.seanproctor.potassium.updater.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScriptLiteralsTest {
    @Test
    fun `powershell literal quotes a plain path`() {
        assertEquals("'C:\\Program Files\\App\\App.exe'", psLiteral("C:\\Program Files\\App\\App.exe"))
    }

    @Test
    fun `powershell literal doubles an apostrophe in the user profile`() {
        assertEquals(
            "'C:\\Users\\O''Brien\\AppData\\Local\\Temp\\App-Setup.exe'",
            psLiteral("C:\\Users\\O'Brien\\AppData\\Local\\Temp\\App-Setup.exe"),
        )
    }

    @Test
    fun `powershell literal neutralizes an injecting manifest file name`() {
        // A manifest could name the artifact so it closes the literal and appends statements.
        val hostile = "C:\\Temp\\App';Start-Process calc;'.exe"
        val quoted = psLiteral(hostile)

        assertEquals("'C:\\Temp\\App'';Start-Process calc;''.exe'", quoted)
        // Every quote after the opener is part of a doubled pair, so the literal never terminates early.
        assertTrue(quoted.startsWith("'") && quoted.endsWith("'"))
        assertEquals(hostile, unquotePowerShell(quoted))
    }

    @Test
    fun `shell literal quotes a plain path`() {
        assertEquals("'/opt/app/bin/App'", shLiteral("/opt/app/bin/App"))
    }

    @Test
    fun `shell literal neutralizes command substitution`() {
        // Inside the double quotes previously used, $(...) would have been executed by bash.
        assertEquals("'/tmp/app\$(id).AppImage'", shLiteral("/tmp/app\$(id).AppImage"))
    }

    @Test
    fun `shell literal escapes an embedded single quote`() {
        val hostile = "/home/o'brien/App';id;'.AppImage"
        assertEquals("'/home/o'\\''brien/App'\\'';id;'\\''.AppImage'", shLiteral(hostile))
    }

    /** Decodes a PowerShell single-quoted literal, mirroring the shell's own parsing. */
    private fun unquotePowerShell(literal: String): String =
        literal
            .removeSurrounding("'")
            .replace("''", "'")
}
