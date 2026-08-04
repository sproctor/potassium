package com.seanproctor.potassium.updater.internal

/**
 * Bodies of the detached scripts that replace the installed `.app` bundle after a macOS update.
 *
 * Both wait for the running app to exit before touching the bundle — the update is applied by a
 * process that outlives it — and both relaunch only when asked. Kept separate from
 * [PlatformInstaller] so the generated text can be parsed and asserted in tests instead of only
 * being exercised by running a real update.
 */
internal object MacInstallScripts {
    /**
     * Replaces the bundle by extracting [zipFile] over [installDir].
     *
     * The ZIP contains the `.app` at its root, so extracting into the install directory restores
     * it in place.
     */
    fun forZip(
        zipFile: String,
        appPath: String,
        installDir: String,
        pid: Long,
        restart: Boolean,
    ): String =
        """
        |#!/usr/bin/env bash
        |set -e
        |
        |ZIP_FILE=${shLiteral(zipFile)}
        |APP_PATH=${shLiteral(appPath)}
        |INSTALL_DIR=${shLiteral(installDir)}
        |APP_PID=$pid
        |
        |${waitForExit()}
        |
        |# Remove old app bundle
        |if [ -d "${D}APP_PATH" ]; then
        |    rm -rf "${D}APP_PATH"
        |fi
        |
        |# Extract the ZIP
        |ditto -x -k "${D}ZIP_FILE" "${D}INSTALL_DIR"
        |
        |${clearQuarantine()}
        |${relaunch(restart)}
        |# Clean up
        |rm -f "${D}ZIP_FILE"
        |rm -f "$D{0}"
        """.trimMargin()

    /**
     * Replaces the bundle by mounting [dmgFile] and copying the `.app` out of it.
     *
     * Mounting and copying rather than handing the image to Finder is what makes a DMG update
     * silent and relaunchable: `open` on a DMG only mounts it and leaves the install to the user
     * dragging the bundle, which reports no completion for the updater to act on.
     */
    fun forDmg(
        dmgFile: String,
        appPath: String,
        mountPoint: String,
        pid: Long,
        restart: Boolean,
    ): String =
        """
        |#!/usr/bin/env bash
        |set -e
        |
        |DMG_FILE=${shLiteral(dmgFile)}
        |APP_PATH=${shLiteral(appPath)}
        |MOUNT_POINT=${shLiteral(mountPoint)}
        |APP_PID=$pid
        |
        |${waitForExit()}
        |
        |mkdir -p "${D}MOUNT_POINT"
        |# Detach on every exit path so a failed copy never leaves the image mounted.
        |trap 'hdiutil detach "${D}MOUNT_POINT" -force >/dev/null 2>&1 || true; rmdir "${D}MOUNT_POINT" 2>/dev/null || true' EXIT
        |
        |# -nobrowse keeps the volume out of Finder. `yes` answers the licence prompt of an image
        |# carrying a software licence agreement, which would otherwise block on stdin forever.
        |# -noverify skips hdiutil's checksum pass; the whole file was already verified against the
        |# manifest SHA-512 before this script was written.
        |yes | hdiutil attach "${D}DMG_FILE" -nobrowse -readonly -noverify -mountpoint "${D}MOUNT_POINT" >/dev/null
        |
        |# The bundle sits at the volume root, beside the /Applications symlink; searching deeper
        |# would risk matching a nested helper app. A glob rather than `find -quit`, whose
        |# availability differs between BSD and GNU builds; an unmatched glob stays literal and
        |# fails the -d test, which the emptiness check below reports.
        |NEW_APP=""
        |for candidate in "${D}MOUNT_POINT"/*.app; do
        |    if [ -d "${D}candidate" ]; then
        |        NEW_APP="${D}candidate"
        |        break
        |    fi
        |done
        |if [ -z "${D}NEW_APP" ]; then
        |    echo "No .app found in ${D}DMG_FILE" >&2
        |    exit 1
        |fi
        |
        |# Remove old app bundle
        |if [ -d "${D}APP_PATH" ]; then
        |    rm -rf "${D}APP_PATH"
        |fi
        |
        |ditto "${D}NEW_APP" "${D}APP_PATH"
        |
        |${clearQuarantine()}
        |${relaunch(restart)}
        |# Clean up
        |rm -f "${D}DMG_FILE"
        |rm -f "$D{0}"
        """.trimMargin()

    /** A literal `$`, which cannot be written directly inside these raw strings. */
    private const val D = "$"

    private fun waitForExit(): String =
        """
        |# Wait for the app process to fully exit
        |while kill -0 "${D}APP_PID" 2>/dev/null; do
        |    sleep 0.5
        |done
        """.trimMargin()

    private fun clearQuarantine(): String =
        """
        |# Remove quarantine attribute
        |xattr -r -d com.apple.quarantine "${D}APP_PATH" 2>/dev/null || true
        """.trimMargin()

    private fun relaunch(restart: Boolean): String =
        if (restart) {
            """
            |# Relaunch the app
            |open "${D}APP_PATH"
            """.trimMargin()
        } else {
            ""
        }
}
