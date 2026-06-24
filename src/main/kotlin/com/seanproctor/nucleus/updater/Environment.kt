package com.seanproctor.nucleus.updater

import java.io.File

/**
 * Host-environment accessors, abstracted so [InstallTypeDetector] is unit-testable with a
 * fake. The production implementation is [SystemEnvironment].
 */
interface Environment {
    /** The current platform. */
    val platform: Platform

    /** An environment variable's value, or null if unset. */
    fun getenv(name: String): String?

    /** A JVM system property's value, or null. */
    fun systemProperty(name: String): String?

    /** Whether a regular file exists at [path]. */
    fun fileExists(path: String): Boolean

    /** The text content of [path], or null if it is not a readable file. */
    fun readText(path: String): String?

    /** The current process's executable path (used to locate the install dir), or null. */
    fun executablePath(): String?
}

/** [Environment] backed by the real JVM and filesystem. */
object SystemEnvironment : Environment {
    override val platform: Platform get() = Platform.current

    override fun getenv(name: String): String? = System.getenv(name)

    override fun systemProperty(name: String): String? = System.getProperty(name)

    override fun fileExists(path: String): Boolean = File(path).isFile

    override fun readText(path: String): String? = File(path).takeIf { it.isFile }?.readText()

    override fun executablePath(): String? = ProcessHandle.current().info().command().orElse(null)
}
