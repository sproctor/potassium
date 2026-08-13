/*
 * Copyright 2020-2026 JetBrains s.r.o. and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

package com.seanproctor.potassium.internal

/** A verdict Apple's notary service reports for a submission, spelled the way `notarytool` prints it. */
internal enum class NotarizationStatus(
    val printedName: String,
) {
    Accepted("Accepted"),
    InProgress("In Progress"),
    Invalid("Invalid"),
    Rejected("Rejected"),
    ;

    companion object {
        // notarytool prints the status as an indented "status: <value>" line of the submission
        // record it dumps. Its progress chatter ("Current status: In Progress......") does not
        // start a line with "status:", so it is never mistaken for the verdict.
        private val STATUS_REGEX = Regex("""^[ \t]*status:[ \t]*(\S.*?)[ \t\r]*$""", RegexOption.MULTILINE)

        /**
         * Reads the verdict off `notarytool` output, or returns null when the output carries no
         * status this plugin knows. `submit --wait` prints the record after its progress lines,
         * so the last status in the output is the one that counts.
         */
        fun parse(output: String): NotarizationStatus? {
            val lastStatusLine = STATUS_REGEX.findAll(output).lastOrNull() ?: return null
            val printed = lastStatusLine.groupValues[1]
            return entries.firstOrNull { it.printedName.equals(printed, ignoreCase = true) }
        }
    }
}
