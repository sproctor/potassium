package com.seanproctor.potassium.updater

import com.seanproctor.potassium.updater.runtime.Platform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExecutableTypeValidationTest {
    private fun updater(type: InstallType?) =
        PotassiumUpdater {
            currentVersion = "1.0.0"
            provider = FakeUpdateProvider()
            executableType = type
        }

    /** An install format belonging to some platform other than the one running these tests. */
    private fun foreignType(): InstallType =
        when (Platform.Current) {
            Platform.Windows -> InstallType.DEB
            else -> InstallType.MSI
        }

    /** An install format belonging to the platform running these tests, or null on an unknown OS. */
    private fun nativeType(): InstallType? =
        when (Platform.Current) {
            Platform.Windows -> InstallType.NSIS
            Platform.MacOS -> InstallType.DMG
            Platform.Linux -> InstallType.DEB
            Platform.Unknown -> null
        }

    @Test
    fun `a format from another platform is rejected`() {
        val foreign = foreignType()
        val error =
            runCatching { updater(foreign) }
                .exceptionOrNull()

        assertNotNull("expected construction to fail for ${foreign.id} on ${Platform.Current}", error)
        assertTrue(error is IllegalArgumentException)
        // The message has to name both sides, or the reader cannot tell which one is wrong.
        val message = error?.message.orEmpty()
        assertTrue(message, message.contains(foreign.id))
        assertTrue(message, message.contains(Platform.Current.name))
    }

    @Test
    fun `a format from this platform is accepted`() {
        val native = nativeType() ?: return
        assertEquals("1.0.0", updater(native).currentVersion)
    }

    @Test
    fun `a platform-independent format is accepted anywhere`() {
        // ZIP is how macOS updates apply, and is offered on the other platforms too.
        assertEquals("1.0.0", updater(InstallType.ZIP).currentVersion)
    }

    @Test
    fun `an unset executableType is accepted`() {
        assertEquals("1.0.0", updater(null).currentVersion)
    }
}
