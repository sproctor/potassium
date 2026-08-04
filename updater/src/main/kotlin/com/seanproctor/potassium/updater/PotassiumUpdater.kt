package com.seanproctor.potassium.updater

import com.seanproctor.potassium.updater.exception.NetworkException
import com.seanproctor.potassium.updater.exception.NoMatchingFileException
import com.seanproctor.potassium.updater.exception.UpdateException
import com.seanproctor.potassium.updater.internal.FileSelector
import com.seanproctor.potassium.updater.internal.InstallTypeDetector
import com.seanproctor.potassium.updater.internal.PlatformInfo
import com.seanproctor.potassium.updater.internal.PlatformInstaller
import com.seanproctor.potassium.updater.internal.UpdateCache
import com.seanproctor.potassium.updater.internal.UpdateDownloadEngine
import com.seanproctor.potassium.updater.internal.UpdateMarker
import com.seanproctor.potassium.updater.internal.YamlParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.coroutines.cancellation.CancellationException

public class PotassiumUpdater(
    private val config: UpdaterConfig,
) {
    public val currentVersion: String get() = config.currentVersion

    public val channel: String
        get() = resolveChannel()

    private var pendingUpdateVersion: String? = null

    private val httpClient: HttpClient =
        config.httpClient
            ?: HttpClient
                .newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build()

    private val installTypeDetector = InstallTypeDetector()

    public fun isUpdateSupported(): Boolean {
        // An explicitly configured type is an opt-in: it also unlocks MSI, which is never
        // self-updated when merely detected (see OPT_IN_UPDATABLE_TYPES).
        config.executableType?.let { return it in OPT_IN_UPDATABLE_TYPES }
        val type = installTypeDetector.detect() ?: return false
        return type in SELF_UPDATABLE_TYPES
    }

    public suspend fun checkForUpdates(): UpdateResult {
        if (config.isDevMode()) return UpdateResult.NotAvailable
        if (!isUpdateSupported()) return UpdateResult.NotAvailable
        return withContext(Dispatchers.IO) {
            try {
                doCheckForUpdates()
            } catch (e: UpdateException) {
                UpdateResult.Error(e)
            } catch (e: CancellationException) {
                throw e
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception,
            ) {
                UpdateResult.Error(NetworkException("Failed to check for updates", e))
            }
        }
    }

    /**
     * Downloads the update, emitting progress; the terminal emission carries the finished
     * [DownloadProgress.file]. Downloads are differential (blockmap-based) when possible,
     * so [DownloadProgress.totalBytes] reflects the planned transfer size — which may be
     * `0` when the artifact can be assembled entirely from local data. Rely on
     * `file != null` for completion and guard divisions by `totalBytes`.
     */
    public fun downloadUpdate(info: UpdateInfo): Flow<DownloadProgress> =
        flow {
            pendingUpdateVersion = info.version
            val targetFile = info.currentFile
            val tempDir = System.getProperty("java.io.tmpdir")
            val tempFile = File(tempDir, "${targetFile.fileName}.download")
            val finalFile = File(tempDir, targetFile.fileName)

            try {
                val engine =
                    UpdateDownloadEngine(
                        httpClient = httpClient,
                        config = config,
                        resolveInstallType = ::resolveExecutableType,
                        cache = updateCache(),
                    )
                engine.execute(info, targetFile, tempFile, finalFile) { emit(it) }
            } catch (e: UpdateException) {
                tempFile.delete()
                throw e
            } catch (e: CancellationException) {
                tempFile.delete()
                throw e
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception,
            ) {
                tempFile.delete()
                throw NetworkException("Download failed", e)
            }
        }.flowOn(Dispatchers.IO)

    public fun installAndRestart(installerFile: File) {
        writeUpdateMarker()
        val platform = PlatformInfo.currentPlatform()
        PlatformInstaller.install(installerFile, platform, restart = true)
    }

    public fun installAndQuit(installerFile: File) {
        writeUpdateMarker()
        val platform = PlatformInfo.currentPlatform()
        PlatformInstaller.install(installerFile, platform, restart = false)
    }

    /**
     * Returns the update event if the application was just updated, and consumes it
     * so that subsequent calls return `null`. Use this on startup to detect a
     * post-update launch (e.g. to show a "What's new" dialog or run migrations).
     */
    public fun consumeUpdateEvent(): UpdateEvent? {
        val event = peekUpdateEvent() ?: return null
        UpdateMarker.delete()
        return event
    }

    /**
     * Returns `true` if the application was launched after an update.
     * Does **not** consume the event — call [consumeUpdateEvent] to clear it.
     *
     * Answers `false` whenever an update cannot be positively established, including an
     * unreadable or malformed marker; it never throws. Both this and [consumeUpdateEvent] are
     * typically called during startup, where an exception would take the application down.
     */
    public fun wasJustUpdated(): Boolean = peekUpdateEvent() != null

    /** The recorded update event, or null when there is no positive evidence of one. Never throws. */
    private fun peekUpdateEvent(): UpdateEvent? =
        try {
            readUpdateEvent()
        } catch (
            @Suppress("TooGenericExceptionCaught") _: Exception,
        ) {
            // A marker that cannot be read or parsed — e.g. a torn write from a crash during
            // the non-atomic UpdateMarker.write — is not evidence of an update. Report "not
            // updated" instead of propagating into the caller's startup path. The file is left
            // alone: the next update overwrites it, and deleting it here would mean a corrupt
            // read could discard a marker the caller never got to see.
            null
        }

    private fun readUpdateEvent(): UpdateEvent? {
        val (previousVersion, newVersion) = UpdateMarker.read() ?: return null
        // The marker is written before the installer runs. If the app still reports the exact
        // version recorded then, the update has not taken effect — either the install failed, or
        // it is still running and the user reopened the old app — so report no event. Both
        // strings originate from config.currentVersion (one process generation apart), so strict
        // equality is the right comparison; parsing would conflate differently-formatted strings
        // and could falsely pass validation.
        //
        // The marker is deliberately kept: an install still in flight will make it valid, and
        // only consumeUpdateEvent() — which delivers the event — clears it.
        if (previousVersion == config.currentVersion) return null
        val level = Version.fromString(newVersion).levelFrom(Version.fromString(previousVersion))
        return UpdateEvent(previousVersion, newVersion, level)
    }

    private fun writeUpdateMarker() {
        val targetVersion = pendingUpdateVersion ?: return
        try {
            UpdateMarker.write(config.currentVersion, targetVersion)
        } catch (
            @Suppress("TooGenericExceptionCaught") _: Exception,
        ) {
            // Best-effort: don't prevent the update if the marker can't be written
        }
    }

    private fun doCheckForUpdates(): UpdateResult {
        val platform = PlatformInfo.currentPlatform()
        val arch = PlatformInfo.currentArch()
        val metadataUrl = config.provider.resolveMetadataUrl(channel, platform, httpClient)

        val requestBuilder =
            HttpRequest
                .newBuilder()
                .uri(URI.create(metadataUrl))
                .GET()
        applyAuthHeaders(requestBuilder)
        val response = httpClient.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString())

        if (response.statusCode() != HTTP_OK) {
            return UpdateResult.Error(NetworkException("HTTP ${response.statusCode()} for $metadataUrl"))
        }

        val metadata = YamlParser.parse(response.body())
        val currentVersion = Version.fromString(config.currentVersion)
        val remoteVersion = Version.fromString(metadata.version)

        val isNewer = remoteVersion > currentVersion
        val isDowngrade = remoteVersion < currentVersion

        if (!isNewer && !(config.allowDowngrade && isDowngrade)) {
            return UpdateResult.NotAvailable
        }

        val executableType = resolveExecutableType()

        // The install format is detected at runtime (APPIMAGE/SNAP/FLATPAK env, electron-builder's
        // resources/package-type, the NSIS uninstaller / portable env / WindowsApps path on
        // Windows); macOS resolves to ZIP. A null format lets FileSelector fall back to the
        // platform default. Users can force one via config.executableType.
        val format = executableType?.id

        val selectedFile =
            FileSelector.select(
                files = metadata.files,
                platform = platform,
                arch = arch,
                format = format,
            ) ?: return UpdateResult.Error(
                NoMatchingFileException(
                    platform.name,
                    arch.name,
                    format ?: "auto",
                ),
            )

        val updateInfo =
            UpdateInfo(
                version = metadata.version,
                releaseDate = metadata.releaseDate,
                files =
                    metadata.files.map { file ->
                        UpdateFile(
                            url = config.provider.getDownloadUrl(file.url, metadata.version),
                            sha512 = file.sha512,
                            size = file.size,
                            blockMapSize = file.blockMapSize,
                            fileName = file.url,
                        )
                    },
                currentFile =
                    UpdateFile(
                        url = config.provider.getDownloadUrl(selectedFile.url, metadata.version),
                        sha512 = selectedFile.sha512,
                        size = selectedFile.size,
                        blockMapSize = selectedFile.blockMapSize,
                        fileName = selectedFile.url,
                    ),
            )

        val level = remoteVersion.levelFrom(currentVersion)

        return UpdateResult.Available(updateInfo, level)
    }

    private fun resolveExecutableType(): InstallType? = config.executableType ?: installTypeDetector.detect()

    private fun updateCache(): UpdateCache = config.updateCacheDir?.let { UpdateCache(it) } ?: UpdateCache()

    private fun applyAuthHeaders(builder: HttpRequest.Builder) {
        config.provider.authHeaders().forEach { (key, value) ->
            builder.header(key, value)
        }
    }

    private fun resolveChannel(): String =
        config.channel ?: when {
            currentVersion.contains("alpha") -> "alpha"
            currentVersion.contains("beta") -> "beta"
            else -> "latest"
        }

    public companion object {
        private const val HTTP_OK = 200

        /** Types the updater installs when they are auto-detected at runtime. */
        private val SELF_UPDATABLE_TYPES =
            setOf(
                InstallType.EXE,
                InstallType.NSIS,
                InstallType.NSIS_WEB,
                InstallType.DMG,
                InstallType.ZIP,
                InstallType.APPIMAGE,
                InstallType.DEB,
                InstallType.RPM,
            )

        /**
         * Types accepted when [UpdaterConfig.executableType] is set explicitly. MSI is opt-in
         * only: electron-builder publishes no `.msi` entries in `latest.yml` and per-machine
         * MSI upgrades require elevation, so MSI installs are treated as managed deployments
         * (Intune/GPO/SCCM) unless the app opts in and serves a manifest listing the `.msi`.
         */
        private val OPT_IN_UPDATABLE_TYPES = SELF_UPDATABLE_TYPES + InstallType.MSI
    }
}
