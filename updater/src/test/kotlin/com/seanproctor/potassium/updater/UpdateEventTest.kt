package com.seanproctor.potassium.updater

import com.seanproctor.potassium.updater.internal.UpdateMarker
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class UpdateEventTest {
    private lateinit var updater: PotassiumUpdater

    @Before
    fun setup() {
        updater =
            PotassiumUpdater {
                currentVersion = "2.0.0"
                provider = FakeUpdateProvider()
            }
        UpdateMarker.delete()
    }

    @After
    fun cleanup() {
        UpdateMarker.delete()
    }

    @Test
    fun `consumeUpdateEvent returns null when no update happened`() {
        assertNull(updater.consumeUpdateEvent())
    }

    @Test
    fun `consumeUpdateEvent returns event with correct data`() {
        UpdateMarker.write("1.0.0", "2.0.0")

        val event = updater.consumeUpdateEvent()
        assertNotNull(event)
        assertEquals("1.0.0", event!!.previousVersion)
        assertEquals("2.0.0", event.newVersion)
        assertEquals(UpdateLevel.MAJOR, event.updateLevel)
    }

    @Test
    fun `consumeUpdateEvent deletes the marker`() {
        UpdateMarker.write("1.0.0", "2.0.0")
        updater.consumeUpdateEvent()

        assertNull(updater.consumeUpdateEvent())
    }

    @Test
    fun `wasJustUpdated returns true when marker exists`() {
        UpdateMarker.write("1.0.0", "2.0.0")
        assertTrue(updater.wasJustUpdated())
    }

    @Test
    fun `wasJustUpdated returns false when no marker exists`() {
        assertFalse(updater.wasJustUpdated())
    }

    @Test
    fun `wasJustUpdated still returns true after peek without consume`() {
        UpdateMarker.write("1.0.0", "2.0.0")
        assertTrue(updater.wasJustUpdated())
        assertTrue(updater.wasJustUpdated())
    }

    @Test
    fun `consumeUpdateEvent reports nothing while the app still runs the previous version`() {
        // Written by a 2.0.0 process whose install has not taken effect — the app still reports 2.0.0.
        UpdateMarker.write("2.0.0", "3.0.0")

        assertNull(updater.consumeUpdateEvent())
    }

    @Test
    fun `wasJustUpdated is false after a failed update`() {
        UpdateMarker.write("2.0.0", "3.0.0")
        assertFalse(updater.wasJustUpdated())
    }

    @Test
    fun `marker is preserved while the install may still be in flight`() {
        // The user reopened the old app while the installer is still running: the event belongs
        // to the update that is about to land, so nothing may discard it.
        UpdateMarker.write("2.0.0", "3.0.0")

        assertFalse(updater.wasJustUpdated())
        assertNull(updater.consumeUpdateEvent())
        assertTrue(UpdateMarker.exists())

        // Once the install completes, the now-3.0.0 app reports the event.
        val updated =
            PotassiumUpdater {
                currentVersion = "3.0.0"
                provider = FakeUpdateProvider()
            }
        val event = updated.consumeUpdateEvent()
        assertNotNull(event)
        assertEquals("2.0.0", event!!.previousVersion)
        assertEquals("3.0.0", event.newVersion)
    }

    @Test
    fun `wasJustUpdated is false for an unparseable marker instead of throwing`() {
        // A torn write from a crash mid-update: Version.fromString("1.") throws on the empty group.
        UpdateMarker.write("1.0.0", "1.")

        assertFalse(updater.wasJustUpdated())
    }

    @Test
    fun `consumeUpdateEvent returns null for an unparseable marker instead of throwing`() {
        UpdateMarker.write("1.0.0", "1.")

        assertNull(updater.consumeUpdateEvent())
    }

    @Test
    fun `an out-of-range version component does not throw`() {
        UpdateMarker.write("1.0.0", "99999999999999.0.0")

        assertFalse(updater.wasJustUpdated())
        assertNull(updater.consumeUpdateEvent())
    }

    @Test
    fun `consumeUpdateEvent detects minor update level`() {
        UpdateMarker.write("1.0.0", "1.1.0")

        val event = updater.consumeUpdateEvent()
        assertNotNull(event)
        assertEquals(UpdateLevel.MINOR, event!!.updateLevel)
    }

    @Test
    fun `consumeUpdateEvent detects patch update level`() {
        UpdateMarker.write("1.0.0", "1.0.1")

        val event = updater.consumeUpdateEvent()
        assertNotNull(event)
        assertEquals(UpdateLevel.PATCH, event!!.updateLevel)
    }

    @Test
    fun `consumeUpdateEvent detects pre-release update level`() {
        UpdateMarker.write("1.0.0-beta.1", "1.0.0-beta.2")

        val event = updater.consumeUpdateEvent()
        assertNotNull(event)
        assertEquals(UpdateLevel.PRE_RELEASE, event!!.updateLevel)
    }
}
