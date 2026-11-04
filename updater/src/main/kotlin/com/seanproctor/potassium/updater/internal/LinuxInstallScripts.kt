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
        |# Remove this script and its private directory on every exit path, a failed update included
        |trap 'rm -f "$D{0}"; rmdir "$(dirname "$D{0}")" 2>/dev/null || true' EXIT
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
        """.trimMargin()

    /**
     * Installs [packageFile] (`deb` or `rpm`, per [extension]) over the jpackage install. The
     * installer's output goes to [logFile]; when it fails, [failureFile] records [version],
     * [runningVersion] and its exit status (see [InstallFailureRecord]) for the relaunched app to
     * report. [packageDir], when given, is the updater's own staging directory holding the package,
     * removed once empty; a package the caller keeps elsewhere leaves its directory alone.
     */
    @Suppress("LongParameterList")
    fun forPackage(
        packageFile: String,
        extension: String,
        launcher: String,
        workingDir: String,
        pid: Long,
        restart: Boolean,
        version: String,
        runningVersion: String,
        logFile: String,
        failureFile: String,
        packageDir: String?,
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
            |NEW_VERSION=${shLiteral(version)}
            |RUNNING_VERSION=${shLiteral(runningVersion)}
            |LOG_FILE=${shLiteral(logFile)}
            |FAILURE_FILE=${shLiteral(failureFile)}
            |
            |${waitForExit()}
            |
            |sleep 1
            |
            |${enterWorkingDir(workingDir)}
            |
            |# Keep the installer's output: this script's own output is discarded, so the log is the
            |# only record of why an install failed. A log that cannot be written must not stop the
            |# install, so fall back to discarding it.
            |{ mkdir -p "$(dirname "${D}LOG_FILE")" && : > "${D}LOG_FILE"; } 2>/dev/null || LOG_FILE=/dev/null
            |rm -f "${D}FAILURE_FILE"
            |
            |# Install the package (shows graphical authentication dialog)
            |$installCmd > "${D}LOG_FILE" 2>&1
            |STATUS=$D?
            |
            |# Any non-zero status is a failed install: dpkg exits 1 when it leaves the package
            |# unconfigured and 2 when it cannot start (say, another package manager holds its lock),
            |# and pkexec exits 126 when the authentication dialog is dismissed. errexit stays off so
            |# the app is relaunched either way; the record tells it why it is still the old version.
            |if [ "${D}STATUS" -ne 0 ]; then
            |    printf '${InstallFailureRecord.KEY_VERSION}=%s\n${InstallFailureRecord.KEY_RUNNING_VERSION}=%s\n${InstallFailureRecord.KEY_EXIT_CODE}=%s\n' \
            |        "${D}NEW_VERSION" "${D}RUNNING_VERSION" "${D}STATUS" > "${D}FAILURE_FILE.tmp" &&
            |        mv -f "${D}FAILURE_FILE.tmp" "${D}FAILURE_FILE"
            |fi
            |
            |# Clean up the package file
            |rm -f "${D}PKG_FILE"
            |${removePackageDir(packageDir)}
            |${relaunch(restart, "APP_LAUNCHER", "Relaunch the application")}
            |# Clean up this script
            |rm -f "$D{0}"
            |rmdir "$(dirname "$D{0}")" 2>/dev/null || true
            """.trimMargin()
    }

    /** Removes the updater's [packageDir] once empty; nothing when the package lives elsewhere. */
    private fun removePackageDir(packageDir: String?): String =
        if (packageDir == null) {
            "# The package's directory is the caller's, so it stays"
        } else {
            "rmdir ${shLiteral(packageDir)} 2>/dev/null || true"
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
