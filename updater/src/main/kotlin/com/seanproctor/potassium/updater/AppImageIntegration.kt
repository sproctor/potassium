package com.seanproctor.potassium.updater

import com.seanproctor.potassium.updater.internal.DesktopEntryText
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * The relationship between the running AppImage and the user's desktop environment.
 *
 * An AppImage carries its `.desktop` entry and icons inside the image, but nothing on the host
 * installs them, so desktops that resolve icons only through installed entries (GNOME foremost —
 * it ignores the icon a window sets on itself) show a generic icon for the running app. This is
 * the read side of [AppImageIntegration]; [AppImageIntegration.integrate] acts on it.
 */
public sealed class AppImageIntegrationStatus {
    /**
     * The app is not running from an AppImage (or the image carries no desktop entry to
     * integrate). There is nothing to offer.
     */
    public data object NotAppImage : AppImageIntegrationStatus()

    /** No desktop entry for this app exists anywhere; offering to integrate makes sense. */
    public data object NotIntegrated : AppImageIntegrationStatus()

    /** An entry written by [AppImageIntegration.integrate] exists and matches this image. */
    public data class Integrated(
        val desktopFile: File,
    ) : AppImageIntegrationStatus()

    /**
     * An entry written by [AppImageIntegration.integrate] exists but no longer matches — the
     * image moved or carries a different version. It is already broken from the desktop's point
     * of view, so refreshing it with [AppImageIntegration.integrate] needs no further consent.
     */
    public data class Stale(
        val desktopFile: File,
    ) : AppImageIntegrationStatus()

    /**
     * An entry for this app exists that this library did not write: a system package (deb/rpm),
     * an integration tool (Gear Lever, appimaged), or a hand-written file. Leave it alone —
     * writing a second entry would duplicate the launcher.
     */
    public data class ExternallyManaged(
        val desktopFile: File,
    ) : AppImageIntegrationStatus()
}

/** The outcome of [AppImageIntegration.integrate]. */
public sealed class AppImageIntegrationResult {
    public data class Integrated(
        val desktopFile: File,
    ) : AppImageIntegrationResult()

    public data class Failed(
        val reason: String,
        val cause: Throwable? = null,
    ) : AppImageIntegrationResult()
}

/**
 * Installs the running AppImage's own `.desktop` entry and icons into the user's XDG data
 * directory, so the desktop can match the app's windows to a launcher entry and show its icon.
 *
 * The AppImage runtime mounts the image and exports `APPDIR` (the mount point, which holds the
 * `.desktop` file and hicolor icons the packager embedded) and `APPIMAGE` (the image's own path,
 * which is what the installed entry's `Exec=` must point at). Integration is a consent flow by
 * design: call [status], ask the user when it reports [AppImageIntegrationStatus.NotIntegrated],
 * and call [integrate] on acceptance — silent self-integration is the pattern that gave
 * AppImages a bad name. A [AppImageIntegrationStatus.Stale] entry (the user moved or updated the
 * image) is already broken, so refreshing it silently is fine.
 */
