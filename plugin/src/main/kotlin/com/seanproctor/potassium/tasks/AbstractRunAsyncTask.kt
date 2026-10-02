package com.seanproctor.potassium.tasks

import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Launches the application like `run`, but as a detached process: the task finishes once the
 * JVM is up instead of waiting for the app to exit. That frees the build for IDEs that run one
 * Gradle build at a time (Android Studio queues every other task behind a running `run`).
 *
 * Output goes to `output.log` in [runDir]. A previous instance launched by this task is stopped
 * first, so re-running the task restarts the app rather than starting a second copy.
 */
@DisableCachingByDefault(because = "Launches the application, not a cacheable build step")
abstract class AbstractRunAsyncTask : AbstractPotassiumTask() {
    @get:Input
    abstract val javaExecutable: Property<String>

    @get:Input
    abstract val mainClass: Property<String>

    @get:Input
    abstract val jvmArgs: ListProperty<String>

    @get:Input
    abstract val args: ListProperty<String>

    @get:Classpath
    abstract val classpath: ConfigurableFileCollection

    @get:Internal
    abstract val workingDir: DirectoryProperty

    /** Holds the argument file, the PID file and the app's output log. */
    @get:Internal
    abstract val runDir: DirectoryProperty

    @TaskAction
    fun launch() {
        val dir = runDir.get().asFile.apply { mkdirs() }
        val pidFile = dir.resolve("app.pid")
        stopPreviousInstance(pidFile)

        val argFile = dir.resolve("app.args")
        writeJavaArgFile(
            argFile,
            jvmArgs.get() + listOf("-cp", classpath.asPath, mainClass.get()) + args.get(),
        )
        val logFile = dir.resolve("output.log")
        val process =
            ProcessBuilder(javaExecutable.get(), "@${argFile.absolutePath}")
                .directory(workingDir.get().asFile)
                .redirectErrorStream(true)
                .redirectOutput(logFile)
                .start()
        // Nothing feeds the app's stdin; close it so a read sees EOF instead of blocking.
        process.outputStream.close()

        // Catch a JVM that dies on startup (bad flag, missing main class): once this task
        // returns, nobody is watching the process.
        if (process.waitFor(STARTUP_CHECK_SECONDS, TimeUnit.SECONDS) && process.exitValue() != 0) {
            throw GradleException(
                "Application exited with code ${process.exitValue()} during startup. " +
                    "Output:\n${logFile.readText().takeLast(LOG_TAIL_CHARS)}",
            )
        }
        val startInstant =
            process
                .toHandle()
                .info()
                .startInstant()
                .orElse(null)
        pidFile.writeText("${process.pid()} ${startInstant?.toEpochMilli() ?: ""}")
        logger.lifecycle("Started ${mainClass.get()} (pid ${process.pid()}). Output: $logFile")
    }

    private fun stopPreviousInstance(pidFile: File) {
        if (!pidFile.isFile) return
        val parts = pidFile.readText().trim().split(" ")
        pidFile.delete()
        val pid = parts.getOrNull(0)?.toLongOrNull() ?: return
        val recordedStart = parts.getOrNull(1)?.toLongOrNull()
        val handle = ProcessHandle.of(pid).orElse(null) ?: return
        // A reused PID belongs to an unrelated process; only stop the one this task started.
        val actualStart =
            handle
                .info()
                .startInstant()
                .orElse(null)
                ?.toEpochMilli()
        if (recordedStart == null || actualStart != recordedStart || !handle.isAlive) return

        logger.lifecycle("Stopping previous instance (pid $pid)")
        val processes = handle.descendants().toList() + handle
        processes.forEach { it.destroy() }
        val exited =
            runCatching {
                handle.onExit().get(STOP_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            }.isSuccess
        if (!exited) processes.forEach { it.destroyForcibly() }
    }

    private companion object {
        const val STARTUP_CHECK_SECONDS = 2L
        const val STOP_TIMEOUT_SECONDS = 10L
        const val LOG_TAIL_CHARS = 4000
    }
}
