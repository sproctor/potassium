package com.seanproctor.nucleus.updater

/** An in-memory [Environment] for tests. Paths use `/` separators on all hosts. */
class FakeEnvironment(
    override val platform: Platform,
    private val envVars: Map<String, String> = emptyMap(),
    private val properties: Map<String, String> = emptyMap(),
    private val files: Map<String, String> = emptyMap(),
    private val executable: String? = null,
) : Environment {
    override fun getenv(name: String): String? = envVars[name]

    override fun systemProperty(name: String): String? = properties[name]

    override fun fileExists(path: String): Boolean = files.containsKey(path)

    override fun readText(path: String): String? = files[path]

    override fun executablePath(): String? = executable
}