public class AppImageIntegration internal constructor(
    private val getenv: (String) -> String?,
    private val runCommand: (List<String>) -> Unit,
) {
    public constructor() : this(System::getenv, ::runQuietly)

    /** Where this app stands with the desktop environment; see [AppImageIntegrationStatus]. */
    public fun status(): AppImageIntegrationStatus {
        val source = sourceEntry() ?: return AppImageIntegrationStatus.NotAppImage
        val target = File(applicationsDir(), source.desktopFile.name)
        if (target.isFile) {
            val text = DesktopEntryText(target.readText())
            val markedPath =
                text.value(MARKER_KEY)
                    ?: return AppImageIntegrationStatus.ExternallyManaged(target)
            val current =
                markedPath == source.appImage.path &&
                    text.value(VERSION_KEY) == source.text.value(VERSION_KEY)
            return if (current) {
                AppImageIntegrationStatus.Integrated(target)
            } else {
                AppImageIntegrationStatus.Stale(target)
            }
        }
        foreignEntryFor(source)?.let { return AppImageIntegrationStatus.ExternallyManaged(it) }
        return AppImageIntegrationStatus.NotIntegrated
    }

    /**
     * Writes the desktop entry (with `Exec=` rewritten to launch the image) and copies the icons.
     * Overwrites an entry this library wrote before; refuses to touch anyone else's.
     */
    public fun integrate(): AppImageIntegrationResult {
        when (val current = status()) {
            AppImageIntegrationStatus.NotAppImage ->
                return AppImageIntegrationResult.Failed("Not running from an AppImage")
            is AppImageIntegrationStatus.ExternallyManaged ->
                return AppImageIntegrationResult.Failed(
                    "The desktop entry at ${current.desktopFile} is managed elsewhere",
                )
            else -> {}
        }
        val source =
            sourceEntry()
                ?: return AppImageIntegrationResult.Failed("Not running from an AppImage")
        val target = File(applicationsDir(), source.desktopFile.name)
        return try {
            val iconOverride = installIcons(source)
            writeDesktopFile(source, target, iconOverride)
            // Refreshes the desktop's caches (MimeType handlers in particular). Best-effort: the
            // entry itself is picked up by file watching either way.
            runCommand(listOf("update-desktop-database", target.parentFile.path))
            AppImageIntegrationResult.Integrated(target)
        } catch (e: IOException) {
            AppImageIntegrationResult.Failed("Could not write the desktop entry", e)
        }
    }

    /** The embedded entry plus the paths integration works from; null when not an AppImage. */
    private class SourceEntry(
        val appImage: File,
        val appDir: File,
        val desktopFile: File,
        val text: DesktopEntryText,
    )

    private fun sourceEntry(): SourceEntry? {
        val appImage = existingFile("APPIMAGE") { it.isFile } ?: return null
        val appDir = existingFile("APPDIR") { it.isDirectory } ?: return null
        val desktopFile =
            appDir
                .listFiles { file -> file.isFile && file.name.endsWith(".desktop") }
                ?.minByOrNull { it.name } ?: return null
        return SourceEntry(appImage, appDir, desktopFile, DesktopEntryText(desktopFile.readText()))
    }

    private fun existingFile(
        variable: String,
        valid: (File) -> Boolean,
    ): File? =
        getenv(variable)
            ?.takeIf { it.isNotBlank() }
            ?.let(::File)
            ?.takeIf(valid)

    /**
     * An entry under another name that already launches this image — an integration tool's
     * (Gear Lever and appimaged name theirs after a hash), found by its `Exec=` referencing the
     * image path. A same-named entry in a *system* applications directory (a deb/rpm install)
     * also counts: its `StartupWMClass` already matches this app's windows, so a second entry
     * would only duplicate the launcher.
     */
    private fun foreignEntryFor(source: SourceEntry): File? {
        val userEntry =
            applicationsDir()
                .listFiles { file -> file.isFile && file.name.endsWith(".desktop") }
                ?.sortedBy { it.name }
                ?.firstOrNull { entry ->
                    DesktopEntryText(entry.readText()).value("Exec")?.contains(source.appImage.path) == true
                }
        if (userEntry != null) return userEntry
        return systemDataDirs()
            .map { File(File(it, "applications"), source.desktopFile.name) }
            .firstOrNull { it.isFile }
    }

    /**
     * Copies the hicolor icon tree for the entry's icon name into the user's icon directory,
     * keeping `Icon=` a themed name. An image without a hicolor tree falls back to its root icon
     * file, copied under the data dir and referenced by absolute path (which the spec allows);
     * the returned override is that path, or null when the themed name still works.
     */
    private fun installIcons(source: SourceEntry): String? {
        val iconName = source.text.value("Icon") ?: return null
        val hicolor = File(source.appDir, "usr/share/icons/hicolor")
        val sized =
            hicolor
                .walkTopDown()
                .filter { it.isFile && it.parentFile.name == "apps" && it.nameWithoutExtension == iconName }
                .toList()
        if (sized.isNotEmpty()) {
            for (icon in sized) {
                val relative = icon.relativeTo(hicolor).path
                copy(icon, File(File(dataHome(), "icons/hicolor"), relative))
            }
            return null
        }
        val rootIcon =
            listOf("$iconName.png", "$iconName.svg", ".DirIcon")
                .map { File(source.appDir, it) }
                .firstOrNull { it.isFile } ?: return null
        val installed = File(dataHome(), "icons/$iconName.png")
        copy(rootIcon, installed)
        return installed.path
    }

    private fun writeDesktopFile(
        source: SourceEntry,
        target: File,
        iconOverride: String?,
    ) {
        val rewritten =
            source.text
                .rewriteForImage(source.appImage.path, iconOverride)
                .withValue(MARKER_KEY, source.appImage.path)
        target.parentFile.mkdirs()
        val temp = File(target.parentFile, "${target.name}.potassium-tmp")
        temp.writeText(rewritten.render())
        Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }

    private fun copy(
        from: File,
        to: File,
    ) {
        to.parentFile.mkdirs()
        Files.copy(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }

    private fun dataHome(): File {
        val configured = getenv("XDG_DATA_HOME")?.takeIf { it.isNotBlank() }
        if (configured != null) return File(configured)
        val home = getenv("HOME")?.takeIf { it.isNotBlank() } ?: System.getProperty("user.home")
        return File(home, ".local/share")
    }

    private fun applicationsDir(): File = File(dataHome(), "applications")

    private fun systemDataDirs(): List<File> =
        (getenv("XDG_DATA_DIRS")?.takeIf { it.isNotBlank() } ?: "/usr/local/share:/usr/share")
            .split(':')
            .filter { it.isNotBlank() }
            .map(::File)

    private companion object {
        /**
         * Marks an entry as written by this library (the value is the image it was written for),
         * so [status] can tell its own work from a system package's or another tool's.
         */
        const val MARKER_KEY = "X-Potassium-AppImage"
        const val VERSION_KEY = "X-AppImage-Version"
    }
}

private fun runQuietly(command: List<String>) {
    try {
        ProcessBuilder(command)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
            .waitFor()
    } catch (_: IOException) {
        // The tool is optional; the desktop picks the entry up by file watching regardless.
    } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
    }
}
