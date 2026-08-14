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
     * Replaces the bundle by extracting [zipFile] and swapping the `.app` it contains into place.
     *
     * The archive's bundle directory does not necessarily carry the same name as the installed one
     * — a DMG stages the app under the product name while a ZIP preserves whatever the build
     * produced — so the script never assumes the two match:
     *
     *  1. it extracts into a staging directory next to the installed app and leaves that app
     *     untouched until a complete replacement exists on disk, so a truncated download or a
     *     failed `ditto` cannot leave the machine without an application;
     *  2. it locates the `.app` inside the archive instead of guessing its name;
     *  3. it keeps the installed path when both bundles share a `CFBundleIdentifier`, so Dock
     *     tiles, login items and aliases keep resolving, and only adopts the archive's name when
     *     the identifier changed — a deliberate rebranding — removing the old bundle so no
     *     duplicate is left behind;
     *  4. it restores the previous bundle if the swap fails or is interrupted.
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
        |set -euo pipefail
        |
        |ZIP_FILE=${shLiteral(zipFile)}
        |APP_PATH=${shLiteral(appPath)}
        |INSTALL_DIR=${shLiteral(installDir)}
        |APP_PID=$pid
        |
        |STAGE_DIR="${D}INSTALL_DIR/.potassium-update-$D$D"
        |BACKUP=""
        |TARGET="${D}APP_PATH"
        |
        |# Runs on every exit path, including an interrupt between the two renames below: if the
        |# installed bundle was moved aside and nothing took its place, put it back. Leaving the
        |# machine without an application is the one outcome this script must never produce.
        |cleanup() {
        |    if [ -n "${D}BACKUP" ] && [ -d "${D}BACKUP" ] && [ ! -d "${D}TARGET" ]; then
        |        echo "Restoring the previous bundle after an interrupted update" >&2
        |        mv "${D}BACKUP" "${D}TARGET" || true
        |    fi
        |    rm -rf "${D}STAGE_DIR"
        |}
        |trap cleanup EXIT INT TERM
        |
        |${waitForExit()}
        |
        |# Unpack next to the installed app: same volume, so the swap below is a plain rename, and
        |# the installed bundle stays intact until a complete replacement exists.
        |rm -rf "${D}STAGE_DIR"
        |mkdir -p "${D}STAGE_DIR"
        |ditto -x -k "${D}ZIP_FILE" "${D}STAGE_DIR"
        |
        |# -print -quit rather than a pipe into head: under pipefail a killed `find` surfaces as
        |# exit 141 and errexit would abort here, before any diagnostic is reported.
        |NEW_APP="$D(/usr/bin/find "${D}STAGE_DIR" -maxdepth 2 -name '*.app' -type d -print -quit)"
        |if [ -z "${D}NEW_APP" ] || [ ! -f "${D}NEW_APP/Contents/Info.plist" ]; then
        |    echo "No .app bundle found in ${D}ZIP_FILE — keeping the installed application" >&2
        |    exit 1
        |fi
        |
        |read_bundle_id() {
        |    /usr/libexec/PlistBuddy -c 'Print :CFBundleIdentifier' "${D}1/Contents/Info.plist" 2>/dev/null || true
        |}
        |CURRENT_BUNDLE_ID="$D(read_bundle_id "${D}APP_PATH")"
        |NEW_BUNDLE_ID="$D(read_bundle_id "${D}NEW_APP")"
        |
        |# Same identifier: keep the installed path so the Dock, login items and aliases stay valid.
        |# Different identifier: the app was renamed on purpose, adopt the new name and drop the old.
        |REMOVE_OLD=0
        |if [ -n "${D}CURRENT_BUNDLE_ID" ] && [ -n "${D}NEW_BUNDLE_ID" ] &&
        |    [ "${D}CURRENT_BUNDLE_ID" != "${D}NEW_BUNDLE_ID" ]; then
        |    TARGET="${D}INSTALL_DIR/$D(basename "${D}NEW_APP")"
        |    if [ "${D}TARGET" != "${D}APP_PATH" ]; then
        |        REMOVE_OLD=1
        |    fi
        |fi
        |
        |# Swap through a backup so a failed move can be rolled back.
        |BACKUP="${D}TARGET.potassium-old-$D$D"
        |if [ -d "${D}TARGET" ]; then
        |    mv "${D}TARGET" "${D}BACKUP"
        |fi
        |if ! mv "${D}NEW_APP" "${D}TARGET"; then
        |    echo "Failed to install the new bundle — restoring the previous one" >&2
        |    if [ -d "${D}BACKUP" ]; then
        |        mv "${D}BACKUP" "${D}TARGET"
        |    fi
        |    exit 1
        |fi
        |rm -rf "${D}BACKUP"
        |BACKUP=""
        |
        |if [ "${D}REMOVE_OLD" = "1" ] && [ -d "${D}APP_PATH" ]; then
        |    rm -rf "${D}APP_PATH"
        |fi
        |
        |${clearQuarantine("TARGET")}
        |${relaunch(restart, "TARGET")}
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

    private fun clearQuarantine(bundleVar: String = "APP_PATH"): String =
        """
        |# Remove quarantine attribute
        |xattr -r -d com.apple.quarantine "$D$bundleVar" 2>/dev/null || true
        """.trimMargin()

    /**
     * A failed relaunch must not abort the script under `errexit`: the new version is already
     * installed at that point, and aborting would skip the cleanup that follows.
     */
    private fun relaunch(
        restart: Boolean,
        bundleVar: String = "APP_PATH",
    ): String =
        if (restart) {
            """
            |# Relaunch the app
            |open "$D$bundleVar" || echo "Relaunch failed; the update itself succeeded" >&2
            """.trimMargin()
        } else {
            ""
        }
}
