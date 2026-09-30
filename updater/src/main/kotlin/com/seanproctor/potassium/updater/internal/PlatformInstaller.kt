package com.seanproctor.potassium.updater.internal

import com.seanproctor.potassium.updater.runtime.Platform
import java.io.File
import java.nio.file.Files
import kotlin.system.exitProcess

@Suppress("TooManyFunctions")
internal object PlatformInstaller {
    private const val UPDATE_SCRIPT_UNIX = "updater.sh"
    private const val UPDATE_SCRIPT_WINDOWS = "updater.ps1"

    /**
     * A fresh, owner-only (`0700` on POSIX) directory for one update's detached script, which
     * removes the directory when it finishes.
     *
     * A predictable path in the shared temp directory (`/tmp` on Linux) would let another local
     * user create it first, owning the directory the script is written into, and replace the
     * script before it runs as this app's user. A new unguessable directory per update also keeps
     * two apps, or two updates, from colliding.
     */
    private fun createUpdateWorkDir(): File = Files.createTempDirectory("potassium-install-").toFile()

    fun install(
        file: File,
        platform: Platform,
        restart: Boolean = true,
    ) {
        val extension = file.name.substringAfterLast('.').lowercase()

        when {
            platform == Platform.MacOS && extension == "zip" -> installMacZip(file, restart)
            platform == Platform.MacOS && extension == "dmg" -> installMacDmg(file, restart)
            platform == Platform.Windows -> installWindows(file, extension, restart)
            platform == Platform.Linux && extension == "appimage" -> installLinuxAppImage(file, restart)
            platform == Platform.Linux && (extension == "deb" || extension == "rpm") ->
                installLinuxPackage(file, extension, restart)
            else -> handOffToDesktop(file, platform).start()
        }
        exitProcess(0)
    }

    /**
     * Opens [file] with the desktop's default handler, for formats this updater does not install
     * itself (macOS PKG, Linux tarballs and store packages).
     *
     * This is a hand-off: the graphical installer it launches reports no completion, so the app
     * cannot be relaunched afterwards and `restart` cannot be honored. Every format the updater
     * claims to self-update is routed to a real installer above.
     */
    private fun handOffToDesktop(
        file: File,
        platform: Platform,
    ): ProcessBuilder =
        when (platform) {
            Platform.Linux -> ProcessBuilder("xdg-open", file.absolutePath)
            Platform.MacOS -> ProcessBuilder("open", file.absolutePath)
            Platform.Windows -> error("Windows uses installWindows()")
            Platform.Unknown -> error("Unsupported platform: ${System.getProperty("os.name")}")
        }

    private fun installLinuxAppImage(
        newAppImage: File,
        restart: Boolean,
    ) {
        val currentAppImage =
            System.getenv("APPIMAGE")
                ?: error("APPIMAGE environment variable not set — update is only supported from a packaged AppImage")

        startDetachedLinuxScript(
            LinuxInstallScripts.forAppImage(
                newAppImage = newAppImage.absolutePath,
                currentAppImage = currentAppImage,
                workingDir = workingDir(),
                pid = ProcessHandle.current().pid(),
                restart = restart,
            ),
        )
    }

    private fun installLinuxPackage(
        packageFile: File,
        extension: String,
        restart: Boolean,
    ) {
        val launcher =
            resolveLinuxLauncher()
                ?: error("Cannot resolve application launcher from java.home")

        startDetachedLinuxScript(
            LinuxInstallScripts.forPackage(
                packageFile = packageFile.absolutePath,
                extension = extension,
                launcher = launcher.absolutePath,
                workingDir = workingDir(),
                pid = ProcessHandle.current().pid(),
                restart = restart,
            ),
        )
    }

    /** The directory the app was started in; the relaunch returns to it while it still exists. */
    private fun workingDir(): String = System.getProperty("user.dir") ?: "/"

    /**
     * Starts [body] with setsid, in a new session fully detached from the current process tree,
     * so it outlives the exit this updater is about to perform. The script deletes itself via
     * `$0`, and then its directory, when it finishes.
     */
    private fun startDetachedLinuxScript(body: String) {
        val script = File(createUpdateWorkDir(), UPDATE_SCRIPT_UNIX)
        script.writeText(body)
        script.setExecutable(true)

        ProcessBuilder("setsid", "bash", script.absolutePath)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
    }

    /**
     * Resolves the jpackage launcher on Linux.
     * jpackage structure: /opt/<app>/bin/<Launcher> with java.home = /opt/<app>/lib/runtime
     */
    private fun resolveLinuxLauncher(): File? {
        val javaHome = System.getProperty("java.home") ?: return null
        // java.home = /opt/<app>/lib/runtime → parent = lib → parent = /opt/<app>
        val appRoot = File(javaHome).parentFile?.parentFile ?: return null
        val binDir = File(appRoot, "bin")
        if (!binDir.isDirectory) return null
        return binDir.listFiles()?.firstOrNull { it.canExecute() }
    }

    private fun installMacZip(
        zipFile: File,
        restart: Boolean,
    ) {
        val appBundle = currentAppBundleOrFail()
        startDetachedMacScript(
            MacInstallScripts.forZip(
                zipFile = zipFile.absolutePath,
                appPath = appBundle.absolutePath,
                installDir = appBundle.parentFile.absolutePath,
                pid = ProcessHandle.current().pid(),
                restart = restart,
            ),
        )
    }

