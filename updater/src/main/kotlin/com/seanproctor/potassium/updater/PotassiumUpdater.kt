package com.seanproctor.potassium.updater

import com.seanproctor.potassium.updater.exception.ChecksumException
import com.seanproctor.potassium.updater.exception.NetworkException
import com.seanproctor.potassium.updater.exception.NoMatchingFileException
import com.seanproctor.potassium.updater.exception.UpdateException
import com.seanproctor.potassium.updater.internal.ChecksumVerifier
import com.seanproctor.potassium.updater.internal.FileSelector
import com.seanproctor.potassium.updater.internal.InstallTypeDetector
import com.seanproctor.potassium.updater.internal.PlatformInfo
import com.seanproctor.potassium.updater.internal.PlatformInstaller
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
        val type = resolveExecutableType() ?: return false
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

    public fun downloadUpdate(info: UpdateInfo): Flow<DownloadProgress> =
        flow {
            pendingUpdateVersion = info.version
            val targetFile = info.currentFile
            val tempDir = System.getProperty("java.io.tmpdir")
            val tempFile = File(tempDir, "${targetFile.fileName}.download")
            val finalFile = File(tempDir, targetFile.fileName)

            try {
                val requestBuilder =
                    HttpRequest
                        .newBuilder()
                        .uri(URI.create(targetFile.url))
                        .GET()
                applyAuthHeaders(requestBuilder)
                val response = httpClient.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofInputStream())

                if (response.statusCode() != HTTP_OK) {
                    throw NetworkException("HTTP ${response.statusCode()} downloading ${targetFile.url}")
                }

                val totalBytes = targetFile.size
                var bytesDownloaded = 0L

                response.body().use { inputStream ->
                    tempFile.outputStream().use { outputStream ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        var bytesRead: Int
                        while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                            outputStream.write(buffer, 0, bytesRead)
                            bytesDownloaded += bytesRead
                            val percent =
                                if (totalBytes > 0) {
                                    (bytesDownloaded.toDouble() / totalBytes * PERCENT_MAX).coerceAtMost(PERCENT_MAX)
                                } else {
                                    0.0
                                }
                            emit(DownloadProgress(bytesDownloaded, totalBytes, percent))
                        }
                    }
                }

                // Verify checksum
                if (!ChecksumVerifier.verify(tempFile, targetFile.sha512)) {
                    val actual = ChecksumVerifier.computeSha512Base64(tempFile)
                    tempFile.delete()
                    throw ChecksumException(targetFile.sha512, actual)
                }

                // Rename to final file
                if (finalFile.exists()) finalFile.delete()
                tempFile.renameTo(finalFile)

                emit(DownloadProgress(bytesDownloaded, totalBytes, PERCENT_MAX, finalFile))
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
     */
    public fun wasJustUpdated(): Boolean = UpdateMarker.exists()

    private fun peekUpdateEvent(): UpdateEvent? {
        val (previousVersion, newVersion) = UpdateMarker.read() ?: return null
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
        // resources/package-type); macOS resolves to ZIP and Windows to NSIS. A null format lets
        // FileSelector fall back to the platform default. Users can force one via config.executableType.
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
        private const val PERCENT_MAX = 100.0

        private val SELF_UPDATABLE_TYPES =
            setOf(
                InstallType.EXE,
                InstallType.NSIS,
                InstallType.NSIS_WEB,
                InstallType.MSI,
                InstallType.DMG,
                InstallType.ZIP,
                InstallType.APPIMAGE,
                InstallType.DEB,
                InstallType.RPM,
            )
    }
}
