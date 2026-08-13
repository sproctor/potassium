package com.seanproctor.potassium.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class NotarizationRequestInfoTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun recordFile() = tempFolder.newFolder().resolve(NOTARIZATION_REQUEST_INFO_FILE_NAME)

    @Test
    fun `a saved record reads back`() {
        val file = recordFile()
        val info =
            NotarizationRequestInfo(
                uuid = "2efe2717-52ef-43a5-96dc-0797e4ca1041",
                artifactSha512 = "kX0mVpiV+X6H1kTmA/xAF3g==",
            )
        info.saveTo(file)

        assertEquals(info, NotarizationRequestInfo.loadFrom(file))
    }

    @Test
    fun `a missing record is not resumable`() {
        assertNull(NotarizationRequestInfo.loadFrom(recordFile()))
    }

    @Test
    fun `a record without an artifact hash is not resumable`() {
        // What plugin versions before the resume support wrote: a submission id and nothing to
        // tie it to the bytes it was submitted for.
        val file = recordFile().apply { writeText("uuid=2efe2717-52ef-43a5-96dc-0797e4ca1041\nupload.time=\n") }

        assertNull(NotarizationRequestInfo.loadFrom(file))
    }

    @Test
    fun `a record without a submission id is not resumable`() {
        val file = recordFile().apply { writeText("artifact.sha512=kX0mVpiV+X6H1kTmA/xAF3g==\n") }

        assertNull(NotarizationRequestInfo.loadFrom(file))
    }
}
