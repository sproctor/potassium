package com.seanproctor.potassium.updater.internal

import com.seanproctor.potassium.updater.runtime.Platform
import java.io.File
import kotlin.system.exitProcess

@Suppress("TooManyFunctions")
internal object PlatformInstaller {
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
        val pid = ProcessHandle.current().pid()
        val currentAppImage =
            System.getenv("APPIMAGE")
                ?: error("APPIMAGE environment variable not set — update is only supported from a packaged AppImage")

        val relaunchCmd =
            if (restart) {
                "\n# Relaunch in a fully detached process\nnohup \"\$OLD_FILE\" > /dev/null 2>&1 &\n"
            } else {
                ""
            }

        val script = File(System.getProperty("java.io.tmpdir"), "nucleus-update.sh")
        script.writeText(
            """
            |#!/usr/bin/env bash
            |set -e
            |
            |# Ignore SIGHUP to survive parent process exit
            |trap '' HUP
            |
            |NEW_FILE=${shLiteral(newAppImage.absolutePath)}
            |OLD_FILE=${shLiteral(currentAppImage)}
            |APP_PID=$pid
            |
            |# Wait for the app process to fully exit
            |while kill -0 "${'$'}APP_PID" 2>/dev/null; do
            |    sleep 0.5
            |done
            |
            |# Wait for the AppImage FUSE mount to fully clean up
            |sleep 1
            |
            |# Replace the old AppImage with the new one
            |mv -f "${'$'}NEW_FILE" "${'$'}OLD_FILE"
            |chmod +x "${'$'}OLD_FILE"
            |$relaunchCmd
            |# Clean up this script
            |rm -f "${'$'}{0}"
            """.trimMargin(),
        )
        script.setExecutable(true)

        // Use setsid to start the script in a new session, fully detached
        // from the current process tree
        ProcessBuilder("setsid", "bash", script.absolutePath)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
    }

    private fun installLinuxPackage(
        packageFile: File,
        extension: String,
        restart: Boolean,
    ) {
        val pid = ProcessHandle.current().pid()
        val launcher =
            resolveLinuxLauncher()
                ?: error("Cannot resolve application launcher from java.home")

        val installCmd =
            when (extension) {
                "deb" -> "pkexec dpkg -i \"\$PKG_FILE\""
                "rpm" -> "pkexec rpm -U \"\$PKG_FILE\""
                else -> error("Unsupported package format: $extension")
            }

        val relaunchCmd =
            if (restart) {
                "\n# Relaunch the application\nnohup \"\$APP_LAUNCHER\" > /dev/null 2>&1 &\n"
            } else {
                ""
            }

        val script = File(System.getProperty("java.io.tmpdir"), "nucleus-update.sh")
        script.writeText(
            """
            |#!/usr/bin/env bash
            |
            |# Ignore SIGHUP to survive parent process exit
            |trap '' HUP
            |
            |PKG_FILE=${shLiteral(packageFile.absolutePath)}
            |APP_PID=$pid
            |APP_LAUNCHER=${shLiteral(launcher.absolutePath)}
            |
            |# Wait for the app process to fully exit
            |while kill -0 "${'$'}APP_PID" 2>/dev/null; do
            |    sleep 0.5
            |done
            |
            |sleep 1
            |
            |# Install the package (shows graphical authentication dialog)
            |# Do not use set -e: dpkg/rpm may return non-zero on warnings,
            |# which would prevent the application from relaunching.
            |$installCmd
            |
            |# Clean up the package file
            |rm -f "${'$'}PKG_FILE"
            |$relaunchCmd
            |# Clean up this script
            |rm -f "${'$'}{0}"
            """.trimMargin(),
        )
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
        val pid = ProcessHandle.current().pid()
        startDetachedMacScript(
            MacInstallScripts.forDmg(
                dmgFile = dmgFile.absolutePath,
                appPath = appBundle.absolutePath,
                // Per-process mount point: a fixed one would collide with a concurrent update.
                mountPoint = File(System.getProperty("java.io.tmpdir"), "potassium-dmg-$pid").absolutePath,
                pid = pid,
                restart = restart,
            ),
        )
    }

    private fun currentAppBundleOrFail(): File =
        resolveCurrentAppBundle()
            ?: error("Cannot resolve current .app bundle from java.home")

    /**
     * Starts [body] detached, so it outlives the exit this updater is about to perform. The
     * script deletes itself via `$0` when it finishes.
     */
    private fun startDetachedMacScript(body: String) {
        val script = File(System.getProperty("java.io.tmpdir"), "nucleus-update.sh")
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

        val script = File(System.getProperty("java.io.tmpdir"), "nucleus-update.ps1")
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
            |Remove-Item ${psLiteral(script.absolutePath)} -Force -ErrorAction SilentlyContinue
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
