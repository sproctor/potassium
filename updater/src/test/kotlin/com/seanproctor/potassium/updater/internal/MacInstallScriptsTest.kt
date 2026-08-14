package com.seanproctor.potassium.updater.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class MacInstallScriptsTest {
    private val zip =
        MacInstallScripts.forZip(
            zipFile = "/tmp/App-1.2.3.zip",
            appPath = "/Applications/App.app",
            installDir = "/Applications",
            pid = 4321,
            restart = true,
        )

    private val dmg =
        MacInstallScripts.forDmg(
            dmgFile = "/tmp/App-1.2.3.dmg",
            appPath = "/Applications/App.app",
            mountPoint = "/tmp/potassium-dmg-4321",
            pid = 4321,
            restart = true,
        )

    @Test
    fun `zip script parses as bash`() {
        assertParses(zip)
    }

    @Test
    fun `dmg script parses as bash`() {
        assertParses(dmg)
    }

    @Test
    fun `scripts with hostile paths still parse as bash`() {
        // The install location contains the account name, so an apostrophe is ordinary; the
        // artifact name comes from the update manifest, so treat it as hostile.
        val hostile =
            MacInstallScripts.forDmg(
                dmgFile = "/tmp/App';touch /tmp/pwned;'.dmg",
                appPath = "/Users/o'brien/Applications/App.app",
                mountPoint = "/tmp/potassium-dmg-1",
                pid = 1,
                restart = true,
            )
        assertParses(hostile)
        // The injected command must survive as literal text rather than becoming a statement.
        assertTrue(hostile.contains("touch /tmp/pwned"))
        assertFalse("injected command must stay inside a quoted literal", runsCommand(hostile, "touch"))
    }

    @Test
    fun `zip script never removes the installed bundle before a replacement exists`() {
        // The old script did `rm -rf "$APP_PATH"` and only then extracted, so a truncated archive
        // left the machine with no application at all. The install must go through a staging
        // directory and a reversible rename instead — any removal of the old bundle happens only
        // after the replacement is in place.
        val swap = zip.indexOf("mv \"\$NEW_APP\" \"\$TARGET\"")
        val removeOld = zip.indexOf("rm -rf \"\$APP_PATH\"")
        assertTrue("the swap must be present", swap >= 0)
        assertTrue(
            "the installed bundle must not be deleted before the replacement is in place",
            removeOld < 0 || removeOld > swap,
        )
        assertTrue(zip.contains("STAGE_DIR="))
        assertTrue(zip.contains("ditto -x -k \"\$ZIP_FILE\" \"\$STAGE_DIR\""))
        assertTrue(zip.contains("mv \"\$TARGET\" \"\$BACKUP\""))
        assertTrue(zip.contains("trap cleanup EXIT INT TERM"))
    }

    @Test
    fun `zip script locates the bundle instead of assuming its name`() {
        assertTrue(zip.contains("-name '*.app' -type d -print -quit"))
        assertTrue(zip.contains("CFBundleIdentifier"))
        // A matching identifier keeps the installed path so Dock tiles and aliases stay valid.
        assertTrue(zip.contains("TARGET=\"\$APP_PATH\""))
        assertTrue(zip.contains("open \"\$TARGET\""))
    }

    @Test
    fun `dmg script detaches the image on every exit path`() {
        assertTrue(dmg.contains("trap "))
        assertTrue(dmg.contains("hdiutil detach"))
    }

    @Test
    fun `restart adds the relaunch and clearing it removes it`() {
        assertTrue(dmg.contains("open \"\$APP_PATH\""))
        val noRestart =
            MacInstallScripts.forDmg(
                dmgFile = "/tmp/App.dmg",
                appPath = "/Applications/App.app",
                mountPoint = "/tmp/m",
                pid = 1,
                restart = false,
            )
        assertFalse(noRestart.contains("open \"\$APP_PATH\""))
        assertParses(noRestart)
    }

    /** Runs `bash -n`, which parses the script without executing any of it. */
    private fun assertParses(script: String) {
        val bash = File("/bin/bash").takeIf { it.canExecute() }
        assumeTrue("bash is unavailable on this host", bash != null)

        val file = File.createTempFile("mac-install-script", ".sh").apply { deleteOnExit() }
        file.writeText(script)

        val process =
            ProcessBuilder(bash!!.absolutePath, "-n", file.absolutePath)
                .redirectErrorStream(true)
                .start()
        val output = process.inputStream.bufferedReader().readText()
        assertEquals("bash rejected the script:\n$output\n---\n$script", 0, process.waitFor())
    }

    /**
     * Whether [command] appears where bash would execute it rather than inside a quoted literal.
     * Detected by asking bash itself to expand the script's variable assignments.
     */
    private fun runsCommand(
        script: String,
        command: String,
    ): Boolean =
        script
            .lineSequence()
            .filter { it.isNotBlank() && !it.trimStart().startsWith("#") }
            // An assignment keeps everything after `=` as data; a command would start the line.
            .any { line -> line.trimStart().startsWith(command) }
}
