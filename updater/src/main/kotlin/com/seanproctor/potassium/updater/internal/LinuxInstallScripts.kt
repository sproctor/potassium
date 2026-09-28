package com.seanproctor.potassium.updater.internal

/**
 * Bodies of the detached scripts that apply a Linux update (AppImage replacement, or a deb/rpm
 * install) once the running app has exited, and optionally relaunch it. Kept separate from
 * [PlatformInstaller] so the generated text can be parsed, asserted and run in tests instead of
 * only being exercised by a real update.
 */
internal object LinuxInstallScripts {
    /** Replaces [currentAppImage] with [newAppImage] in place. */
    fun forAppImage(
        newAppImage: String,
        currentAppImage: String,
        workingDir: String,
        pid: Long,
        restart: Boolean,
    ): String =
        """
        |#!/usr/bin/env bash
        |set -e
        |
        |# Ignore SIGHUP to survive parent process exit
        |trap '' HUP
        |
        |NEW_FILE=${shLiteral(newAppImage)}
        |OLD_FILE=${shLiteral(currentAppImage)}
        |APP_PID=$pid
        |
        |${waitForExit()}
        |
        |# Wait for the AppImage FUSE mount to fully clean up
        |sleep 1
        |
        |${enterWorkingDir(workingDir)}
        |
        |# Replace the old AppImage with the new one
        |mv -f "${D}NEW_FILE" "${D}OLD_FILE"
        |chmod +x "${D}OLD_FILE"
        |${relaunch(restart, "OLD_FILE", "Relaunch in a fully detached process")}
        |# Clean up this script
        |rm -f "$D{0}"
        """.trimMargin()

    /** Installs [packageFile] (`deb` or `rpm`, per [extension]) over the jpackage install. */
    fun forPackage(
        packageFile: String,
        extension: String,
        launcher: String,
        workingDir: String,
        pid: Long,
        restart: Boolean,
    ): String {
        val installCmd =
            when (extension) {
                "deb" -> "pkexec dpkg -i \"${D}PKG_FILE\""
                "rpm" -> "pkexec rpm -U \"${D}PKG_FILE\""
                else -> error("Unsupported package format: $extension")
            }
        return """
            |#!/usr/bin/env bash
            |
            |# Ignore SIGHUP to survive parent process exit
            |trap '' HUP
            |
            |PKG_FILE=${shLiteral(packageFile)}
            |APP_PID=$pid
            |APP_LAUNCHER=${shLiteral(launcher)}
            |
            |${waitForExit()}
            |
            |sleep 1
            |
            |${enterWorkingDir(workingDir)}
            |
            |# Install the package (shows graphical authentication dialog)
            |# Do not use set -e: dpkg/rpm may return non-zero on warnings,
            |# which would prevent the application from relaunching.
            |$installCmd
            |
            |# Clean up the package file
            |rm -f "${D}PKG_FILE"
            |${relaunch(restart, "APP_LAUNCHER", "Relaunch the application")}
            |# Clean up this script
            |rm -f "$D{0}"
            """.trimMargin()
    }

    /** A literal `$`, which cannot be written directly inside these raw strings. */
    private const val D = "$"

    private fun waitForExit(): String =
        """
        |# Wait for the app process to fully exit
        |while kill -0 "${D}APP_PID" 2>/dev/null; do
        |    sleep 0.5
        |done
        """.trimMargin()

    /**
     * The script inherits the app's working directory, and so does the relaunched app. If that
     * directory has been deleted since the app started (say, a terminal's directory that was
     * later removed), the JVM refuses to start ("Could not determine current working directory")
     * and the relaunch dies silently. Move to a directory that exists first: the app's own when it
     * is still there, else `$HOME`, else `/`. `cd` only fails here, so none of this can trip
     * `errexit`.
     */
    private fun enterWorkingDir(workingDir: String): String =
        """
        |# Run from a directory that exists, or the relaunched JVM cannot start
        |cd -- ${shLiteral(workingDir)} 2>/dev/null || cd -- "${D}HOME" 2>/dev/null || cd /
        """.trimMargin()

    private fun relaunch(
        restart: Boolean,
        targetVar: String,
        comment: String,
    ): String =
        if (restart) {
            "\n# $comment\nnohup \"$D$targetVar\" > /dev/null 2>&1 &\n"
        } else {
            ""
        }
}
