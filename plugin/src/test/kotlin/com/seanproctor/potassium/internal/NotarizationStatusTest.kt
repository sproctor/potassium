package com.seanproctor.potassium.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NotarizationStatusTest {
    @Test
    fun `parses the verdict out of notarytool info output`() {
        val output =
            """
            Successfully received submission info
              createdDate: 2026-08-13T09:12:41.532Z
              id: 2efe2717-52ef-43a5-96dc-0797e4ca1041
              name: MyApp-1.0.0-mac-arm64.dmg
              status: Accepted
            """.trimIndent()

        assertEquals(NotarizationStatus.Accepted, NotarizationStatus.parse(output))
    }

    @Test
    fun `parses a two-word status`() {
        val output =
            """
            Successfully received submission info
              id: 2efe2717-52ef-43a5-96dc-0797e4ca1041
              status: In Progress
            """.trimIndent()

        assertEquals(NotarizationStatus.InProgress, NotarizationStatus.parse(output))
    }

    @Test
    fun `takes the verdict from the record submit prints last, not from its progress lines`() {
        val output =
            """
            Conducting pre-submission checks for MyApp-1.0.0-mac-arm64.dmg and initiating connection to Apple
            Submission ID received
              id: 2efe2717-52ef-43a5-96dc-0797e4ca1041
            Successfully uploaded file
              id: 2efe2717-52ef-43a5-96dc-0797e4ca1041
              path: /build/MyApp-1.0.0-mac-arm64.dmg
            Waiting for processing to complete.
            Current status: In Progress......
            Processing complete
              id: 2efe2717-52ef-43a5-96dc-0797e4ca1041
              status: Invalid
            """.trimIndent()

        assertEquals(NotarizationStatus.Invalid, NotarizationStatus.parse(output))
    }

    @Test
    fun `progress chatter alone is not a verdict`() {
        val output =
            """
            Waiting for processing to complete.
            Current status: In Progress......
            """.trimIndent()

        assertNull(NotarizationStatus.parse(output))
    }

    @Test
    fun `tolerates carriage returns and reports an unknown status as no verdict`() {
        assertEquals(NotarizationStatus.Rejected, NotarizationStatus.parse("  status: Rejected\r\n"))
        assertNull(NotarizationStatus.parse("  status: Something Else\n"))
        assertNull(NotarizationStatus.parse("Error: The Internet connection appears to be offline."))
    }
}
