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
        packageFile: String = "/tmp/app_1.2.3_amd64.$extension",
        launcher: String = "/opt/app/bin/App",
        workingDir: String = "/home/user",
        pid: Long = 4321,
        logFile: String = "/home/user/.local/share/app/potassium-install.log",
        failureFile: String = "/home/user/.local/share/app/potassium-install-failure",
        packageDir: String? = "/tmp/potassium-update-123",
    ) = LinuxInstallScripts.forPackage(
        packageFile = packageFile,
        extension = extension,
        launcher = launcher,
        workingDir = workingDir,
        pid = pid,
        restart = restart,
        version = "1.2.3",
        runningVersion = "1.2.2",
        logFile = logFile,
        failureFile = failureFile,
        packageDir = packageDir,
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

    @Test
    fun `package script records a failed install and still relaunches`() {
        // The regression: another package manager held the dpkg lock, so dpkg exited 2 at once.
        // The script discarded its output and relaunched the old version, which looked like an
        // update that silently did nothing.
        val run =
            runPackageUpdate(
                pkexec = "echo 'dpkg: error: dpkg frontend lock was locked by another process' >&2; exit 2",
            )

        assertTrue("the app must be relaunched", run.relaunched)
        assertEquals("version=1.2.3\nrunningVersion=1.2.2\nexitCode=2\n", run.failureFile.readText())
        assertTrue(run.logFile.readText().contains("frontend lock was locked by another process"))
        assertFalse("the package must be removed", run.packageFile.exists())
        assertFalse("the package's directory must be removed", run.packageFile.parentFile.exists())
    }

    @Test
    fun `package script leaves alone a directory the updater did not create`() {
        // installAndRestart takes any file: a deb the caller keeps in ~/Downloads must not take
        // that directory with it, even when it was the only file there.
        val run = runPackageUpdate(pkexec = "exit 0", ownPackageDir = false)

        assertTrue("the app must be relaunched", run.relaunched)
        assertFalse("the package must be removed", run.packageFile.exists())
        assertTrue("the caller's directory must stay", run.packageFile.parentFile.isDirectory)
    }

    @Test
    fun `package script leaves no failure record after a successful install`() {
        val run =
            runPackageUpdate(
                pkexec = "echo \"Unpacking app (1.2.3)\"; exit 0",
                staleFailure = true,
            )

        assertTrue("the app must be relaunched", run.relaunched)
        assertFalse("a record from an earlier attempt must be cleared", run.failureFile.exists())
        assertTrue(run.logFile.readText().contains("Unpacking app"))
    }

    @Test
    fun `package script installs even when the log cannot be written`() {
        // The log's directory cannot be created because a regular file is in the way.
        val blocker = tmp.newFile("blocker")
        val run =
            runPackageUpdate(
                pkexec = "exit 0",
                logFile = File(blocker, "potassium-install.log"),
            )

        assertTrue("the install must run and the app be relaunched", run.relaunched)
        assertTrue("the installer must have been invoked", run.invoked)
    }

    private class PackageRun(
        val relaunched: Boolean,
        val invoked: Boolean,
        val packageFile: File,
        val logFile: File,
        val failureFile: File,
    )

    /**
     * Runs the deb script for real with a stand-in `pkexec` on PATH whose body is [pkexec], and a
     * launcher that records that it was relaunched.
     */
    private fun runPackageUpdate(
        pkexec: String,
        staleFailure: Boolean = false,
        logFile: File = File(tmp.root, "state/potassium-install.log"),
        ownPackageDir: Boolean = true,
    ): PackageRun {
        val bash = File("/bin/bash").takeIf { it.canExecute() }
        assumeTrue("bash is unavailable on this host", bash != null)

        val bin = tmp.newFolder("bin")
        val invokedMarker = File(tmp.root, "invoked")
        File(bin, "pkexec").apply {
            writeText("#!/bin/sh\ntouch '${invokedMarker.path}'\n$pkexec\n")
            setExecutable(true)
        }
        val relaunchMarker = File(tmp.root, "relaunched")
        val launcher =
            File(tmp.root, "App").apply {
                writeText("#!/bin/sh\ntouch '${relaunchMarker.path}'\n")
                setExecutable(true)
            }
        val packageDir = tmp.newFolder(if (ownPackageDir) "potassium-update-123" else "Downloads")
        val packageFile = File(packageDir, "app_1.2.3_amd64.deb").apply { writeText("deb") }
        val failureFile = File(tmp.root, "state/potassium-install-failure")
        if (staleFailure) {
            failureFile.parentFile.mkdirs()
            failureFile.writeText("version=1.2.2\nexitCode=2\n")
        }
        val deadPid = ProcessBuilder("true").start().also { it.waitFor() }.pid()
        val script =
            File(scriptDir.apply { mkdirs() }, "updater.sh").apply {
                writeText(
                    pkg(
                        "deb",
                        packageFile = packageFile.path,
                        launcher = launcher.path,
                        workingDir = tmp.root.path,
                        pid = deadPid,
                        logFile = logFile.path,
                        failureFile = failureFile.path,
                        packageDir = packageDir.path.takeIf { ownPackageDir },
                    ),
                )
            }

        val process =
            ProcessBuilder(bash!!.path, script.path)
                .apply { environment()["PATH"] = "${bin.path}:${environment()["PATH"]}" }
                .redirectErrorStream(true)
                .start()
        val output = process.inputStream.bufferedReader().readText()
        assertTrue("the script did not finish", process.waitFor(30, TimeUnit.SECONDS))
        assertEquals("the script failed:\n$output", 0, process.exitValue())

        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!relaunchMarker.exists() && System.nanoTime() < deadline) Thread.sleep(50)
        return PackageRun(relaunchMarker.exists(), invokedMarker.exists(), packageFile, logFile, failureFile)
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
