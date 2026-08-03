package com.seanproctor.potassium.updater.internal

import java.io.File

/**
 * Reads marker files from the packaged app's `resources/` directory — electron-builder's
 * per-target `package-type`, the plugin-written `updater-cache-dir`, etc.
 *
 * Candidate locations are derived from the bundled runtime path and the launcher path,
 * mirroring jpackage layouts. Only path arithmetic happens here — existence checks and
 * reads go through [InstallEnvironment] so callers stay unit-testable.
 */
internal object AppResources {
    /** The trimmed content of `resources/<fileName>`, or null when absent or blank. */
    fun read(
        env: InstallEnvironment,
        fileName: String,
    ): String? {
        val path =
            resourceDirCandidates(env)
                .map { "$it/$fileName" }
                .firstOrNull { env.fileExists(it) }
                ?: return null
        return env.readText(path)?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun resourceDirCandidates(env: InstallEnvironment): List<String> =
        buildList {
            env.systemProperty("java.home")?.let { javaHome ->
                val runtimeDir = File(javaHome)
                runtimeDir.parentFile?.parentFile?.let { add("${it.path}/resources") }
                runtimeDir.parentFile?.let { add("${it.path}/resources") }
                add("$javaHome/resources")
            }
            env.executablePath()?.let { exe ->
                File(exe).parentFile?.let { dir ->
                    add("${dir.path}/resources")
                    dir.parentFile?.let { add("${it.path}/resources") }
                }
            }
        }.distinct()
}
