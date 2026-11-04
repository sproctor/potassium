package com.seanproctor.potassium.updater

import com.seanproctor.potassium.updater.internal.InstallFailureRecord
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class InstallFailureTest {
    private lateinit var updater: PotassiumUpdater

    @Before
    fun setup() {
        updater =
            PotassiumUpdater {
                currentVersion = "2.0.0"
                provider = FakeUpdateProvider()
            }
        cleanup()
    }

    @After
    fun cleanup() {
        InstallFailureRecord.delete()
        InstallFailureRecord.logFile().delete()
    }

    private fun record(text: String) {
        InstallFailureRecord.recordFile().apply {
            parentFile.mkdirs()
            writeText(text)
        }
    }

    @Test
    fun `consumeInstallFailure returns null when no install failed`() {
        assertNull(updater.consumeInstallFailure())
    }

    @Test
    fun `consumeInstallFailure reports the version, exit code and installer output`() {
        record("version=2.1.0\nexitCode=2\n")
        InstallFailureRecord.logFile().writeText("dpkg: error: dpkg frontend lock was locked by another process\n")

        assertEquals(
            InstallFailure("2.1.0", 2, "dpkg: error: dpkg frontend lock was locked by another process"),
            updater.consumeInstallFailure(),
        )
    }

    @Test
    fun `consumeInstallFailure clears the record but keeps the log`() {
        record("version=2.1.0\nexitCode=126\n")
        InstallFailureRecord.logFile().writeText("Error executing command as another user\n")
        updater.consumeInstallFailure()

        assertNull(updater.consumeInstallFailure())
        assertFalse(InstallFailureRecord.recordFile().exists())
        assertEquals("Error executing command as another user\n", InstallFailureRecord.logFile().readText())
    }

    @Test
    fun `consumeInstallFailure reports an unknown version as null`() {
        record("version=\nexitCode=1\n")

        assertEquals(InstallFailure(null, 1, ""), updater.consumeInstallFailure())
    }

    @Test
    fun `consumeInstallFailure keeps only the end of a long log`() {
        record("version=2.1.0\nexitCode=1\n")
        InstallFailureRecord.logFile().writeText("x".repeat(100_000) + "\nthe error")

        val output = updater.consumeInstallFailure()!!.output
        assertEquals(16 * 1024, output.length)
        assertEquals("the error", output.substringAfterLast('\n'))
    }

    @Test
    fun `consumeInstallFailure ignores a failure the running version has overtaken`() {
        // The install failed, then 2.0.0 was installed some other way.
        record("version=2.0.0\nexitCode=2\n")

        assertNull(updater.consumeInstallFailure())
        assertFalse(InstallFailureRecord.recordFile().exists())
    }

    @Test
    fun `consumeInstallFailure ignores a malformed record`() {
        record("exitCode=oops\n")

        assertNull(updater.consumeInstallFailure())
    }
}
