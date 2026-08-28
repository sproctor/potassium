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
        // mkfifo as the canary: a command no install script ever runs legitimately, so a line
        // starting with it can only come from the injection escaping its quotes.
        val hostile =
            MacInstallScripts.forDmg(
                dmgFile = "/tmp/App';mkfifo /tmp/pwned;'.dmg",
                appPath = "/Users/o'brien/Applications/App.app",
                mountPoint = "/tmp/potassium-dmg-1",
                pid = 1,
                restart = true,
            )
        assertParses(hostile)
        // The injected command must survive as literal text rather than becoming a statement.
        assertTrue(hostile.contains("mkfifo /tmp/pwned"))
        assertFalse("injected command must stay inside a quoted literal", runsCommand(hostile, "mkfifo"))
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
    fun `zip script refuses to replace a different application at the adopted name`() {
        // Adopting the archive's bundle name can collide with an unrelated .app in the install
        // directory; the swap would otherwise move it aside and delete it.
        val guard = zip.indexOf("Refusing to replace")
        val backup = zip.indexOf("mv \"\$TARGET\" \"\$BACKUP\"")
        assertTrue("the collision guard must be present", guard >= 0)
        assertTrue("the guard must precede the swap", guard < backup)
        assertTrue(zip.contains("EXISTING_BUNDLE_ID"))
    }

    @Test
    fun `dmg script detaches the image on every exit path`() {
        assertTrue(dmg.contains("trap "))
        assertTrue(dmg.contains("hdiutil detach"))
    }

    @Test
    fun `both scripts refresh the icon caches after the swap and before relaunch`() {
        // Finder and the Dock cache icons by bundle record; replacing the bundle in place is when
        // that cache goes stale. The refresh must come after the new bundle is in place and before
        // the relaunch, and must be best-effort (guarded), since the update itself already succeeded.
        for ((name, script, bundleVar) in listOf(
            Triple("zip", zip, "TARGET"),
            Triple("dmg", dmg, "APP_PATH"),
        )) {
            val touch = script.indexOf("touch \"\$$bundleVar\"")
            val lsregister = script.indexOf("\"\$LSREGISTER\" -f \"\$$bundleVar\"")
            val relaunch = script.indexOf("open \"\$$bundleVar\"")
            assertTrue("$name: the mtime bump must be present", touch >= 0)
            assertTrue("$name: the Launch Services refresh must be present", lsregister >= 0)
            assertTrue("$name: the refresh must precede the relaunch", touch < relaunch && lsregister < relaunch)
            assertTrue("$name: the refresh must be best-effort", script.contains(">/dev/null 2>&1 || true"))
        }
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
