package com.seanproctor.potassium.updater.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.TimeUnit

class LinuxInstallScriptsTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun appImage(
        restart: Boolean = true,
        newAppImage: String = "/tmp/App-1.2.3.AppImage",
        currentAppImage: String = "/home/user/Applications/App.AppImage",
        workingDir: String = "/home/user",
        pid: Long = 4321,
    ) = LinuxInstallScripts.forAppImage(newAppImage, currentAppImage, workingDir, pid, restart)

    private fun pkg(
        extension: String,
        restart: Boolean = true,
    ) = LinuxInstallScripts.forPackage(
        packageFile = "/tmp/app_1.2.3_amd64.$extension",
        extension = extension,
        launcher = "/opt/app/bin/App",
        workingDir = "/home/user",
        pid = 4321,
        restart = restart,
    )

    @Test
    fun `scripts parse as bash with and without restart`() {
        for (restart in listOf(true, false)) {
            assertParses(appImage(restart = restart))
            assertParses(pkg("deb", restart))
            assertParses(pkg("rpm", restart))
        }
    }

    @Test
    fun `restart adds the relaunch and clearing it removes it`() {
        assertTrue(appImage().contains("nohup \"\$OLD_FILE\""))
        assertFalse(appImage(restart = false).contains("nohup"))
        assertTrue(pkg("deb").contains("nohup \"\$APP_LAUNCHER\""))
        assertFalse(pkg("deb", restart = false).contains("nohup"))
    }

    @Test
    fun `package script installs with the matching tool`() {
        assertTrue(pkg("deb").contains("pkexec dpkg -i \"\$PKG_FILE\""))
        assertTrue(pkg("rpm").contains("pkexec rpm -U \"\$PKG_FILE\""))
    }

    @Test
    fun `scripts leave the inherited working directory before installing`() {
        for (script in listOf(appImage(), pkg("deb"))) {
            val cd = script.indexOf("cd -- '/home/user'")
            assertTrue("the working-directory guard must be present:\n$script", cd >= 0)
            assertTrue("the guard must follow the wait for exit", cd > script.indexOf("done"))
            assertTrue("the guard must precede the relaunch", cd < script.indexOf("nohup"))
        }
    }

    @Test
    fun `hostile paths round-trip as literals`() {
        val hostile = "/home/o'brien/\$(touch pwned)/App.AppImage"
        val script = appImage(newAppImage = hostile, workingDir = "/home/o'brien")
        assertParses(script)
        assertEquals(hostile, evaluatedAssignment(script, "NEW_FILE"))
    }

    @Test
    fun `appimage relaunch starts in an existing directory when the original was deleted`() {
        // The regression: the app was started from a directory that no longer exists. The script
        // inherited that working directory and passed it on, and a JVM without a working directory
        // aborts during startup, so the relaunched app died before showing anything.
        val home = tmp.newFolder("home")
        val gone = tmp.newFolder("gone")
        assertEquals(home.canonicalPath, runAppImageUpdate(workingDir = gone, home = home, deleteCwd = gone))
    }

    @Test
    fun `appimage relaunch keeps the original working directory while it exists`() {
        val home = tmp.newFolder("home")
        val original = tmp.newFolder("original")
        assertEquals(original.canonicalPath, runAppImageUpdate(workingDir = original, home = home))
    }

    @Test
    fun `appimage script removes its private directory when it finishes`() {
        val home = tmp.newFolder("home")
        runAppImageUpdate(workingDir = home, home = home)
        assertFalse("the script's directory must be removed", scriptDir.exists())
    }

    @Test
    fun `appimage script removes its private directory when the update fails`() {
        val bash = File("/bin/bash").takeIf { it.canExecute() }
        assumeTrue("bash is unavailable on this host", bash != null)

        // The new AppImage is missing, so the replacing `mv` fails and errexit aborts the script.
        val deadPid = ProcessBuilder("true").start().also { it.waitFor() }.pid()
        val script =
            File(scriptDir.apply { mkdirs() }, "updater.sh").apply {
                writeText(
                    appImage(
                        newAppImage = File(tmp.root, "missing.AppImage").path,
                        currentAppImage = File(tmp.root, "App.AppImage").path,
                        workingDir = tmp.root.path,
                        pid = deadPid,
                        restart = false,
                    ),
                )
            }

        val process = ProcessBuilder(bash!!.path, script.path).redirectErrorStream(true).start()
        process.inputStream.bufferedReader().readText()
        assertTrue("the script did not finish", process.waitFor(30, TimeUnit.SECONDS))
        assertTrue("the update must fail", process.exitValue() != 0)
        assertFalse("the script's directory must be removed", scriptDir.exists())
    }

    /** Stands in for the private per-update directory the installer writes the script into. */
    private val scriptDir: File get() = File(tmp.root, "potassium-install")

    /**
     * Runs the AppImage script for real against a stand-in AppImage that records the directory it
     * was relaunched in, and returns that directory. When [deleteCwd] is set, the script starts
     * inside that directory and it is removed first, as when the app's own directory has vanished.
     */
    private fun runAppImageUpdate(
        workingDir: File,
        home: File,
        deleteCwd: File? = null,
    ): String {
        val bash = File("/bin/bash").takeIf { it.canExecute() }
        assumeTrue("bash is unavailable on this host", bash != null)

        val marker = File(tmp.root, "relaunched-in")
        val current = File(tmp.root, "App.AppImage").apply { writeText("old") }
        // `pwd -P` fails like the JVM does when the directory is gone, leaving the marker empty.
        val update =
            File(tmp.root, "App-new.AppImage").apply {
                writeText("#!/bin/sh\npwd -P > '${marker.path}.tmp'; mv '${marker.path}.tmp' '${marker.path}'\n")
            }
        // A pid that has already exited, so the script does not wait.
        val deadPid = ProcessBuilder("true").start().also { it.waitFor() }.pid()
        val script =
            File(scriptDir.apply { mkdirs() }, "updater.sh").apply {
                writeText(
                    appImage(
                        newAppImage = update.path,
                        currentAppImage = current.path,
                        workingDir = workingDir.path,
                        pid = deadPid,
                    ),
                )
            }

        val command =
            if (deleteCwd != null) {
                listOf(
                    bash!!.path,
                    "-c",
                    "cd \"\$1\" && rmdir \"\$1\" && exec bash \"\$2\"",
                    "_",
                    deleteCwd.path,
                    script.path,
                )
            } else {
                listOf(bash!!.path, script.path)
            }
        val process =
            ProcessBuilder(command)
                .apply { environment()["HOME"] = home.path }
                .redirectErrorStream(true)
                .start()
        val output = process.inputStream.bufferedReader().readText()
        assertTrue("the script did not finish", process.waitFor(30, TimeUnit.SECONDS))
        assertEquals("the script failed:\n$output", 0, process.exitValue())

        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!marker.exists() && System.nanoTime() < deadline) Thread.sleep(50)
        assertTrue("the app was not relaunched", marker.exists())
        assertTrue("the update must replace the installed AppImage", current.readText().startsWith("#!/bin/sh"))
        return marker.readText().trim()
    }

    /** Runs `bash -n`, which parses the script without executing any of it. */
    private fun assertParses(script: String) {
        val bash = File("/bin/bash").takeIf { it.canExecute() }
        assumeTrue("bash is unavailable on this host", bash != null)

        val file = File.createTempFile("linux-install-script", ".sh").apply { deleteOnExit() }
        file.writeText(script)

        val process =
            ProcessBuilder(bash!!.absolutePath, "-n", file.absolutePath)
                .redirectErrorStream(true)
                .start()
        val output = process.inputStream.bufferedReader().readText()
        assertEquals("bash rejected the script:\n$output\n---\n$script", 0, process.waitFor())
    }

    /** The value bash assigns to [variable] when it evaluates the script's assignment line. */
    private fun evaluatedAssignment(
        script: String,
        variable: String,
    ): String {
        val bash = File("/bin/bash").takeIf { it.canExecute() }
        assumeTrue("bash is unavailable on this host", bash != null)

        val assignment = script.lineSequence().single { it.startsWith("$variable=") }
        val probe =
            ProcessBuilder(bash!!.absolutePath, "-c", "$assignment\nprintf '%s' \"\$$variable\"")
                .redirectErrorStream(true)
                .start()
        val output = probe.inputStream.bufferedReader().readText()
        assertEquals("bash rejected the assignment:\n$output", 0, probe.waitFor())
        return output
    }
}
