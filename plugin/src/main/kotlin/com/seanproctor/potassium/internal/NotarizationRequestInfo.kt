/*
 * Copyright 2020-2021 JetBrains s.r.o. and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

package com.seanproctor.potassium.internal

import java.io.File
import java.io.IOException
import java.util.Properties

internal const val NOTARIZATION_REQUEST_INFO_FILE_NAME = "notarization-request.properties"

/**
 * A submission handed to Apple's notary service, recorded on disk so that a re-run of a
 * notarization task can pick that submission up again instead of uploading the same bytes twice.
 *
 * [artifactSha512] ties the record to the artifact it describes: a record left behind by an
 * earlier build must not be resumed against a freshly built one, or the task would staple a
 * verdict Apple reached for different bytes.
 */
internal data class NotarizationRequestInfo(
    val uuid: String,
    val artifactSha512: String,
) {
    fun saveTo(file: File) {
        val properties = Properties()
        properties[UUID] = uuid
        properties[ARTIFACT_SHA512] = artifactSha512
        file.outputStream().buffered().use { output ->
            properties.store(output, null)
        }
    }

    companion object {
        private const val UUID = "uuid"
        private const val ARTIFACT_SHA512 = "artifact.sha512"

        /**
         * Reads back a record written by [saveTo]. Returns null when [file] does not exist, or
         * when it carries less than a submission id and the hash of the artifact it belongs to:
         * a partial record — a torn write, or one written by a plugin version that did not record
         * the hash yet — cannot be matched against an artifact, so it must not be resumed.
         *
         * @throws IOException if the file exists but cannot be read.
         */
        fun loadFrom(file: File): NotarizationRequestInfo? {
            if (!file.isFile) return null
            val properties =
                Properties().apply {
                    file.inputStream().buffered().use { input ->
                        load(input)
                    }
                }
            val uuid = properties.getProperty(UUID).orEmpty().trim()
            val artifactSha512 = properties.getProperty(ARTIFACT_SHA512).orEmpty().trim()
            if (uuid.isEmpty() || artifactSha512.isEmpty()) return null
            return NotarizationRequestInfo(uuid = uuid, artifactSha512 = artifactSha512)
        }
    }
}