    private fun installMacDmg(
        dmgFile: File,
        restart: Boolean,
    ) {
        val appBundle = currentAppBundleOrFail()
        val workDir = createUpdateWorkDir()
        startDetachedMacScript(
            MacInstallScripts.forDmg(
                dmgFile = dmgFile.absolutePath,
                appPath = appBundle.absolutePath,
                // Inside this update's private directory, so no other update can collide with it.
                mountPoint = File(workDir, "dmg-mount").absolutePath,
                pid = ProcessHandle.current().pid(),
                restart = restart,
            ),
            workDir,
        )
    }

    private fun currentAppBundleOrFail(): File =
        resolveCurrentAppBundle()
            ?: error("Cannot resolve current .app bundle from java.home")

    /**
     * Starts [body] detached, so it outlives the exit this updater is about to perform. The
     * script deletes itself via `$0`, and then its directory, when it finishes.
     */
    private fun startDetachedMacScript(
        body: String,
        workDir: File = createUpdateWorkDir(),
    ) {
        val script = File(workDir, UPDATE_SCRIPT_UNIX)
        script.writeText(body)
        script.setExecutable(true)

        ProcessBuilder("bash", script.absolutePath)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()

        // exitProcess(0) is called by install() right after this returns
    }

    private fun resolveCurrentAppBundle(): File? {
        val javaHome = System.getProperty("java.home") ?: return null
        var dir = File(javaHome)
        while (dir.parentFile != null) {
            if (dir.name.endsWith(".app")) return dir
            dir = dir.parentFile
        }
        return null
    }

    private fun installWindows(
        file: File,
        extension: String,
        restart: Boolean,
    ) {
        val pid = ProcessHandle.current().pid()
        val installerCmd =
            when (extension) {
                // msiexec needs the path double-quoted in its own argument string; that inner
                // quoting is part of the value, and psLiteral quotes the whole thing for PowerShell.
                "msi" ->
                    "Start-Process msiexec -ArgumentList '/i', " +
                        "${psLiteral("\"${file.absolutePath}\"")}, '/passive' -Wait"
                // --updated keeps the installer in update mode (shortcut preservation,
                // close-wait handling). --force-run is deliberately omitted: the installer's
                // own relaunch would pass an --updated argument to the app and depends on the
                // Start-Menu shortcut; the script relaunches the exact launcher path instead.
                else -> "Start-Process ${psLiteral(file.absolutePath)} -ArgumentList '/S', '--updated' -Wait"
            }

        val launcher = if (restart) resolveWindowsLauncher() else null
        val relaunchCmd =
            if (launcher != null) {
                "\n|# Relaunch the application\n|Start-Process ${psLiteral(launcher.absolutePath)}"
            } else {
                ""
            }

        val workDir = createUpdateWorkDir()
        val script = File(workDir, UPDATE_SCRIPT_WINDOWS)
        script.writeText(
            """
            |# Wait for the app process to fully exit
            |while (Get-Process -Id $pid -ErrorAction SilentlyContinue) {
            |    Start-Sleep -Milliseconds 500
            |}
            |
            |# Run the installer silently
            |$installerCmd
            |$relaunchCmd
            |# Clean up
            |Remove-Item ${psLiteral(file.absolutePath)} -Force -ErrorAction SilentlyContinue
            |Remove-Item ${psLiteral(workDir.absolutePath)} -Recurse -Force -ErrorAction SilentlyContinue
            """.trimMargin(),
        )

        ProcessBuilder(
            "powershell",
            "-ExecutionPolicy",
            "Bypass",
            "-WindowStyle",
            "Hidden",
            "-File",
            script.absolutePath,
        ).redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
    }

    /**
     * Resolves the jpackage launcher on Windows.
     *
     * The running process is the launcher itself, so its command path is the authoritative
     * source. The fallback scans the install dir (java.home = C:\...\<AppName>\runtime →
     * parent = install dir), skipping electron-builder's NSIS uninstaller
     * ("Uninstall <ProductName>.exe"), which also lives there.
     */
    private fun resolveWindowsLauncher(): File? {
        ProcessHandle
            .current()
            .info()
            .command()
            .orElse(null)
            ?.let(::File)
            ?.takeIf { it.isFile && it.name.endsWith(".exe", ignoreCase = true) }
            ?.let { return it }

        val javaHome = System.getProperty("java.home") ?: return null
        val appRoot = File(javaHome).parentFile ?: return null
        if (!appRoot.isDirectory) return null
        return appRoot.listFiles()?.firstOrNull {
            it.isFile && it.name.endsWith(".exe", ignoreCase = true) && !isNsisUninstallerName(it.name)
        }
    }

    /**
     * Whether [fileName] is the uninstaller electron-builder's NSIS installer writes into the
     * install root (`Uninstall <ProductName>.exe`). Relaunching that after an update would show
     * the app's own uninstall prompt instead of starting the app.
     */
    private fun isNsisUninstallerName(fileName: String): Boolean =
        fileName.startsWith("Uninstall", ignoreCase = true) &&
            fileName.endsWith(".exe", ignoreCase = true)
}
